package vn.reconhub.core.domain;

import java.math.BigDecimal;

/**
 * One part of a fee schedule: a fixed amount, or a percentage of a base with an optional minimum, applied when its
 * condition holds. Each part is rounded half-up to the đồng on its own, then the parts are added up
 * ({@link vn.reconhub.core.matching.FeeFormula}).
 */
public record FeeComponent(String label, Kind kind, Long amount, BigDecimal rate, Base base, Long min, Condition when) {

    public enum Kind { FIXED, PERCENT }

    /** COD: the order's cash on delivery; ORDER_AMOUNT: the order value; LINE_GROSS: the payment line before fee. */
    public enum Base { COD, ORDER_AMOUNT, LINE_GROSS }

    /** {@code remoteArea}: only for orders flagged as remote. {@code above} is strictly greater, {@code atMost} less or equal. */
    public record Condition(Boolean remoteArea, Base base, Long above, Long atMost) {}

    public static FeeComponent fixed(String label, long amount, Condition when) {
        return new FeeComponent(label, Kind.FIXED, amount, null, null, null, when);
    }

    public static FeeComponent percent(String label, String rate, Base base, Long min, Condition when) {
        return new FeeComponent(label, Kind.PERCENT, null, new BigDecimal(rate), base, min, when);
    }
}
