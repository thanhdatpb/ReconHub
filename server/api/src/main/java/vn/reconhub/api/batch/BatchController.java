package vn.reconhub.api.batch;

import org.springframework.http.ResponseEntity;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.reconhub.api.security.CurrentUser;
import vn.reconhub.api.web.Views;
import vn.reconhub.core.ai.BatchExplainer;
import vn.reconhub.core.domain.Enums.FileStatus;
import vn.reconhub.core.domain.ImportFile;
import vn.reconhub.core.messaging.Messages;
import vn.reconhub.core.repo.FileErrorRepository;
import vn.reconhub.core.repo.ImportFileRepository;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Batch monitor: executions from the Spring Batch tables, restart and run-now commands over JMS, and failure explanations. */
@RestController
@RequestMapping("/api")
public class BatchController {
    private static final Set<String> RUNNABLE = Set.of("matchingJob", "feeValidationJob", "agingAndReportJob", "archiveAndPurgeJob");
    private static final DateTimeFormatter HM_DM = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    private final BatchQueryService batch;
    private final BatchExplainer explainer;
    private final JmsTemplate jms;
    private final CurrentUser current;
    private final ImportFileRepository files;
    private final FileErrorRepository fileErrors;

    public BatchController(BatchQueryService batch, BatchExplainer explainer, JmsTemplate jms, CurrentUser current,
                           ImportFileRepository files, FileErrorRepository fileErrors) {
        this.batch = batch;
        this.explainer = explainer;
        this.jms = jms;
        this.current = current;
        this.files = files;
        this.fileErrors = fileErrors;
    }

    @GetMapping("/batch/executions")
    public List<Views.JobView> executions(@RequestParam(defaultValue = "300") int limit) {
        return batch.recentViews(Math.min(limit, 1000));
    }

    public record Queued(boolean queued, String message) {}

    /** The batch app relaunches the same job instance; Spring Batch skips completed steps. */
    @PostMapping("/batch/executions/{id}/restart")
    public ResponseEntity<Queued> restart(@PathVariable long id) {
        Views.JobView v = batch.detail(id).view();
        if (!"FAILED".equals(v.status()) && !"STOPPED".equals(v.status())) {
            return ResponseEntity.badRequest().body(new Queued(false, "Lần chạy #" + id + " đang ở trạng thái " + v.status() + ", không cần chạy lại"));
        }
        if (v.restartedAs() != null) {
            return ResponseEntity.badRequest().body(new Queued(false, "Lần chạy #" + id + " đã được chạy lại thành #" + v.restartedAs()));
        }
        jms.convertAndSend(Messages.BATCH_COMMANDS, Messages.BatchCommand.restart(id, current.get().getFullName()));
        return ResponseEntity.accepted().body(new Queued(true, "Đã gửi lệnh chạy lại #" + id));
    }

    @PostMapping("/batch/jobs/{job}/run")
    public ResponseEntity<Queued> run(@PathVariable String job) {
        if (!RUNNABLE.contains(job)) return ResponseEntity.badRequest().body(new Queued(false, "Job " + job + " không chạy tay được"));
        jms.convertAndSend(Messages.BATCH_COMMANDS, Messages.BatchCommand.run(job, current.get().getFullName()));
        return ResponseEntity.accepted().body(new Queued(true, "Đã gửi lệnh chạy " + job));
    }

    @PostMapping("/ai/batch/{id}/explain")
    @Transactional(readOnly = true)
    public BatchExplainer.Explanation explain(@PathVariable long id) {
        BatchQueryService.Detail d = batch.detail(id);
        Views.JobView v = d.view();
        if (!"FAILED".equals(v.status()) && !"STOPPED".equals(v.status())) {
            return new BatchExplainer.Explanation("Lần chạy này không lỗi nên không có gì cần giải thích.", List.of(), List.of(), "rules");
        }
        List<String> steps = v.steps().stream()
                .map(s -> s.name() + " " + s.status() + " read=" + s.read() + " write=" + s.write() + " skip=" + s.skip()
                        + (s.exitMessage() == null ? "" : " · " + s.exitMessage()))
                .toList();
        StringBuilder exit = new StringBuilder(d.fullExitMessage() == null ? "" : d.fullExitMessage());
        d.stepMessages().forEach((step, msg) -> exit.append("\n[").append(step).append("] ").append(msg, 0, Math.min(msg.length(), 1200)));
        BatchExplainer.Failure failure = new BatchExplainer.Failure(id, v.job(), v.params().toString(), exit.toString(), steps, details(v.params()));
        return explainer.explain(failure, current.get().getId());
    }

    /** For a failed file: its first bad rows and, if the partner already re-sent it, the replacement. */
    private List<String> details(Map<String, String> params) {
        List<String> out = new ArrayList<>();
        String fileId = params.get("fileId");
        if (fileId == null) return out;
        ImportFile f = files.findById(Long.parseLong(fileId)).orElse(null);
        if (f == null) return out;
        if (f.getMessage() != null) out.add("Thông báo của file: " + f.getMessage());
        fileErrors.findTop200ByFileIdOrderByRowNo(f.getId()).stream().limit(3).forEach(e ->
                out.add("Dòng " + e.getRowNo() + ", cột \"" + e.getColumnName() + "\" = \"" + e.getValue() + "\": " + e.getReason()));
        files.findTop500ByOrderByReceivedAtDesc().stream()
                .filter(x -> x.getSourceCode().equals(f.getSourceCode()) && x.getBusinessDate().equals(f.getBusinessDate()))
                .filter(x -> x.getReceivedAt().isAfter(f.getReceivedAt()))
                .filter(x -> x.getStatus() == FileStatus.COMPLETED || x.getStatus() == FileStatus.COMPLETED_WITH_ERRORS)
                .min(Comparator.comparing(ImportFile::getReceivedAt))
                .ifPresent(x -> out.add("File thay thế " + x.getFileName() + " đã nạp thành công lúc "
                        + HM_DM.format(x.getReceivedAt().atZone(ZoneId.of("Asia/Ho_Chi_Minh")))));
        return out;
    }
}
