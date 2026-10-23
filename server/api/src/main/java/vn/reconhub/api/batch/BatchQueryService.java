package vn.reconhub.api.batch;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import vn.reconhub.api.web.Views;
import vn.reconhub.core.ai.JobStatusProvider;
import vn.reconhub.core.domain.BatchExecutionAck;
import vn.reconhub.core.files.ReplacementFiles;
import vn.reconhub.core.repo.AppUserRepository;
import vn.reconhub.core.repo.BatchExecutionAckRepository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads Spring Batch's own tables (BATCH_JOB_EXECUTION, BATCH_STEP_EXECUTION, ..._PARAMS) with three queries,
 * instead of JobExplorer's one-query-per-execution walk. Spring Batch writes local timestamps in the batch
 * JVM's zone, so both containers run with the same TZ.
 */
@Service
public class BatchQueryService implements JobStatusProvider {
    private static final DateTimeFormatter HM_DM = DateTimeFormatter.ofPattern("HH:mm dd/MM");

    private final NamedParameterJdbcTemplate jdbc;
    private final ReplacementFiles replacements;
    private final BatchExecutionAckRepository acks;
    private final AppUserRepository users;
    private final ZoneId batchZone = ZoneId.systemDefault();

    public BatchQueryService(NamedParameterJdbcTemplate jdbc, ReplacementFiles replacements, BatchExecutionAckRepository acks,
                             AppUserRepository users) {
        this.jdbc = jdbc;
        this.replacements = replacements;
        this.acks = acks;
        this.users = users;
    }

    /** Full text of a failure, kept for the explainer; the list view only shows the first meaningful line. */
    public record Detail(Views.JobView view, String fullExitMessage, Map<String, String> stepMessages) {}

    public List<Views.JobView> recentViews(int limit) {
        return recent(limit, null).stream().map(Detail::view).toList();
    }

    public Detail detail(long executionId) {
        List<Detail> d = recent(1, executionId);
        if (d.isEmpty()) throw new IllegalArgumentException("Không có lần chạy #" + executionId);
        return d.get(0);
    }

    @Override
    public List<JobLine> recent(int limit) {
        return recent(limit, null).stream().map(d -> new JobLine(d.view().id(), d.view().job(), d.view().status(),
                d.view().startTime() == null ? "" : HM_DM.format(d.view().startTime().atZone(ZoneId.of("Asia/Ho_Chi_Minh"))),
                d.view().exitMessage(), handled(d.view()))).toList();
    }

    /** For the assistant; the note itself stays out, as it is free text that may name customers. */
    private static String handled(Views.JobView v) {
        if (v.supersededBy() != null) return "đã có file thay thế " + v.supersededBy().fileName();
        if (v.acknowledged() != null) return "đã được " + v.acknowledged().byName() + " đánh dấu đã xử lý";
        if (v.restartedAs() != null) return "đã chạy lại thành #" + v.restartedAs();
        return null;
    }

    private List<Detail> recent(int limit, Long onlyId) {
        MapSqlParameterSource p = new MapSqlParameterSource("limit", limit).addValue("id", onlyId);
        List<Map<String, Object>> execs = jdbc.queryForList("""
                select e.job_execution_id, e.job_instance_id, i.job_name, e.status, e.exit_message, e.start_time, e.end_time,
                       (select min(n.job_execution_id) from batch_job_execution n
                         where n.job_instance_id = e.job_instance_id and n.job_execution_id > e.job_execution_id) as restarted_as
                from batch_job_execution e join batch_job_instance i on i.job_instance_id = e.job_instance_id
                where (cast(:id as bigint) is null or e.job_execution_id = :id)
                order by e.job_execution_id desc
                limit :limit
                """, p);
        if (execs.isEmpty()) return List.of();
        List<Long> ids = execs.stream().map(r -> ((Number) r.get("job_execution_id")).longValue()).toList();
        MapSqlParameterSource byIds = new MapSqlParameterSource("ids", ids);

        Map<Long, Map<String, String>> params = new HashMap<>();
        jdbc.query("select job_execution_id, parameter_name, parameter_value from batch_job_execution_params where job_execution_id in (:ids) order by parameter_name",
                byIds, (ResultSet rs) -> {
                    params.computeIfAbsent(rs.getLong(1), k -> new LinkedHashMap<>()).put(rs.getString(2), rs.getString(3));
                });

        Map<Long, List<Views.StepView>> steps = new HashMap<>();
        Map<Long, Map<String, String>> stepMessages = new HashMap<>();
        jdbc.query("""
                select job_execution_id, step_name, status, read_count, write_count, read_skip_count + process_skip_count + write_skip_count,
                       commit_count, start_time, end_time, exit_message
                from batch_step_execution where job_execution_id in (:ids) order by step_execution_id
                """, byIds, (ResultSet rs) -> {
            long id = rs.getLong(1);
            String full = rs.getString(10);
            steps.computeIfAbsent(id, k -> new ArrayList<>()).add(new Views.StepView(rs.getString(2), rs.getString(3), rs.getLong(4),
                    rs.getLong(5), rs.getLong(6), rs.getLong(7), millis(rs.getTimestamp(8), rs.getTimestamp(9)), shortMessage(full)));
            if (full != null && !full.isBlank()) stepMessages.computeIfAbsent(id, k -> new LinkedHashMap<>()).put(rs.getString(2), full);
        });

        Map<Long, Views.Acknowledgement> acked = acknowledgements(execs.stream()
                .filter(r -> Views.JobView.failedOrStopped((String) r.get("status")))
                .map(r -> ((Number) r.get("job_execution_id")).longValue()).toList());

        List<Detail> out = new ArrayList<>();
        for (Map<String, Object> r : execs) {
            long id = ((Number) r.get("job_execution_id")).longValue();
            String full = (String) r.get("exit_message");
            String status = (String) r.get("status");
            boolean stuck = Views.JobView.failedOrStopped(status);
            Number restartedAs = (Number) r.get("restarted_as");
            Map<String, String> jobParams = params.getOrDefault(id, Map.of());
            Views.JobView v = new Views.JobView(id, (String) r.get("job_name"), jobParams, status,
                    instant(r.get("start_time")), instant(r.get("end_time")), steps.getOrDefault(id, List.of()),
                    shortMessage(full), stuck && restartedAs != null ? restartedAs.longValue() : null,
                    stuck ? superseded(jobParams) : null, acked.get(id));
            out.add(new Detail(v, full, stepMessages.getOrDefault(id, Map.of())));
        }
        return out;
    }

