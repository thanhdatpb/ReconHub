package vn.reconhub.core.ai;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import vn.reconhub.core.domain.AppUser;
import vn.reconhub.core.domain.Enums.Role;
import vn.reconhub.core.report.ExceptionQuery;
import vn.reconhub.core.report.ReportService;
import vn.reconhub.core.report.Rows;
import vn.reconhub.core.util.Money;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The only things the assistant can do: read reports, as the person asking. One instance per question,
 * so it can record which tools ran and keep the last table or chart for the UI.
 */
public class ReadOnlyTools {
    static final Map<String, String> LABEL = Map.of("GHN", "GHN", "GHTK", "GHTK", "SHOPEE", "Shopee", "TIKTOK", "TikTok Shop",
            "VCB", "Vietcombank", "MOMO", "MoMo");

    private final ReportService reports;
    private final AppUser user;
    private final JobStatusProvider jobs;
    final List<String> calls = new ArrayList<>();
    AssistantService.Table table;
    AssistantService.Chart chart;

    public ReadOnlyTools(ReportService reports, AppUser user, JobStatusProvider jobs) {
        this.reports = reports;
        this.user = user;
        this.jobs = jobs;
    }

    @Tool(description = "Số tiền đối tác (GHN, GHTK, SHOPEE, TIKTOK) còn nợ cửa hàng, chia theo tuổi nợ 0-7, 8-30, 31-60 và trên 60 ngày kể từ khi giao hàng.")
    public String getReceivables(@ToolParam(description = "Mã đối tác: GHN, GHTK, SHOPEE hoặc TIKTOK. Bỏ trống để lấy tất cả.", required = false) String source) {
        calls.add("getReceivables(" + (source == null ? "" : "source=" + source) + ")");
        if (!canSeeMoney()) return "Tài khoản của bạn không được xem số liệu tiền, nên mình không trả lời được câu này.";
        List<Rows.AgingRow> rows = reports.aging().stream()
                .filter(r -> source == null || source.isBlank() || r.source.equalsIgnoreCase(source)).toList();
        List<List<Object>> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (Rows.AgingRow r : rows) {
            long total = r.b0 + r.b1 + r.b2 + r.b3;
            out.add(List.of(LABEL.getOrDefault(r.source, r.source), Money.format(r.b0), Money.format(r.b1), Money.format(r.b2), Money.format(r.b3), Money.format(total)));
            sb.append(r.source).append(": 0-7 ngày ").append(r.b0).append(", 8-30 ngày ").append(r.b1).append(", 31-60 ngày ").append(r.b2)
                    .append(", trên 60 ngày ").append(r.b3).append(", tổng ").append(total).append(" VND\n");
        }
        table = new AssistantService.Table(List.of("Đối tác", "0–7 ngày", "8–30 ngày", "31–60 ngày", "Trên 60 ngày", "Tổng"), out);
        return sb.isEmpty() ? "Không có khoản phải thu nào." : sb.toString();
    }

    @Tool(description = "Tỷ lệ khớp tự động theo từng nguồn trong N ngày gần nhất.")
    public String getMatchRate(@ToolParam(description = "Số ngày gần nhất, mặc định 7") Integer days) {
        int n = days == null || days < 1 ? 7 : Math.min(days, 90);
        calls.add("getMatchRate(days=" + n + ")");
        LocalDate to = reports.today();
        List<Rows.DayStat> stats = reports.dayStats(to.minusDays(n - 1L), to, List.of());
        Map<String, long[]> by = new LinkedHashMap<>();
        for (Rows.DayStat d : stats) {
            long[] a = by.computeIfAbsent(d.source, k -> new long[2]);
            a[0] += d.totalLines;
            a[1] += d.autoMatched;
        }
        List<List<Object>> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder("Tổng: " + pct(reports.autoRate(stats)) + "\n");
        by.forEach((s, a) -> {
            out.add(List.of(LABEL.getOrDefault(s, s), a[0], pct(a[0] == 0 ? 0 : (double) a[1] / a[0])));
            sb.append(s).append(": ").append(a[0]).append(" dòng, khớp tự động ").append(pct(a[0] == 0 ? 0 : (double) a[1] / a[0])).append('\n');
        });
        table = new AssistantService.Table(List.of("Nguồn", "Số giao dịch", "Khớp tự động"), out);
        return sb.toString();
    }

