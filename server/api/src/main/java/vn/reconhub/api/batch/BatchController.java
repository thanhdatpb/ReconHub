package vn.reconhub.api.batch;

import org.springframework.http.ResponseEntity;
import vn.reconhub.core.messaging.JmsOutbox;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.reconhub.api.security.CurrentUser;
import vn.reconhub.api.web.Views;
import vn.reconhub.core.ai.BatchExplainer;
import vn.reconhub.core.domain.ImportFile;
import vn.reconhub.core.files.ReplacementFiles;
import vn.reconhub.core.messaging.Messages;
import vn.reconhub.core.repo.FileErrorRepository;
import vn.reconhub.core.repo.ImportFileRepository;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Batch monitor: executions from the Spring Batch tables, restart and run-now commands over JMS, and failure explanations. */
@RestController
@RequestMapping("/api")
public class BatchController {
    private static final Set<String> RUNNABLE = Set.of("matchingJob", "feeValidationJob", "agingAndReportJob", "archiveAndPurgeJob");
    private static final DateTimeFormatter HM_DM = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    private final BatchQueryService batch;
    private final BatchAckService acks;
    private final BatchExplainer explainer;
    private final JmsOutbox outbox;
    private final CurrentUser current;
    private final ImportFileRepository files;
    private final FileErrorRepository fileErrors;
    private final ReplacementFiles replacements;

    public BatchController(BatchQueryService batch, BatchAckService acks, BatchExplainer explainer, JmsOutbox outbox, CurrentUser current,
                           ImportFileRepository files, FileErrorRepository fileErrors, ReplacementFiles replacements) {
        this.batch = batch;
        this.acks = acks;
        this.explainer = explainer;
        this.outbox = outbox;
        this.current = current;
        this.files = files;
        this.fileErrors = fileErrors;
        this.replacements = replacements;
    }

    @GetMapping("/batch/executions")
    public List<Views.JobView> executions(@RequestParam(defaultValue = "300") int limit) {
        return batch.recentViews(Math.min(limit, 1000));
    }

    public record Queued(boolean queued, String message) {}

    /**
     * The batch app relaunches the same job instance; Spring Batch skips completed steps. A file that was already
     * replaced is never restarted: its money is in the replacement, and loading both would count it twice.
     */
    @PostMapping("/batch/executions/{id}/restart")
    public ResponseEntity<Queued> restart(@PathVariable long id) {
        Views.JobView v = batch.detail(id).view();
        if (!Views.JobView.failedOrStopped(v.status())) {
            return ResponseEntity.badRequest().body(new Queued(false, "Lần chạy #" + id + " đang ở trạng thái " + v.status() + ", không cần chạy lại"));
        }
        if (v.restartedAs() != null) {
            return ResponseEntity.badRequest().body(new Queued(false, "Lần chạy #" + id + " đã được chạy lại thành #" + v.restartedAs()));
        }
        if (v.supersededBy() != null) {
            return ResponseEntity.badRequest().body(new Queued(false, "Không chạy lại lần chạy #" + id + ": file thay thế "
                    + v.supersededBy().fileName() + " đã nạp thành công lúc " + time(v.supersededBy().receivedAt())
                    + ". Chạy lại sẽ nạp trùng tiền của ngày này."));
        }
        if (!outbox.send(Messages.BATCH_COMMANDS, Messages.BatchCommand.restart(id, current.get().getFullName()))) {
            return ResponseEntity.status(503).body(new Queued(false, "Hàng đợi lệnh (Artemis) chưa phản hồi; lệnh chạy lại #" + id + " sẽ được gửi khi hàng đợi hoạt động lại"));
        }
        return ResponseEntity.accepted().body(new Queued(true, "Đã gửi lệnh chạy lại #" + id));
    }

    public record AckRequest(String note) {}

    /** "Đánh dấu đã xử lý" with a required note; any signed-in role. The run stays in the monitor with who, when and the note. */
    @PostMapping("/batch/executions/{id}/acknowledge")
    public Views.JobView acknowledge(@PathVariable long id, @RequestBody(required = false) AckRequest req) {
        return acks.acknowledge(id, req == null ? null : req.note(), current.get());
    }

    @PostMapping("/batch/jobs/{job}/run")
    public ResponseEntity<Queued> run(@PathVariable String job) {
        if (!RUNNABLE.contains(job)) return ResponseEntity.badRequest().body(new Queued(false, "Job " + job + " không chạy tay được"));
        if (!outbox.send(Messages.BATCH_COMMANDS, Messages.BatchCommand.run(job, current.get().getFullName()))) {
            return ResponseEntity.status(503).body(new Queued(false, "Hàng đợi lệnh (Artemis) chưa phản hồi"));
        }
        return ResponseEntity.accepted().body(new Queued(true, "Đã gửi lệnh chạy " + job));
    }

    @PostMapping("/ai/batch/{id}/explain")
    @Transactional(readOnly = true)
    public BatchExplainer.Explanation explain(@PathVariable long id) {
        BatchQueryService.Detail d = batch.detail(id);
        Views.JobView v = d.view();
        if (!Views.JobView.failedOrStopped(v.status())) {
            return new BatchExplainer.Explanation("Lần chạy này không lỗi nên không có gì cần giải thích.", List.of(), List.of(), "rules");
        }
        List<String> steps = v.steps().stream()
                .map(s -> s.name() + " " + s.status() + " read=" + s.read() + " write=" + s.write() + " skip=" + s.skip()
                        + (s.exitMessage() == null ? "" : " · " + s.exitMessage()))
                .toList();
        StringBuilder exit = new StringBuilder(d.fullExitMessage() == null ? "" : d.fullExitMessage());
        d.stepMessages().forEach((step, msg) -> exit.append("\n[").append(step).append("] ").append(msg, 0, Math.min(msg.length(), 1200)));
        BatchExplainer.Failure failure = new BatchExplainer.Failure(id, v.job(), v.params().toString(), exit.toString(), steps, details(v));
        return explainer.explain(failure, current.get().getId());
    }

    /**
     * For a failed file: its first bad rows and, if the partner already re-sent it, the replacement. For any run: who
     * marked it as handled. The note is left out: it is free text and goes to the model.
     */
    private List<String> details(Views.JobView v) {
        List<String> out = new ArrayList<>();
        String fileId = v.params().get("fileId");
        ImportFile f = fileId == null ? null : files.findById(Long.parseLong(fileId)).orElse(null);
        if (f != null) {
            if (f.getMessage() != null) out.add("Thông báo của file: " + f.getMessage());
            fileErrors.findTop200ByFileIdOrderByRowNo(f.getId()).stream().limit(3).forEach(e ->
                    out.add("Dòng " + e.getRowNo() + ", cột \"" + e.getColumnName() + "\" = \"" + e.getValue() + "\": " + e.getReason()));
            replacements.replacementFor(f).ifPresent(x -> out.add("File thay thế " + x.getFileName() + " đã nạp thành công lúc "
                    + time(x.getReceivedAt())));
        }
        if (v.acknowledged() != null) {
            out.add("Lần chạy đã được " + v.acknowledged().byName() + " đánh dấu đã xử lý lúc " + time(v.acknowledged().at()));
        }
        return out;
    }

    private static String time(Instant at) {
        return HM_DM.format(at.atZone(ZoneId.of("Asia/Ho_Chi_Minh")));
    }
}
