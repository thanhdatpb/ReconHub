package vn.reconhub.batch.housekeeping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import vn.reconhub.core.config.ReconProperties;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * archiveAndPurgeJob: archiveFilesStep gzips partner files older than {@code olderThanDays} (the database keeps the lines),
 * purgeStep removes rejected copies and old LLM usage rows.
 */
@Configuration
public class HousekeepingSteps {
    public static final String JOB = "archiveAndPurgeJob";
    private static final Logger log = LoggerFactory.getLogger(HousekeepingSteps.class);

    private final JdbcTemplate jdbc;
    private final ReconProperties props;

    public HousekeepingSteps(JdbcTemplate jdbc, ReconProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Bean
    Job archiveAndPurgeJob(JobRepository repo, Step archiveFilesStep, Step purgeStep) {
        return new JobBuilder(JOB, repo).start(archiveFilesStep).next(purgeStep).build();
    }

    @Bean
    Step archiveFilesStep(JobRepository repo, PlatformTransactionManager tx) {
        return new StepBuilder("archiveFilesStep", repo).tasklet((contribution, ctx) -> {
            List<Map<String, Object>> old = jdbc.queryForList("""
                    select id, stored_path from import_files
                    where status in ('COMPLETED', 'COMPLETED_WITH_ERRORS') and received_at < ? and stored_path not like '%.gz'
                    """, Timestamp.from(cutoff(ctx)));
            for (Map<String, Object> f : old) {
                contribution.incrementReadCount();
                Path path = Path.of((String) f.get("stored_path"));
                if (!Files.exists(path)) continue;
                Path gz = path.resolveSibling(path.getFileName() + ".gz");
                try (InputStream in = Files.newInputStream(path); OutputStream out = new GZIPOutputStream(Files.newOutputStream(gz))) {
                    in.transferTo(out);
                }
                Files.delete(path);
                jdbc.update("update import_files set stored_path = ? where id = ?", gz.toString(), f.get("id"));
                contribution.incrementWriteCount(1);
            }
            return RepeatStatus.FINISHED;
        }, tx).build();
    }

    @Bean
    Step purgeStep(JobRepository repo, PlatformTransactionManager tx) {
        return new StepBuilder("purgeStep", repo).tasklet((contribution, ctx) -> {
            Instant cutoff = cutoff(ctx);
            int removed = 0;
            for (Map<String, Object> f : jdbc.queryForList("select stored_path from import_files where status = 'DUPLICATE' and received_at < ?", Timestamp.from(cutoff))) {
                try {
                    if (Files.deleteIfExists(Path.of((String) f.get("stored_path")))) removed++;
                } catch (IOException e) {
                    log.warn("Could not delete {}: {}", f.get("stored_path"), e.getMessage());
                }
            }
            removed += jdbc.update("delete from ai_requests where at < ?", Timestamp.from(cutoff));
            contribution.incrementWriteCount(removed);
            return RepeatStatus.FINISHED;
        }, tx).build();
    }

    private Instant cutoff(ChunkContext ctx) {
        Map<String, Object> p = ctx.getStepContext().getJobParameters();
        int days = Integer.parseInt((String) p.getOrDefault("olderThanDays", "90"));
        return LocalDate.parse((String) p.get("runDate")).minusDays(days).atStartOfDay(props.getZone()).toInstant();
    }
}
