package vn.reconhub.batch.ingest;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import vn.reconhub.core.config.ReconProperties;
import vn.reconhub.core.domain.ImportFile;
import vn.reconhub.core.domain.MappingRow;
import vn.reconhub.core.domain.PaymentLine;
import vn.reconhub.core.domain.Source;
import vn.reconhub.core.repo.FileErrorRepository;
import vn.reconhub.core.repo.ImportFileRepository;
import vn.reconhub.core.repo.PaymentLineRepository;
import vn.reconhub.core.repo.SourceRepository;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.List;

/**
 * fileIngestionJob: validateFileStep → parseAndStageStep (chunk 500, bad rows skipped up to 10%) → finalizeFileStep.
 * The identifying parameter is {@code fileId}, so running a failed file again restarts it instead of loading it twice.
 */
@Configuration
public class IngestionSteps {
    public static final String JOB = "fileIngestionJob";

    private final ImportFileRepository files;
    private final SourceRepository sources;
    private final FileErrorRepository errors;
    private final PaymentLineRepository lines;
    private final ReconProperties props;

    public IngestionSteps(ImportFileRepository files, SourceRepository sources, FileErrorRepository errors,
                          PaymentLineRepository lines, ReconProperties props) {
        this.files = files;
        this.sources = sources;
        this.errors = errors;
        this.lines = lines;
        this.props = props;
    }

    @Bean
    Job fileIngestionJob(JobRepository repo, Step validateFileStep, Step parseAndStageStep, Step finalizeFileStep, IngestionListener listener) {
        return new JobBuilder(JOB, repo)
                .listener(listener)
                .start(validateFileStep)
                .next(parseAndStageStep)
                .next(finalizeFileStep)
                .build();
    }

    /** Fails fast when the file is missing or no longer has the columns the mapping needs. */
    @Bean
    Step validateFileStep(JobRepository repo, PlatformTransactionManager tx) {
        return new StepBuilder("validateFileStep", repo).tasklet((contribution, ctx) -> {
            ImportFile file = file((Long) ctx.getStepContext().getJobParameters().get("fileId"));
            Source source = sources.findById(file.getSourceCode()).orElseThrow();
            Path path = Path.of(file.getStoredPath());
            if (!Files.exists(path)) throw new IllegalStateException("NoSuchFileException: không tìm thấy " + path);
            for (String field : MappingRow.REQUIRED) {
                if (source.columnFor(field).isEmpty()) throw new IllegalStateException("Nguồn " + source.getCode() + " chưa ánh xạ trường bắt buộc " + field);
            }
            SourceFileReader probe = new SourceFileReader(path, source.getEncoding(), source.getDelimiter());
            probe.open(new ExecutionContext());
            List<String> headers = probe.headers();
            probe.close();
            for (MappingRow m : source.getMapping()) {
                if (m.column() != null && !headers.contains(m.column())) {
                    throw new IllegalStateException("File thiếu cột \"" + m.column() + "\" đã ánh xạ cho trường " + m.field()
                            + ". Cột trong file: " + String.join(", ", headers));
                }
            }
            contribution.incrementReadCount();
            contribution.incrementWriteCount(1);
            return RepeatStatus.FINISHED;
        }, tx).build();
    }

    @Bean
    Step parseAndStageStep(JobRepository repo, PlatformTransactionManager tx, SourceFileReader sourceFileReader,
                           PaymentRowProcessor paymentRowProcessor, JdbcBatchItemWriter<PaymentLine> paymentLineWriter,
                           RatioSkipPolicy ratioSkipPolicy, FileErrorSkipListener fileErrorSkipListener,
                           SeenErrorsRecorder seenErrorsRecorder) {
        return new StepBuilder("parseAndStageStep", repo)
                .<RawRow, PaymentLine>chunk(500, tx)
                .reader(sourceFileReader)
                .processor(paymentRowProcessor)
                .writer(paymentLineWriter)
                .faultTolerant()
                .skipPolicy(ratioSkipPolicy)
                .listener(fileErrorSkipListener)
                .listener(seenErrorsRecorder)
                .build();
    }

