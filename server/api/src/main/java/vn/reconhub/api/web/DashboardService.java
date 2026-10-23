package vn.reconhub.api.web;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.reconhub.api.batch.BatchQueryService;
import vn.reconhub.core.domain.Enums.SourceStatus;
import vn.reconhub.core.domain.Source;
import vn.reconhub.core.report.ExceptionQuery;
import vn.reconhub.core.report.ReportService;
import vn.reconhub.core.report.Rows;
import vn.reconhub.core.repo.SourceRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The overview screen in one call. */
@Service
@Transactional(readOnly = true)
public class DashboardService {
    static final List<String> SOURCE_ORDER = List.of("GHN", "GHTK", "SHOPEE", "TIKTOK", "VCB", "MOMO", "VTP");

    private final ReportService reports;
    private final SourceRepository sources;
    private final BatchQueryService batch;

    public DashboardService(ReportService reports, SourceRepository sources, BatchQueryService batch) {
        this.reports = reports;
        this.sources = sources;
        this.batch = batch;
    }

    public record TrendPoint(LocalDate date, double rate, long lines) {}

    public record SourceAmount(String source, long amount, long count) {}

    public record Dashboard(double autoRate7, double autoRatePrev7, long open, long overdue, long pendingApproval, long unreconciled,
                            long agingOver7, long agingOver30, long feeDiffAmount, long feeDiffCount, List<TrendPoint> trend,
                            List<SourceAmount> bySource, List<Views.JobView> failedJobs, List<Views.Arrival> arrivals,
                            List<Views.ExceptionView> latest) {}

    public Dashboard dashboard() {
        LocalDate today = reports.today();
        Instant now = reports.now();
        List<Rows.DayStat> days = reports.dayStats(today.minusDays(29), today, null);
        List<Rows.DayStat> last7 = days.stream().filter(d -> !d.date.isBefore(today.minusDays(6))).toList();
        List<Rows.DayStat> prev7 = days.stream().filter(d -> !d.date.isBefore(today.minusDays(13)) && d.date.isBefore(today.minusDays(6))).toList();
        List<TrendPoint> trend = new ArrayList<>();
        for (int i = 29; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            List<Rows.DayStat> ds = days.stream().filter(d -> d.date.equals(day)).toList();
            trend.add(new TrendPoint(day, reports.autoRate(ds), ds.stream().mapToLong(d -> d.totalLines).sum()));
        }

        List<Source> active = activeSources();
        Map<String, Rows.SourceAmount> open = reports.openBySource().stream().collect(Collectors.toMap(r -> r.source, Function.identity()));
        List<SourceAmount> bySource = active.stream()
                .map(s -> new SourceAmount(s.getCode(), open.containsKey(s.getCode()) ? open.get(s.getCode()).amount : 0,
                        open.containsKey(s.getCode()) ? open.get(s.getCode()).count : 0))
                .sorted(Comparator.comparingLong(SourceAmount::amount).reversed()).toList();

        List<Rows.AgingRow> aging = reports.aging();
        Rows.ExceptionCounts counts = reports.exceptionCounts();
        Rows.FeeDiffTotal fee = reports.feeDiffThisMonth();
        List<Views.JobView> failed = batch.recentViews(300).stream().filter(Views.JobView::needsAction).limit(5).toList();
        List<Views.ExceptionView> latest = reports.searchExceptions(new ExceptionQuery("todo", null, null, "all", false, null, now, 500)).stream()
                .filter(r -> "OPEN".equals(r.status))
                .sorted(Comparator.comparing((Rows.ExceptionRow r) -> r.createdAt).reversed())
                .limit(6).map(ViewService::row).toList();

        return new Dashboard(reports.autoRate(last7), reports.autoRate(prev7), counts.todo, counts.overdue, counts.pending,
                bySource.stream().mapToLong(SourceAmount::amount).sum(),
                aging.stream().mapToLong(r -> r.b1 + r.b2 + r.b3).sum(), aging.stream().mapToLong(r -> r.b2 + r.b3).sum(),
                fee.amount, fee.count, trend, bySource, failed, arrivals(active, now), latest);
    }

    /** Which partner files came in today, which are late against their usual hour, which are not expected today. */
    List<Views.Arrival> arrivals(List<Source> active, Instant now) {
        ZonedDateTime local = now.atZone(reports.zone());
        List<Rows.FileArrivalRow> today = reports.filesReceivedToday().stream()
                .filter(f -> !"DUPLICATE".equals(f.status) && !"FAILED".equals(f.status))
                .sorted(Comparator.comparing(f -> f.receivedAt)).toList();
        List<Views.Arrival> out = new ArrayList<>();
        for (Source s : active) {
            var f = today.stream().filter(x -> x.source.equals(s.getCode())).findFirst();
            if (f.isPresent()) {
                out.add(new Views.Arrival(s.getCode(), "received", f.get().receivedAt, f.get().totalRows, null, null));
            } else if (s.getExpectedHour() == null) {
                boolean due = s.payoutWeekdays().contains(local.getDayOfWeek().getValue());
                out.add(new Views.Arrival(s.getCode(), due ? "due" : "none", null, null, null, null));
            } else {
                String expected = LocalTime.of(s.getExpectedHour(), 0).toString();
                long minutes = (local.getHour() - s.getExpectedHour()) * 60L + local.getMinute();
                out.add(minutes > 15 ? new Views.Arrival(s.getCode(), "late", null, null, minutes, expected)
                        : new Views.Arrival(s.getCode(), "waiting", null, null, null, expected));
            }
        }
        return out;
    }

    List<Source> activeSources() {
        return sources.findAll().stream().filter(s -> s.getStatus() == SourceStatus.ACTIVE)
                .sorted(Comparator.comparingInt(s -> SOURCE_ORDER.indexOf(s.getCode()))).toList();
    }
}
