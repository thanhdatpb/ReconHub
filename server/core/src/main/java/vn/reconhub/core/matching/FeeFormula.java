package vn.reconhub.core.matching;

import vn.reconhub.core.domain.FeeComponent;
import vn.reconhub.core.domain.FeeComponent.Base;
import vn.reconhub.core.domain.FeeComponent.Condition;
import vn.reconhub.core.domain.Order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The arithmetic of a fee schedule: each component that applies is rounded half-up to the đồng, then they are added.
 * Percentages use exact decimals, so 0,5% of 1.290.000 ₫ is 6.450 ₫ and 4,91% of 399.000 ₫ is 19.591 ₫.
 */
public final class FeeFormula {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

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

    static long base(Base base, Order order, long lineGross) {
        return switch (base) {
            case COD -> order.getCodAmount();
            case ORDER_AMOUNT -> order.getAmount();
            case LINE_GROSS -> lineGross;
        };
    }
}
