package vn.reconhub.core.matching;

import org.springframework.stereotype.Component;
import vn.reconhub.core.domain.Order;

/**
 * Expected partner fee per order, mirroring the fee rules shown on the Sources screen.
 * Integer arithmetic with half-up rounding so results match the partners' own files to the đồng.
 */
@Component
public class FeeCalculator {

    public static final long GHN_DELIVERY = 22_000;
    public static final long GHN_REMOTE_SURCHARGE = 8_000;

    /** Expected fee, or -1 when the source has no fee rule. */
    public long expectedFee(String sourceCode, Order order, long lineGross) {
        return switch (sourceCode) {
            case "GHN" -> GHN_DELIVERY + Math.max(5_000, halfUp(order.getCodAmount() * 5, 1_000))
                    + (order.isRemoteArea() ? GHN_REMOTE_SURCHARGE : 0);
            case "GHTK" -> order.getAmount() > 900_000 ? 30_000 : 20_000;
            case "SHOPEE" -> halfUp(order.getAmount() * 400, 10_000) + halfUp(order.getAmount() * 491, 10_000);
            case "TIKTOK" -> halfUp(order.getAmount() * 5, 100);
            case "MOMO" -> halfUp(lineGross, 100);
            default -> -1;
        };
    }

    static long halfUp(long numerator, long denominator) {
        return (numerator + denominator / 2) / denominator;
    }
}