    /** Only file ingestion runs carry a {@code fileId}; the rule for "replaced" lives in {@link ReplacementFiles}. */
    private Views.Superseded superseded(Map<String, String> params) {
        String fileId = params.get("fileId");
        if (fileId == null) return null;
        return replacements.replacementFor(Long.parseLong(fileId))
                .map(f -> new Views.Superseded(f.getId(), f.getFileName(), f.getReceivedAt())).orElse(null);
    }

    private Map<Long, Views.Acknowledgement> acknowledgements(List<Long> executionIds) {
        if (executionIds.isEmpty()) return Map.of();
        List<BatchExecutionAck> found = acks.findAllById(executionIds);
        if (found.isEmpty()) return Map.of();
        Map<String, String> names = new HashMap<>();
        users.findAllById(found.stream().map(BatchExecutionAck::getAcknowledgedBy).distinct().toList())
                .forEach(u -> names.put(u.getId(), u.getFullName()));
        Map<Long, Views.Acknowledgement> out = new HashMap<>();
        for (BatchExecutionAck a : found) {
            out.put(a.getJobExecutionId(), new Views.Acknowledgement(a.getAcknowledgedBy(),
                    names.getOrDefault(a.getAcknowledgedBy(), a.getAcknowledgedBy()), a.getAcknowledgedAt(), a.getNote()));
        }
        return out;
    }

    /**
     * "org.springframework...SkipLimitExceededException: Skip limit of '5' exceeded\n\tat ..." becomes
     * "SkipLimitExceededException: Skip limit of '5' exceeded", plus the first "Caused by" when there is one.
     */
    static String shortMessage(String full) {
        if (full == null || full.isBlank()) return null;
        String[] lines = full.split("\\R");
        String first = simpleName(lines[0].trim());
        for (String l : lines) {
            String t = l.trim();
            if (t.startsWith("Caused by: ")) return first + " ← " + simpleName(t.substring("Caused by: ".length()));
        }
        return first;
    }

    private static String simpleName(String line) {
        int colon = line.indexOf(':');
        String cls = colon < 0 ? line : line.substring(0, colon);
        if (cls.matches("[a-zA-Z0-9_.$]+") && cls.contains(".")) {
            return cls.substring(cls.lastIndexOf('.') + 1) + (colon < 0 ? "" : line.substring(colon));
        }
        return line;
    }

    private Instant instant(Object ts) {
        if (ts == null) return null;
        if (ts instanceof Timestamp t) return t.toLocalDateTime().atZone(batchZone).toInstant();
        if (ts instanceof java.time.LocalDateTime l) return l.atZone(batchZone).toInstant();
        return null;
    }

    private static long millis(Timestamp start, Timestamp end) {
        if (start == null) return 0;
        Timestamp e = end == null ? new Timestamp(System.currentTimeMillis()) : end;
        return Math.max(0, Duration.between(start.toInstant(), e.toInstant()).toMillis());
    }

}
