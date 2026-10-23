package vn.reconhub.core.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.reconhub.core.domain.BatchExecutionAck;
import vn.reconhub.core.domain.Enums.FileStatus;
import vn.reconhub.core.domain.ImportFile;
import vn.reconhub.core.files.ReplacementFiles;
import vn.reconhub.core.repo.BatchExecutionAckRepository;
import vn.reconhub.core.repo.ImportFileRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which failed file counts as replaced, and the one-acknowledgement-per-run rule, against the Flyway schema. */
@SpringBootTest(classes = TestApp.class)
@Testcontainers(disabledWithoutDocker = true)
class BatchAlertRulesIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final AtomicLong DAY = new AtomicLong();
    private static final AtomicLong EXEC = new AtomicLong(900);

    @Autowired ReplacementFiles replacements;
    @Autowired ImportFileRepository files;
    @Autowired BatchExecutionAckRepository acks;
    @Autowired JdbcTemplate jdbc;

    private final Instant failedAt = Instant.parse("2026-10-21T02:00:00Z");

    @Test
    void laterLoadedFileOfTheSameSourceAndDayReplacesTheFailedOne() {
        LocalDate day = day();
        ImportFile failed = file("GHTK", day, failedAt, FileStatus.FAILED, "GHTK_COD.csv");
        file("GHTK", day, failedAt.plus(Duration.ofHours(5)), FileStatus.COMPLETED_WITH_ERRORS, "GHTK_COD_bosung.csv");
        ImportFile v2 = file("GHTK", day, failedAt.plus(Duration.ofHours(3)), FileStatus.COMPLETED, "GHTK_COD_v2.csv");

        assertThat(replacements.replacementFor(failed)).map(ImportFile::getId).contains(v2.getId());
        assertThat(replacements.replacementFor(failed.getId())).map(ImportFile::getFileName).contains("GHTK_COD_v2.csv");
    }

    @Test
    void fileThatDidNotLoadOrBelongsElsewhereIsNoReplacement() {
        LocalDate day = day();
        ImportFile failed = file("GHTK", day, failedAt, FileStatus.FAILED, "GHTK_COD.csv");
        file("GHTK", day, failedAt.minus(Duration.ofHours(1)), FileStatus.COMPLETED, "GHTK_COD_earlier.csv");
        file("GHTK", day, failedAt.plus(Duration.ofHours(1)), FileStatus.FAILED, "GHTK_COD_v2.csv");
        file("GHTK", day, failedAt.plus(Duration.ofHours(2)), FileStatus.PROCESSING, "GHTK_COD_v3.csv");
        file("GHTK", day.plusDays(1), failedAt.plus(Duration.ofHours(3)), FileStatus.COMPLETED, "GHTK_COD_next_day.csv");
        file("GHN", day, failedAt.plus(Duration.ofHours(3)), FileStatus.COMPLETED, "GHN_same_day.csv");

        assertThat(replacements.replacementFor(failed)).isEmpty();
    }

    @Test
    void aRunIsAcknowledgedOnceAndTheFirstNoteStays() {
        long exec = failedExecution();
        acks.saveAndFlush(new BatchExecutionAck(exec, "u1", failedAt, "Đã gọi GHTK, file gửi lại qua email"));

        assertThatThrownBy(() -> acks.saveAndFlush(new BatchExecutionAck(exec, "u3", failedAt.plusSeconds(60), "Ghi đè")))
                .isInstanceOf(DataIntegrityViolationException.class);
        BatchExecutionAck stored = acks.findById(exec).orElseThrow();
        assertThat(stored.getAcknowledgedBy()).isEqualTo("u1");
        assertThat(stored.getNote()).isEqualTo("Đã gọi GHTK, file gửi lại qua email");
    }

    @Test
    void theDatabaseRejectsABlankNote() {
        long exec = failedExecution();
        assertThatThrownBy(() -> acks.saveAndFlush(new BatchExecutionAck(exec, "u1", failedAt, "   ")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Each test gets its own business dates (a week apart), so files of one test never replace those of another. */
    private static LocalDate day() {
        return LocalDate.of(2026, 1, 1).plusDays(7 * DAY.incrementAndGet());
    }

    private ImportFile file(String source, LocalDate day, Instant receivedAt, FileStatus status, String name) {
        return files.save(new ImportFile(source, name, null, receivedAt, day, UUID.randomUUID().toString(), status, "test"));
    }

    private long failedExecution() {
        long id = EXEC.incrementAndGet();
        jdbc.update("insert into batch_job_instance (job_instance_id, version, job_name, job_key) values (?, 0, 'fileIngestionJob', ?)",
                id, "k" + id);
        jdbc.update("insert into batch_job_execution (job_execution_id, version, job_instance_id, create_time, start_time, end_time, status, exit_code) "
                + "values (?, 1, ?, now(), now(), now(), 'FAILED', 'FAILED')", id, id);
        return id;
    }
}
