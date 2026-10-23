package vn.reconhub.core.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One canonical field and the partner's column that feeds it. Confidence and note are set when AI proposed it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MappingRow(String field, String column, Double confidence, String aiNote) {
    public static final java.util.List<String> FIELDS =
            java.util.List.of("externalRef", "orderCode", "trackingCode", "amount", "fee", "txnDate", "description");
    public static final java.util.Set<String> REQUIRED = java.util.Set.of("externalRef", "amount", "txnDate");
}