    @Tool(description = "Tổng phí đối tác đã trừ từ đầu tháng đến nay, theo từng nguồn.")
    public String getFees() {
        calls.add("getFees(month=" + YearMonth.from(reports.today()) + ")");
        if (!canSeeMoney()) return "Tài khoản của bạn không được xem số liệu tiền, nên mình không trả lời được câu này.";
        LocalDate to = reports.today();
        List<Rows.DayStat> stats = reports.dayStats(to.withDayOfMonth(1), to, List.of());
        Map<String, Long> by = new LinkedHashMap<>();
        stats.forEach(d -> by.merge(d.source, d.feeTotal, Long::sum));
        List<Map.Entry<String, Long>> sorted = by.entrySet().stream().filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()).toList();
        chart = new AssistantService.Chart(sorted.stream().map(e -> LABEL.getOrDefault(e.getKey(), e.getKey())).toList(),
                sorted.stream().map(Map.Entry::getValue).toList(), "Phí bị trừ");
        StringBuilder sb = new StringBuilder();
        sorted.forEach(e -> sb.append(e.getKey()).append(": ").append(e.getValue()).append(" VND\n"));
        return sb.isEmpty() ? "Chưa có phí nào trong tháng." : sb.toString();
    }

    @Tool(description = "Danh sách ngoại lệ đang chờ xử lý, ưu tiên các ngoại lệ quá hạn.")
    public String getExceptions(@ToolParam(description = "true để chỉ lấy ngoại lệ quá hạn xử lý", required = false) Boolean overdueOnly,
                                @ToolParam(description = "Số dòng tối đa, mặc định 5", required = false) Integer limit) {
        int n = limit == null || limit < 1 ? 5 : Math.min(limit, 20);
        boolean overdue = Boolean.TRUE.equals(overdueOnly);
        calls.add("getExceptions(overdue=" + overdue + ", limit=" + n + ")");
        Rows.ExceptionCounts counts = reports.exceptionCounts();
        List<Rows.ExceptionRow> rows = reports.searchExceptions(new ExceptionQuery("todo", List.of(), List.of(), "all", overdue, null, reports.now(), n));
        table = new AssistantService.Table(List.of("Mã", "Nguồn", "Loại", "Chênh lệch"),
                rows.stream().map(r -> List.<Object>of(r.id, LABEL.getOrDefault(r.sourceCode, r.sourceCode), r.type, Money.format(r.diffAmount))).toList());
        return "Đang mở: " + counts.todo + ", quá hạn: " + counts.overdue + ", chờ duyệt: " + counts.pending + ".\n"
                + String.join("\n", rows.stream().map(r -> r.id + " " + r.sourceCode + " " + r.type + " " + r.diffAmount).toList());
    }

    @Tool(description = "Các file đối soát đã nhận hôm nay.")
    public String getFileStatus() {
        calls.add("getFileStatus()");
        List<Rows.FileArrivalRow> rows = reports.filesReceivedToday();
        table = new AssistantService.Table(List.of("Nguồn", "Nhận lúc", "Số dòng"),
                rows.stream().map(r -> List.<Object>of(LABEL.getOrDefault(r.source, r.source), r.receivedAt.toString(), r.totalRows)).toList());
        return rows.isEmpty() ? "Hôm nay chưa nhận file nào." : String.join("\n", rows.stream().map(r -> r.source + " lúc " + r.receivedAt + ", " + r.totalRows + " dòng").toList());
    }

    @Tool(description = "Các lần chạy batch gần nhất và lỗi nếu có.")
    public String getJobStatus() {
        calls.add("getJobStatus()");
        if (jobs == null) return "Không đọc được thông tin batch.";
        List<JobStatusProvider.JobLine> lines = jobs.recent(8);
        table = new AssistantService.Table(List.of("Lần chạy", "Job", "Trạng thái", "Bắt đầu"),
                lines.stream().map(l -> List.<Object>of("#" + l.id(), l.job(), l.status(), l.started())).toList());
        return String.join("\n", lines.stream().map(l -> "#" + l.id() + " " + l.job() + " " + l.status() + " " + l.started()
                + (l.exitMessage() == null || l.exitMessage().isBlank() ? "" : " lỗi: " + l.exitMessage())
                + (l.handled() == null ? "" : " (không cần xử lý thêm: " + l.handled() + ")")).toList());
    }

    private boolean canSeeMoney() {
        return user.getRole() != Role.ADMIN;
    }

    static String pct(double x) {
        return String.format(Locale.forLanguageTag("vi-VN"), "%.1f%%", x * 100);
    }
}