    /** Totals come from the tables, not the step counters, so they stay right after a restart. */
    @Bean
    Step finalizeFileStep(JobRepository repo, PlatformTransactionManager tx, JdbcTemplate jdbc) {
        return new StepBuilder("finalizeFileStep", repo).tasklet((contribution, ctx) -> {
            ImportFile file = file((Long) ctx.getStepContext().getJobParameters().get("fileId"));
            Source source = sources.findById(file.getSourceCode()).orElseThrow();
            int total = SourceFileReader.countRows(Path.of(file.getStoredPath()), source.getEncoding());
            long loaded = lines.countByFileId(file.getId());
            int errorCount = (int) errors.countByFileId(file.getId());
            Long amount = jdbc.queryForObject("select coalesce(sum(amount), 0) from payment_lines where file_id = ?", Long.class, file.getId());
            file.finish(total, errorCount, amount == null ? 0 : amount);
            long filtered = total - loaded - errorCount;
            if (filtered > 0) file.setMessage(filtered + " dòng ghi nợ (tiền ra) không thuộc phạm vi đối soát");
            files.save(file);
            contribution.incrementReadCount();
            contribution.incrementWriteCount(1);
            return RepeatStatus.FINISHED;
        }, tx).build();
    }

    // ---------------------------------------------------------------- step-scoped parts

    @Bean
    @StepScope
    SourceFileReader sourceFileReader(@Value("#{jobParameters['fileId']}") Long fileId) {
        ImportFile file = file(fileId);
        Source source = sources.findById(file.getSourceCode()).orElseThrow();
        return new SourceFileReader(Path.of(file.getStoredPath()), source.getEncoding(), source.getDelimiter());
    }

    @Bean
    @StepScope
    PaymentRowProcessor paymentRowProcessor(@Value("#{jobParameters['fileId']}") Long fileId) {
        ImportFile file = file(fileId);
        return new PaymentRowProcessor(sources.findById(file.getSourceCode()).orElseThrow(), fileId, props.getZone());
    }

    @Bean
    @StepScope
    RatioSkipPolicy ratioSkipPolicy(@Value("#{jobParameters['fileId']}") Long fileId) {
        ImportFile file = file(fileId);
        Source source = sources.findById(file.getSourceCode()).orElseThrow();
        return new RatioSkipPolicy(SourceFileReader.countRows(Path.of(file.getStoredPath()), source.getEncoding()));
    }

    @Bean
    @StepScope
    FileErrorSkipListener fileErrorSkipListener(@Value("#{jobParameters['fileId']}") Long fileId) {
        return new FileErrorSkipListener(errors, fileId);
    }

    @Bean
    @StepScope
    SeenErrorsRecorder seenErrorsRecorder(@Value("#{jobParameters['fileId']}") Long fileId, RatioSkipPolicy ratioSkipPolicy,
                                          JdbcTemplate jdbc, PlatformTransactionManager tx) {
        return new SeenErrorsRecorder(fileId, ratioSkipPolicy, jdbc, tx);
    }

    @Bean
    JdbcBatchItemWriter<PaymentLine> paymentLineWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<PaymentLine>()
                .dataSource(dataSource)
                .sql("""
                        insert into payment_lines (source_code, file_id, row_no, external_ref, order_ref, tracking_code, description,
                                                   amount, fee, txn_at, status, fee_checked, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NEW', false, now())
                        """)
                .itemPreparedStatementSetter((l, ps) -> {
                    ps.setString(1, l.getSourceCode());
                    ps.setLong(2, l.getFileId());
                    ps.setInt(3, l.getRowNo());
                    ps.setString(4, l.getExternalRef());
                    ps.setString(5, l.getOrderRef());
                    ps.setString(6, l.getTrackingCode());
                    ps.setString(7, l.getDescription());
                    ps.setLong(8, l.getAmount());
                    ps.setLong(9, l.getFee());
                    ps.setTimestamp(10, Timestamp.from(l.getTxnAt()));
                })
                .assertUpdates(true)
                .build();
    }

    private ImportFile file(Long fileId) {
        return files.findById(fileId).orElseThrow(() -> new IllegalStateException("Không có file #" + fileId));
    }
}
