package vn.reconhub.core.matching;

import vn.reconhub.core.domain.FeeComponent;
import vn.reconhub.core.domain.FeeComponent.Base;
import vn.reconhub.core.domain.FeeComponent.Condition;
import vn.reconhub.core.domain.FeeSchedule;
import vn.reconhub.core.domain.Order;
import vn.reconhub.core.util.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The arithmetic of a fee schedule: each component that applies is rounded half-up to the đồng, then they are added.
 * Percentages use exact decimals, so 0,5% of 1.290.000 ₫ is 6.450 ₫ and 4,91% of 399.000 ₫ is 19.591 ₫.
 */
public final class FeeFormula {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private FeeFormula() {}

    public static long fee(List<FeeComponent> components, Order order, long lineGross) {
        long total = 0;
        for (FeeComponent c : components) {
            if (!applies(c.when(), order, lineGross)) continue;
            total += switch (c.kind()) {
                case FIXED -> c.amount();
                case PERCENT -> {
                    long v = BigDecimal.valueOf(base(c.base(), order, lineGross)).multiply(c.rate())
                            .divide(HUNDRED, 0, RoundingMode.HALF_UP).longValueExact();
                    yield c.min() == null ? v : Math.max(c.min(), v);
                }
            };
        }
        return total;
    }

    static boolean applies(Condition when, Order order, long lineGross) {
        if (when == null) return true;
        if (Boolean.TRUE.equals(when.remoteArea()) && !order.isRemoteArea()) return false;
        if (when.above() == null && when.atMost() == null) return true;
        long value = base(when.base(), order, lineGross);
        if (when.above() != null && value <= when.above()) return false;
        return when.atMost() == null || value <= when.atMost();
    }

    /** "Phiên bản 2, áp dụng từ 13/10/2026" or "Phiên bản 1, áp dụng từ đầu". */
    public static String title(FeeSchedule s) {
        String from = FeeSchedule.FROM_THE_START.equals(s.getEffectiveFrom()) ? "áp dụng từ đầu" : "áp dụng từ " + DMY.format(s.getEffectiveFrom());
        return (s.getVersionNo() == null ? "Đề xuất" : "Phiên bản " + s.getVersionNo()) + ", " + from;
    }

    /** "Cước giao 22.000 ₫; Phí thu hộ 0,5% COD, tối thiểu 5.000 ₫; Phụ phí vùng xa 8.000 ₫ nếu vùng xa". */
    public static String describe(List<FeeComponent> components) {
        return components.stream().map(FeeFormula::describe).collect(Collectors.joining("; "));
    }

    static String describe(FeeComponent c) {
        String value = c.kind() == FeeComponent.Kind.FIXED ? Money.format(c.amount())
                : c.rate().stripTrailingZeros().toPlainString().replace('.', ',') + "% " + baseLabel(c.base())
                + (c.min() == null ? "" : ", tối thiểu " + Money.format(c.min()));
        return c.label() + " " + value + condition(c.when());
    }

    private static String condition(Condition when) {
        if (when == null) return "";
        StringBuilder s = new StringBuilder();
        if (Boolean.TRUE.equals(when.remoteArea())) s.append(" nếu vùng xa");
        if (when.above() != null) s.append(" nếu ").append(baseLabel(when.base())).append(" trên ").append(Money.format(when.above()));
        if (when.atMost() != null) s.append(when.above() == null ? " nếu " : " và ").append(baseLabel(when.base())).append(" đến ").append(Money.format(when.atMost()));
        return s.toString();
    }

    static String baseLabel(Base base) {
        return switch (base) {
            case COD -> "COD";
            case ORDER_AMOUNT -> "giá trị đơn";
            case LINE_GROSS -> "giá trị giao dịch";
        };
    }

    static long base(Base base, Order order, long lineGross) {
        return switch (base) {
            case COD -> order.getCodAmount();
            case ORDER_AMOUNT -> order.getAmount();
            case LINE_GROSS -> lineGross;
        };
    }
}
