package vn.reconhub.core.fees;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.reconhub.core.config.ReconProperties;
import vn.reconhub.core.domain.Enums.ExceptionType;
import vn.reconhub.core.domain.FeeComponent;
import vn.reconhub.core.domain.FeeComponent.Base;
import vn.reconhub.core.domain.Order;
import vn.reconhub.core.domain.PaymentLine;
import vn.reconhub.core.domain.ReconException;
import vn.reconhub.core.report.ReportMapper;
import vn.reconhub.core.report.Rows;
import vn.reconhub.core.util.Money;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * Whether a fee difference looks like a new tariff rather than a partner's mistake, and which rule would explain it.
 * A tariff is inferred only when, for one source:
 * <ul>
 *   <li>at least {@value #MIN_CASES} payments within {@link #WINDOW} were charged exactly the same amount more;</li>
 *   <li>the extra is one fixed amount, possibly only above a threshold of the order value (COD for GHN): from the
 *       first such payment on, every payment above the threshold carries it and none at or below does.</li>
 * </ul>
 * A percentage overcharge (Shopee charging 1% more) gives a different extra per order, and same-value orders charged
 * correctly contradict a threshold, so neither is taken for a tariff.
 */
@Service
public class FeeRuleInference {
    public static final int MIN_CASES = 5;
    public static final Duration WINDOW = Duration.ofDays(14);
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ReportMapper reports;
    private final ReconProperties props;

    public FeeRuleInference(ReportMapper reports, ReconProperties props) {
        this.reports = reports;
        this.props = props;
    }

    /** The inferred rule: {@code extra} on top of the schedule, from {@code effectiveFrom}, when the base is above {@code threshold}. */
    public record Inferred(String source, long extra, Base base, Long threshold, LocalDate effectiveFrom, int cases,
                           long minBase, long maxBase, int chargedAsBefore, List<String> exceptionIds) {
        public FeeComponent component() {
            return FeeComponent.fixed(threshold == null ? "Phụ phí mới" : "Phụ phí " + baseLabel(base) + " lớn", extra,
                    threshold == null ? null : new FeeComponent.Condition(null, base, threshold, null));
        }

        /** "+2.000 ₫ cho COD trên 1.500.000 ₫ từ 13/10/2026" */
        public String rule() {
            return "+" + Money.format(extra) + (threshold == null ? " cho mọi đơn" : " cho " + baseLabel(base) + " trên " + Money.format(threshold))
                    + " từ " + DMY.format(effectiveFrom);
        }

        public List<String> evidence() {
            String range = minBase == maxBase ? Money.format(minBase) : "từ " + Money.format(minBase) + " đến " + Money.format(maxBase);
            return List.of(
                    cases + " giao dịch " + source + " từ " + DMY.format(effectiveFrom) + " bị trừ thêm đúng " + Money.format(extra) + ", "
                            + baseLabel(base) + " " + range,
                    threshold == null ? "Không có giao dịch nào cùng kỳ tính đúng biểu phí hiện tại"
                            : chargedAsBefore + " giao dịch cùng kỳ có " + baseLabel(base) + " đến " + Money.format(threshold) + " vẫn đúng biểu phí");
        }
    }

    /** {@code inferred} when a simple rule explains the difference, otherwise {@code reason} says why not (Vietnamese). */
    public record Outcome(Inferred inferred, String reason) {
        public boolean found() {
            return inferred != null;
        }
    }

    @Transactional(readOnly = true)
    public Outcome infer(ReconException e) {
        if (e.getType() != ExceptionType.FEE_DIFF) return no("Chỉ suy ra biểu phí từ ngoại lệ sai phí");
        if (e.getPayments().size() != 1 || e.getOrders().size() != 1 || !(e.getMeta().get("expectedFee") instanceof Number expected)
                || !(e.getMeta().get("actualFee") instanceof Number actual)) {
            return no("Ngoại lệ thiếu dữ liệu phí nên không suy ra được biểu phí");
        }
        String source = e.getSourceCode();
        long extra = actual.longValue() - expected.longValue();
        Order order = e.getOrders().get(0);
        PaymentLine line = e.getPayments().get(0);
        if (extra <= 0) return no("Phí thực trừ không cao hơn biểu phí nên không có khoản phí mới để thêm");
        if ("GHN".equals(source) && extra == 8_000 && !order.isRemoteArea()) {
            return no("Chênh đúng 8.000 ₫ bằng phụ phí vùng xa đã có trong biểu phí: cần đánh dấu vùng xa cho đơn trong OMS, không cần đổi biểu phí");
        }

        Base base = baseOf(source);
        List<Rows.FeeCheckedLine> rows = reports.feeCheckedLines(source, line.getTxnAt().minus(WINDOW));
        List<Rows.FeeCheckedLine> same = rows.stream().filter(r -> r.extra != null && r.extra == extra)
                .sorted(Comparator.comparing(r -> r.txnAt)).toList();
        if (same.size() < MIN_CASES) {
            return no("Mới có " + same.size() + " giao dịch " + source + " bị trừ thêm đúng " + Money.format(extra) + " trong "
                    + WINDOW.toDays() + " ngày; cần ít nhất " + MIN_CASES + " để suy ra một quy tắc");
        }
        Instant from = same.get(0).txnAt.atZone(props.getZone()).toLocalDate().atStartOfDay(props.getZone()).toInstant();
        long minBase = same.stream().mapToLong(r -> base(base, r)).min().orElseThrow();
        long maxBase = same.stream().mapToLong(r -> base(base, r)).max().orElseThrow();
        List<Rows.FeeCheckedLine> asBefore = rows.stream().filter(r -> r.extra == null && !r.txnAt.isBefore(from)).toList();
        long contradicting = asBefore.stream().filter(r -> base(base, r) >= minBase).count();
        if (contradicting > 0) {
            return no(contradicting + " giao dịch " + source + " có " + baseLabel(base) + " từ " + Money.format(minBase)
                    + " trở lên cùng kỳ vẫn tính đúng biểu phí, nên mức chênh không giải thích được bằng một khoản cố định theo ngưỡng."
                    + " Nhiều khả năng đối tác tính sai: nên gửi khiếu nại");
        }
        Long threshold = asBefore.isEmpty() ? null : threshold(asBefore.stream().mapToLong(r -> base(base, r)).max().orElseThrow(), minBase);
        LocalDate effectiveFrom = from.atZone(props.getZone()).toLocalDate();
        return new Outcome(new Inferred(source, extra, base, threshold, effectiveFrom, same.size(), minBase, maxBase, asBefore.size(),
                same.stream().map(r -> r.exceptionId).toList()), null);
    }

    /** The roundest threshold that separates correctly charged values (up to {@code maxOk}) from overcharged ones ({@code minOver}). */
    static long threshold(long maxOk, long minOver) {
        for (long step : new long[] {1_000_000, 500_000, 100_000, 50_000, 10_000, 1_000}) {
            long t = (minOver - 1) / step * step;
            if (t >= maxOk) return t;
        }
        return maxOk;
    }

    /** What a threshold compares for each source: COD for GHN, the transaction for MoMo, the order value otherwise. */
    public static Base baseOf(String source) {
        return switch (source) {
            case "GHN" -> Base.COD;
            case "MOMO" -> Base.LINE_GROSS;
            default -> Base.ORDER_AMOUNT;
        };
    }

    private static long base(Base base, Rows.FeeCheckedLine r) {
        return switch (base) {
            case COD -> r.cod;
            case ORDER_AMOUNT -> r.orderAmount;
            case LINE_GROSS -> r.gross;
        };
    }

    static String baseLabel(Base base) {
        return switch (base) {
            case COD -> "COD";
            case ORDER_AMOUNT -> "giá trị đơn";
            case LINE_GROSS -> "giá trị giao dịch";
        };
    }

    private static Outcome no(String reason) {
        return new Outcome(null, reason);
    }
}
