package vn.reconhub.batch.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import vn.reconhub.batch.pipeline.ReconPipeline;
import vn.reconhub.core.config.ReconProperties;
import vn.reconhub.core.domain.Source;
import vn.reconhub.core.files.FileRegistrar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * First start of the demo stack: loads the generated orders, then replays the generated partner files day by day
 * as if they had arrived at their usual time, so the screens show a month of history instead of one big import.
 * Runs once; {@code /data/.backfilled} marks it done. After that the inbox poller takes over.
 */
@Component
public class DemoBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoBootstrap.class);
    /** Files sent again later the same day, after the partner was told about a problem. */
    private static final List<String> RESENT_MARKERS = List.of("_v2", "_bosung", "_gui_lai");

    private final ReconPipeline pipeline;
    private final FileRegistrar registrar;
    private final DemoHistorySimulator history;
    private final ReconProperties props;
    private final Clock clock;

    public DemoBootstrap(ReconPipeline pipeline, FileRegistrar registrar, DemoHistorySimulator history, ReconProperties props, Clock clock) {
        this.pipeline = pipeline;
        this.registrar = registrar;
        this.history = history;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        pipeline.exclusive(() -> {
            pipeline.failAbandoned();
            try {
                if (props.getDemo().isEnabled()) backfillOnce();
            } catch (RuntimeException e) {
                log.error("Demo backfill failed; the inbox poller will load the remaining files as they are", e);
            }
            return null;
        });
        pipeline.markReady();
    }

    private void backfillOnce() {
        Path root = props.getData().getRoot();
        Path done = root.resolve(".backfilled");
        Path orders = props.getData().seed().resolve("orders.csv");
        if (Files.exists(done)) return;
        if (!Files.exists(root.resolve(".seeded")) || !Files.exists(orders)) {
            log.info("No generated sample data in {}; skipping the demo backfill", root);
            return;
        }
        long started = System.currentTimeMillis();
        log.info("Demo backfill: loading orders from {}", orders);
        pipeline.seedOrders(orders);

        Instant now = Instant.now(clock);
        Map<LocalDate, List<Arrival>> byDay = new TreeMap<>();
        for (Path p : pipeline.inboxFiles(Duration.ZERO)) {
            Optional<Source> source = registrar.detectSource(p.getFileName().toString());
            if (source.isEmpty()) continue;
            LocalDate day = registrar.businessDate(source.get(), p.getFileName().toString());
            byDay.computeIfAbsent(day, d -> new ArrayList<>()).add(new Arrival(p, receivedAt(source.get(), p, day, now)));
        }
        int n = 0;
        for (Map.Entry<LocalDate, List<Arrival>> day : byDay.entrySet()) {
            List<Arrival> arrivals = day.getValue().stream().sorted(Comparator.comparing(Arrival::at)).toList();
            for (Arrival a : arrivals) {
                pipeline.intake(a.path(), a.at(), "SFTP");
                n++;
            }
            Instant last = arrivals.get(arrivals.size() - 1).at();
            Instant runAt = last.plus(Duration.ofMinutes(6));
            pipeline.matchAndCheck(runAt.isAfter(now) ? now : runAt, day.getKey(), "sau khi nạp file ngày " + day.getKey());
        }
        log.info("Demo backfill: {} files over {} days in {} s", n, byDay.size(), (System.currentTimeMillis() - started) / 1000);

        history.simulate();
        pipeline.report(LocalDate.now(clock));
        try {
            Files.writeString(done, "backfilled at " + Instant.now() + "\n");
        } catch (IOException e) {
            log.warn("Could not write {}: {}", done, e.getMessage());
        }
    }

    /** The partner's usual hour, a few minutes of jitter, five hours later for re-sent files, never in the future. */
    private Instant receivedAt(Source source, Path file, LocalDate day, Instant now) {
        String name = file.getFileName().toString();
        int hour = source.getExpectedHour() != null ? source.getExpectedHour() : 11;
        int minute = Math.floorMod(name.hashCode(), 25);
        if (RESENT_MARKERS.stream().anyMatch(name::contains)) hour += 5;
        Instant at = day.atTime(LocalTime.of(Math.min(hour, 23), minute)).atZone(props.getZone()).toInstant();
        return at.isAfter(now) ? now.minusSeconds(60) : at;
    }

    private record Arrival(Path path, Instant at) {}
}
