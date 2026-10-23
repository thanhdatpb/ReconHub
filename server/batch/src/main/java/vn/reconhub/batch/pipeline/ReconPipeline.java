package vn.reconhub.batch.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.stereotype.Service;
import vn.reconhub.batch.housekeeping.HousekeepingSteps;
import vn.reconhub.batch.ingest.IngestionSteps;
import vn.reconhub.batch.matching.MatchingSteps;
import vn.reconhub.batch.report.ReportSteps;
import vn.reconhub.batch.seed.SeedSteps;
import vn.reconhub.core.config.ReconProperties;
import vn.reconhub.core.domain.Enums.SourceStatus;
import vn.reconhub.core.domain.ImportFile;
import vn.reconhub.core.domain.Source;
import vn.reconhub.core.files.FileRegistrar;
import vn.reconhub.core.repo.ImportFileRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The order jobs run in: register file → fileIngestionJob → matchingJob → feeValidationJob. Every entry point
 * (inbox poller, schedule, JMS command, demo backfill) goes through one lock so jobs never overlap.
 */
@Service
public class ReconPipeline {
    private static final Logger log = LoggerFactory.getLogger(ReconPipeline.class);

    private final JobLauncher launcher;
    private final JobExplorer explorer;
    private final JobRepository repository;
    private final Map<String, Job> jobs;
    private final FileRegistrar registrar;
    private final ImportFileRepository files;
    private final ReconProperties props;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean ready;

