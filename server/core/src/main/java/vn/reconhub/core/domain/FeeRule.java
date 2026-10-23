package vn.reconhub.core.domain;

/** Human-readable fee rule shown in the UI; the arithmetic lives in {@link vn.reconhub.core.matching.FeeCalculator}. */
public record FeeRule(String name, String formula, String effectiveFrom) {}
