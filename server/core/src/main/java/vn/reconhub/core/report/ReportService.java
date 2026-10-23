package vn.reconhub.core.report;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.reconhub.core.config.ReconProperties;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Report queries plus the small amount of shaping the screens and the assistant need. */
@Service
@Transactional(readOnly = true)
public class ReportService {
    public static final Map<String, String> ACCOUNTS = new LinkedHashMap<>();

    static {
        ACCOUNTS.put("112", "Tiền gửi ngân hàng");
        ACCOUNTS.put("131", "Phải thu của khách hàng");
        ACCOUNTS.put("641", "Chi phí bán hàng");
        ACCOUNTS.put("711", "Thu nhập khác");
        ACCOUNTS.put("811", "Chi phí khác");
    }

    private static final Map<String, String> SOURCE_LABEL = Map.of("GHN", "GHN", "GHTK", "GHTK", "SHOPEE", "Shopee",
            "TIKTOK", "TikTok Shop", "VCB", "Vietcombank", "MOMO", "MoMo", "VTP", "Viettel Post");

    private final ReportMapper mapper;
    private final ReconProperties props;
    private final Clock clock;

    public ReportService(ReportMapper mapper, ReconProperties props, Clock clock) {
        this.mapper = mapper;
        this.props = props;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public Instant now() {
        return Instant.now(clock);
    }

    public List<Rows.DayStat> dayStats(LocalDate from, LocalDate to, List<String> sources) {
        return mapper.dayStats(from, to, sources);
    }

    public double autoRate(List<Rows.DayStat> days) {
        long total = days.stream().mapToLong(d -> d.totalLines).sum();
        long auto = days.stream().mapToLong(d -> d.autoMatched).sum();
        return total == 0 ? 0 : (double) auto / total;
    }

    public List<Rows.ExceptionRow> searchExceptions(ExceptionQuery q) {
        return mapper.searchExceptions(q);
    }

    public Rows.ExceptionCounts exceptionCounts() {
        return mapper.exceptionCounts(now());
    }

    public List<Rows.SourceAmount> openBySource() {
        return mapper.openBySource();
    }

    public Rows.FeeDiffTotal feeDiffThisMonth() {
        Instant since = YearMonth.now(clock).atDay(1).atStartOfDay(clock.getZone()).toInstant();
        return mapper.feeDiffSince(since);
    }

    public List<Rows.MatchGroupRow> matchGroups(LocalDate date, String source) {
        return mapper.matchGroups(date, source);
    }

    public List<Rows.AgingRow> aging() {
        return mapper.aging(today(), props.getZone().getId());
    }

    public List<Rows.FileArrivalRow> filesReceivedToday() {
        Instant start = today().atStartOfDay(clock.getZone()).toInstant();
        return mapper.filesReceivedBetween(start, start.plusSeconds(86_400));
    }

    public record JournalEntry(LocalDate date, String voucher, String description, String debit, String credit, long amount, String source) {}

    /** Journal entries for a month: money in (Nợ 112/Có 131), partner fees (Nợ 641/Có 131) and write-offs. */
    public List<JournalEntry> journal(YearMonth month) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.plusMonths(1).atDay(1);
        Instant fromTs = from.atStartOfDay(clock.getZone()).toInstant();
        Instant toTs = to.atStartOfDay(clock.getZone()).toInstant();
        DateTimeFormatter dm = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        String mm = String.format("%02d", month.getMonthValue());
        List<JournalEntry> out = new ArrayList<>();
        int seq = 1;
        for (Rows.JournalRow r : mapper.journal(from, to, fromTs, toTs, props.getZone().getId())) {
            String label = SOURCE_LABEL.getOrDefault(r.source, r.source);
            String period = r.date.format(dm);
            JournalEntry e = switch (r.kind) {
                case "IN" -> new JournalEntry(r.date, "BC" + mm + "-" + pad(seq++), switch (r.source) {
                    case "VCB" -> "Khách chuyển khoản đã khớp đơn, kỳ " + period;
                    case "MOMO" -> "Thu qua ví MoMo đã khớp đơn, kỳ " + period;
                    default -> "Thu tiền " + label + " đã khớp đơn, kỳ " + period;
                }, "112", "131", r.amount, r.source);
                case "FEE" -> new JournalEntry(r.date, "PK" + mm + "-" + pad(seq++), "Phí " + label + " bị trừ, kỳ " + period, "641", "131", r.amount, r.source);
                case "WRITE_OFF_LOSS" -> new JournalEntry(r.date, "PK" + mm + "-" + pad(seq++), "Ghi giảm chênh lệch " + r.ref, "811", "131", r.amount, r.source);
                default -> new JournalEntry(r.date, "PK" + mm + "-" + pad(seq++), "Ghi nhận khoản thu chưa xác định " + r.ref, "131", "711", r.amount, r.source);
            };
            out.add(e);
        }
        return out;
    }

    private static String pad(int n) {
        return String.format("%04d", n);
    }
}
