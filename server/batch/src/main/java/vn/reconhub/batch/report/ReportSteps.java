package vn.reconhub.batch.report;

import jakarta.mail.internet.MimeMessage;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.transaction.PlatformTransactionManager;
import vn.reconhub.core.config.ReconProperties;
import vn.reconhub.core.report.ReportService;
import vn.reconhub.core.report.Rows;
import vn.reconhub.core.util.Money;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * agingAndReportJob: agingSnapshotStep → journalExportStep → emailReportStep. The identifying parameter is {@code asOf};
 * when the mail server is down only the e-mail step fails, and a restart sends the files already written.
 */
@Configuration
public class ReportSteps {
    public static final String JOB = "agingAndReportJob";
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ReportService reports;
    private final ReconProperties props;

    public ReportSteps(ReportService reports, ReconProperties props) {
        this.reports = reports;
        this.props = props;
    }

    @Bean
    Job agingAndReportJob(JobRepository repo, Step agingSnapshotStep, Step journalExportStep, Step emailReportStep) {
        return new JobBuilder(JOB, repo).start(agingSnapshotStep).next(journalExportStep).next(emailReportStep).build();
    }

    @Bean
    Step agingSnapshotStep(JobRepository repo, PlatformTransactionManager tx) {
        return new StepBuilder("agingSnapshotStep", repo).tasklet((contribution, ctx) -> {
            List<Rows.AgingRow> aging = reports.aging();
            List<List<Object>> rows = new ArrayList<>();
            for (Rows.AgingRow r : aging) rows.add(List.of(r.source, r.b0, r.b1, r.b2, r.b3, r.b0 + r.b1 + r.b2 + r.b3));
            CsvFiles.write(agingPath(asOf(ctx)), List.of("Nguồn", "0-7 ngày", "8-30 ngày", "31-60 ngày", "Trên 60 ngày", "Tổng"), rows);
            contribution.incrementReadCount();
            contribution.incrementWriteCount(rows.size());
            return RepeatStatus.FINISHED;
        }, tx).build();
    }

    @Bean
    Step journalExportStep(JobRepository repo, PlatformTransactionManager tx) {
        return new StepBuilder("journalExportStep", repo).tasklet((contribution, ctx) -> {
            LocalDate asOf = asOf(ctx);
            List<List<Object>> rows = new ArrayList<>();
            for (ReportService.JournalEntry e : reports.journal(YearMonth.from(asOf))) {
                rows.add(List.of(e.date().format(DMY), e.voucher(), e.description(), e.debit(), e.credit(), e.amount(), e.source()));
            }
            CsvFiles.write(journalPath(asOf), List.of("Ngày", "Số chứng từ", "Diễn giải", "TK Nợ", "TK Có", "Số tiền", "Nguồn"), rows);
            contribution.incrementWriteCount(rows.size());
            return RepeatStatus.FINISHED;
        }, tx).build();
    }

    @Bean
    Step emailReportStep(JobRepository repo, PlatformTransactionManager tx, JavaMailSender mail) {
        return new StepBuilder("emailReportStep", repo).tasklet((contribution, ctx) -> {
            LocalDate asOf = asOf(ctx);
            Path aging = agingPath(asOf);
            Path journal = journalPath(asOf);
            if (!Files.exists(aging) || !Files.exists(journal)) throw new IllegalStateException("NoSuchFileException: thiếu file báo cáo " + aging);
            long open = reports.aging().stream().mapToLong(r -> r.b0 + r.b1 + r.b2 + r.b3).sum();
            String recipients = (String) ctx.getStepContext().getJobParameters().getOrDefault("recipients", props.getReportRecipients());

            MimeMessage message = mail.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(props.getMailFrom());
            helper.setTo(recipients.split("\\s*,\\s*"));
            helper.setSubject("[ReconHub] Công nợ đối tác ngày " + asOf.format(DMY));
            helper.setText("""
                    Chào phòng Kế toán,

                    Đính kèm báo cáo tuổi nợ phải thu đối tác và bút toán tháng %s.
                    Tổng phải thu đối tác còn mở: %s.

                    Email gửi tự động từ ReconHub (agingAndReportJob).
                    """.formatted(YearMonth.from(asOf).format(DateTimeFormatter.ofPattern("MM/yyyy")), Money.format(open)));
            helper.addAttachment(aging.getFileName().toString(), new FileSystemResource(aging));
            helper.addAttachment(journal.getFileName().toString(), new FileSystemResource(journal));
            mail.send(message);
            contribution.incrementWriteCount(1);
            return RepeatStatus.FINISHED;
        }, tx).build();
    }

    private static LocalDate asOf(ChunkContext ctx) {
        return LocalDate.parse((String) ctx.getStepContext().getJobParameters().get("asOf"));
    }

    private Path agingPath(LocalDate asOf) {
        return props.getData().reports().resolve("aging_" + asOf.format(DateTimeFormatter.BASIC_ISO_DATE) + ".csv");
    }

    private Path journalPath(LocalDate asOf) {
        return props.getData().reports().resolve("journal_" + YearMonth.from(asOf) + "_" + asOf.format(DateTimeFormatter.BASIC_ISO_DATE) + ".csv");
    }
}