    public ReconPipeline(JobLauncher launcher, JobExplorer explorer, JobRepository repository, List<Job> jobs, FileRegistrar registrar,
                         ImportFileRepository files, ReconProperties props, Clock clock) {
        this.launcher = launcher;
        this.explorer = explorer;
        this.repository = repository;
        this.jobs = jobs.stream().collect(Collectors.toMap(Job::getName, j -> j));
        this.registrar = registrar;
        this.files = files;
        this.props = props;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- locking

    public <T> T exclusive(Supplier<T> work) {
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    /** Runs the work only when nothing else is running; used by the poller so it never queues up behind a long job. */
    public boolean tryExclusive(Runnable work) {
        if (!lock.tryLock()) return false;
        try {
            work.run();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean isReady() {
        return ready;
    }

    public void markReady() {
        ready = true;
    }

    // ---------------------------------------------------------------- files

    /** Loads every file that has finished arriving in /data/inbox, then matches. Returns how many files were loaded. */
    public int scanInbox(String by) {
        Instant now = Instant.now(clock);
        int loaded = 0;
        for (Path p : inboxFiles(Duration.ofSeconds(5))) {
            if (intake(p, now, by)) loaded++;
        }
        if (loaded > 0) matchAndCheck(Instant.now(clock), LocalDate.now(clock), loaded + " file mới từ /inbox");
        return loaded;
    }

    /** Files in the inbox not modified for {@code settled}, so a file still being copied is left for the next scan. */
    public List<Path> inboxFiles(Duration settled) {
        Path inbox = props.getData().inbox();
        if (!Files.isDirectory(inbox)) return List.of();
        Instant before = Instant.now().minus(settled);
        try (Stream<Path> s = Files.list(inbox)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !p.getFileName().toString().matches(".*\\.(part|tmp|filepart)$"))
                    .filter(p -> modified(p).isBefore(before))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Registers one file and loads it. Returns true when lines were loaded (false for duplicates and failures). */
    public boolean intake(Path path, Instant receivedAt, String by) {
        String name = path.getFileName().toString();
        Optional<Source> source = registrar.detectSource(name);
        if (source.isEmpty() || source.get().getStatus() != SourceStatus.ACTIVE) {
            log.warn("{}: {}; moved to rejected", name, source.isEmpty() ? "no source matches this file name" : "source " + source.get().getCode() + " is not active");
            moveAside(path);
            return false;
        }
        FileRegistrar.Registered reg = registrar.register(path, source.get(), name, receivedAt, by);
        if (reg.duplicate()) {
            log.info("{}: {}", name, reg.file().getMessage());
            return false;
        }
        return ingest(reg.file()).getStatus() == BatchStatus.COMPLETED;
    }

    public JobExecution ingest(ImportFile file) {
        return launch(IngestionSteps.JOB, new JobParametersBuilder()
                .addLong("fileId", file.getId())
                .addString("source", file.getSourceCode(), false)
                .addString("file", file.getFileName(), false)
                .toJobParameters());
    }

    /** A file the API already registered (upload): load it, then match. */
    public void ingestRegistered(long fileId) {
        ImportFile file = files.findById(fileId).orElseThrow(() -> new IllegalArgumentException("No file #" + fileId));
        if (file.getJobExecutionId() != null) {
            log.info("File #{} already has execution #{}, ignoring duplicate command", fileId, file.getJobExecutionId());
            return;
        }
        if (ingest(file).getStatus() == BatchStatus.COMPLETED) {
            matchAndCheck(Instant.now(clock), LocalDate.now(clock), "file tải lên " + file.getFileName());
        }
    }

    /** Uploads whose INGEST command never arrived (broker down, API restarted): load them anyway. */
    public int ingestOrphans() {
        List<ImportFile> orphans = files.findByStatusAndJobExecutionIdIsNullAndReceivedAtBefore(
                vn.reconhub.core.domain.Enums.FileStatus.PROCESSING, Instant.now(clock).minusSeconds(60));
        for (ImportFile f : orphans) {
            log.info("File #{} {} was registered but never loaded; loading it now", f.getId(), f.getFileName());
            ingestRegistered(f.getId());
        }
        return orphans.size();
    }

    // ---------------------------------------------------------------- jobs

    public void matchAndCheck(Instant at, LocalDate runDate, String trigger) {
        matching(at, runDate, trigger);
        feeValidation(at, runDate, trigger);
    }

    public JobExecution matching(Instant at, LocalDate runDate, String trigger) {
        return launch(MatchingSteps.MATCHING_JOB, runParams(at, runDate, trigger));
    }

    public JobExecution feeValidation(Instant at, LocalDate runDate, String trigger) {
        return launch(MatchingSteps.FEE_JOB, runParams(at, runDate, trigger));
    }

    public JobExecution report(LocalDate asOf) {
        return launch(ReportSteps.JOB, new JobParametersBuilder()
                .addString("asOf", asOf.toString())
                .addString("recipients", props.getReportRecipients(), false)
                .toJobParameters());
    }

    public JobExecution housekeeping(LocalDate runDate) {
        return launch(HousekeepingSteps.JOB, new JobParametersBuilder()
                .addString("runDate", runDate.toString())
                .addString("olderThanDays", "90", false)
                .toJobParameters());
    }

    public JobExecution seedOrders(Path csv) {
        return launch(SeedSteps.JOB, new JobParametersBuilder()
                .addString("file", csv.toString())
                .addString("checksum", FileRegistrar.sha256(csv))
                .toJobParameters());
    }

    /** Runs a job by name with today's defaults; used by the "run now" command. */
    public JobExecution runNow(String job, String by) {
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.now(clock);
        return switch (job) {
            case MatchingSteps.MATCHING_JOB -> matching(now, today, "chạy tay bởi " + by);
            case MatchingSteps.FEE_JOB -> feeValidation(now, today, "chạy tay bởi " + by);
            case ReportSteps.JOB -> report(today);
            case HousekeepingSteps.JOB -> housekeeping(today);
            default -> throw new IllegalArgumentException("Job " + job + " không chạy tay được");
        };
    }

    /**
     * Restarting means launching the same job instance again: Spring Batch skips the steps that completed and
     * resumes the failed one from its last commit.
     */
    public JobExecution restart(long executionId) {
        JobExecution old = explorer.getJobExecution(executionId);
        if (old == null) throw new IllegalArgumentException("No execution #" + executionId);
        if (old.getStatus() != BatchStatus.FAILED && old.getStatus() != BatchStatus.STOPPED) {
            log.info("Execution #{} is {}, nothing to restart", executionId, old.getStatus());
            return old;
        }
        String name = old.getJobInstance().getJobName();
        JobExecution next = launch(name, old.getJobParameters());
        log.info("Restarted #{} ({}) as #{}: {}", executionId, name, next.getId(), next.getStatus());
        if (IngestionSteps.JOB.equals(name) && next.getStatus() == BatchStatus.COMPLETED) {
            matchAndCheck(Instant.now(clock), LocalDate.now(clock), "sau khi chạy lại #" + executionId);
        }
        return next;
    }

    /** Executions left STARTED by a crash or a killed container can never be restarted; mark them failed on startup. */
    public void failAbandoned() {
        for (String name : jobs.keySet()) {
            for (JobExecution e : explorer.findRunningJobExecutions(name)) {
                LocalDateTime now = LocalDateTime.now();
                for (StepExecution s : e.getStepExecutions()) {
                    if (s.getStatus().isRunning()) {
                        s.setStatus(BatchStatus.FAILED);
                        s.setExitStatus(ExitStatus.FAILED.addExitDescription("Batch dừng giữa chừng (khởi động lại tiến trình)"));
                        s.setEndTime(now);
                        repository.update(s);
                    }
                }
                e.setStatus(BatchStatus.FAILED);
                e.setExitStatus(ExitStatus.FAILED.addExitDescription("Batch dừng giữa chừng (khởi động lại tiến trình)"));
                e.setEndTime(now);
                repository.update(e);
                log.warn("Marked abandoned execution #{} ({}) as FAILED", e.getId(), name);
            }
        }
    }

    private JobParameters runParams(Instant at, LocalDate runDate, String trigger) {
        return new JobParametersBuilder()
                .addString("runDate", runDate.toString())
                .addString("runAt", at.toString())
                .addString("trigger", trigger, false)
                .toJobParameters();
    }

    private JobExecution launch(String name, JobParameters params) {
        Job job = jobs.get(name);
        if (job == null) throw new IllegalArgumentException("Unknown job " + name);
        try {
            JobExecution e = launcher.run(job, params);
            if (e.getStatus() != BatchStatus.COMPLETED) log.warn("{} #{} finished {}: {}", name, e.getId(), e.getStatus(), e.getExitStatus().getExitDescription().lines().findFirst().orElse(""));
            return e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not launch " + name + ": " + e.getMessage(), e);
        }
    }

    private void moveAside(Path path) {
        try {
            Path target = props.getData().rejected().resolve("unknown_" + path.getFileName());
            Files.createDirectories(target.getParent());
            Files.move(path, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Could not move {}: {}", path, e.getMessage());
        }
    }

    private static Instant modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant();
        } catch (IOException e) {
            return Instant.now();
        }
    }
}
