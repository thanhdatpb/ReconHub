package vn.reconhub.api.web;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.reconhub.core.config.ReconProperties;
import vn.reconhub.core.ai.AiGateway;
import vn.reconhub.core.report.ReportService;
import vn.reconhub.core.repo.AppUserRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Overview, reconciliation results, aging and journal, plus the reference data the UI needs at start-up. */
@RestController
@RequestMapping("/api")
public class ReportController {
    static final List<String> AGING_BUCKETS = List.of("0–7 ngày", "8–30 ngày", "31–60 ngày", "Trên 60 ngày");

    public record ScheduleItem(String job, String cron, String human) {}

    static final List<ScheduleItem> SCHEDULE = List.of(
            new ScheduleItem("fileIngestionJob", "khi có file", "Quét /inbox mỗi 20 giây và khi có file tải lên"),
            new ScheduleItem("matchingJob", "0 0 1 * * *", "01:00 hằng ngày và ngay sau khi nạp file"),
            new ScheduleItem("feeValidationJob", "0 20 1 * * *", "01:20 hằng ngày và ngay sau khi khớp"),
            new ScheduleItem("agingAndReportJob", "0 0 6 * * *", "06:00 hằng ngày"),
            new ScheduleItem("archiveAndPurgeJob", "0 0 2 * * SUN", "02:00 Chủ nhật"));

    private final ReportService reports;
    private final DashboardService dashboard;
    private final ViewService views;
    private final AppUserRepository users;
    private final AiGateway ai;
    private final ReconProperties props;

    public ReportController(ReportService reports, DashboardService dashboard, ViewService views, AppUserRepository users, AiGateway ai,
                            ReconProperties props) {
        this.reports = reports;
        this.dashboard = dashboard;
        this.views = views;
        this.users = users;
        this.ai = ai;
        this.props = props;
    }

    public record Meta(Instant now, String zone, List<Views.UserView> users, List<Views.SourceView> sources, List<String> activeSources,
                       List<ScheduleItem> batchSchedule, Map<String, String> accounts, List<String> agingBuckets,
                       long writeOffApprovalThreshold, int slaHours, boolean aiEnabled, String aiModel) {}

    @GetMapping("/meta")
    public Meta meta() {
        List<Views.SourceView> sources = views.sources();
        return new Meta(reports.now(), reports.zone().getId(),
                users.findAll().stream().sorted(Comparator.comparing(u -> u.getId())).map(Views::user).toList(),
                sources, sources.stream().filter(s -> "ACTIVE".equals(s.status())).map(Views.SourceView::code).toList(),
                SCHEDULE, ReportService.ACCOUNTS, AGING_BUCKETS, props.getWriteOffApprovalThreshold(), props.getSlaHours(),
                ai.enabled(), ai.enabled() ? ai.model() : null);
    }

    @GetMapping("/dashboard")
    public DashboardService.Dashboard dashboard() {
        return dashboard.dashboard();
    }

    @GetMapping("/recon/days")
    public List<Views.ReconDayView> days(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) String source) {
        List<String> sources = source == null || source.isBlank() ? null : Arrays.stream(source.split(",")).map(String::trim).toList();
        return reports.dayStats(from, to, sources).stream()
                .map(d -> new Views.ReconDayView(d.date, d.source, d.totalLines, d.autoMatched, d.manualMatched, d.exceptions, d.amountIn, d.feeTotal))
                .toList();
    }

    @GetMapping("/recon/days/{date}/{source}/groups")
    public List<Views.MatchGroupView> groups(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @PathVariable String source) {
        return reports.matchGroups(date, source).stream()
                .map(g -> new Views.MatchGroupView(String.valueOf(g.id), g.method, g.orderCodes == null ? List.of() : List.of(g.orderCodes.split(",")),
                        g.paymentRef, g.amount, g.fee, g.confidence))
                .toList();
    }

    @GetMapping("/aging")
    public List<Views.AgingView> aging() {
        return reports.aging().stream().map(r -> new Views.AgingView(r.source, List.of(r.b0, r.b1, r.b2, r.b3))).toList();
    }

    @GetMapping("/journal")
    public List<Views.JournalView> journal(@RequestParam String month) {
        return reports.journal(YearMonth.parse(month)).stream()
                .map(e -> new Views.JournalView(e.date(), e.voucher(), e.description(), e.debit(), e.credit(), e.amount(), e.source()))
                .toList();
    }
}
