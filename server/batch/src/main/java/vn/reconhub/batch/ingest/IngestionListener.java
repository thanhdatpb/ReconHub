package vn.reconhub.batch.ingest;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.reconhub.core.repo.FileErrorRepository;
import vn.reconhub.core.repo.ImportFileRepository;

/** Links the file to the job execution that loads it and records, in words, why a file failed. */
@Component
public class IngestionListener implements JobExecutionListener {
    private final ImportFileRepository files;
    private final FileErrorRepository errors;
    private final TransactionTemplate tx;

    public IngestionListener(ImportFileRepository files, FileErrorRepository errors, PlatformTransactionManager tx) {
        this.files = files;
        this.errors = errors;
        this.tx = new TransactionTemplate(tx);
    }

    @Override
    public void beforeJob(JobExecution job) {
        long fileId = job.getJobParameters().getLong("fileId");
        tx.executeWithoutResult(s -> files.findById(fileId).ifPresent(f -> {
            f.setJobExecutionId(job.getId());
            f.reopen();
            files.save(f);
        }));
    }

    @Override
    public void afterJob(JobExecution job) {
        if (job.getStatus() != BatchStatus.FAILED) return;
        long fileId = job.getJobParameters().getLong("fileId");
        String message = describe(job.getAllFailureExceptions().stream().findFirst().orElse(null));
        tx.executeWithoutResult(s -> files.findById(fileId).ifPresent(f -> {
            f.fail(message.length() > 480 ? message.substring(0, 480) : message, f.getTotalRows(), (int) errors.countByFileId(fileId));
            files.save(f);
        }));
    }

    static String describe(Throwable failure) {
        if (failure == null) return "Job thất bại";
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SkipLimitExceededException sle) {
                String text = "Vượt ngưỡng bỏ qua: quá " + sle.getSkipLimit() + " dòng lỗi nên dừng nạp cả file.";
                if (sle.getCause() instanceof RowException first) {
                    text += " Ví dụ dòng " + first.rowNo() + ", cột \"" + first.column() + "\" = \"" + first.value() + "\": " + first.getMessage();
                }
                return text;
            }
        }
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
