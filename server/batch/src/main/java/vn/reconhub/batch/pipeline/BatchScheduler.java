package vn.reconhub.batch.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

/** The schedule the batch screen shows. Cron times are Vietnam time whatever the container's zone is. */
@Component
public class BatchScheduler {
    private static final Logger log = LoggerFactory.getLogger(BatchScheduler.class);
    private static final String ZONE = "Asia/Ho_Chi_Minh";

    private final ReconPipeline pipeline;
    private final Clock clock;

    public BatchScheduler(ReconPipeline pipeline, Clock clock) {
        this.pipeline = pipeline;
        this.clock = clock;
    }

    /** SFTP drops files into /data/inbox; poll it rather than wait for a fixed time. */
    @Scheduled(initialDelayString = "${reconhub.inbox-poll-ms:20000}", fixedDelayString = "${reconhub.inbox-poll-ms:20000}")
    public void pollInbox() {
        if (!pipeline.isReady()) return;
        pipeline.tryExclusive(() -> {
            int n = pipeline.scanInbox("SFTP");
            if (n > 0) log.info("Loaded {} file(s) from the inbox", n);
            pipeline.ingestOrphans();
        });
    }

    @Scheduled(cron = "0 0 1 * * *", zone = ZONE)
    public void nightlyMatching() {
        if (!pipeline.isReady()) return;
        pipeline.exclusive(() -> pipeline.matching(Instant.now(clock), LocalDate.now(clock), "lịch 01:00"));
    }

    @Scheduled(cron = "0 20 1 * * *", zone = ZONE)
    public void nightlyFeeValidation() {
        if (!pipeline.isReady()) return;
        pipeline.exclusive(() -> pipeline.feeValidation(Instant.now(clock), LocalDate.now(clock), "lịch 01:20"));
    }

    @Scheduled(cron = "0 0 6 * * *", zone = ZONE)
    public void morningReport() {
        if (!pipeline.isReady()) return;
        pipeline.exclusive(() -> pipeline.report(LocalDate.now(clock)));
    }

    @Scheduled(cron = "0 0 2 * * SUN", zone = ZONE)
    public void weeklyHousekeeping() {
        if (!pipeline.isReady()) return;
        pipeline.exclusive(() -> pipeline.housekeeping(LocalDate.now(clock)));
    }
}
