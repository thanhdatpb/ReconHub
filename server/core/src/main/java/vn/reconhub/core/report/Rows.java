package vn.reconhub.core.report;

import java.time.Instant;
import java.time.LocalDate;

/** Flat result rows of {@link ReportMapper}. MyBatis maps snake_case columns to these fields. */
public final class Rows {
    private Rows() {}

    public static class DayStat {
        public LocalDate date;
        public String source;
        public long totalLines;
        public long autoMatched;
        public long manualMatched;
        public long exceptions;
        public long amountIn;
        public long feeTotal;
    }

    public static class ExceptionRow {
        public String id;
        public String type;
        public String sourceCode;
        public String status;
        public long diffAmount;
        public Instant createdAt;
        public Instant slaDueAt;
        public String assigneeId;
        public int version;
        public Double aiConfidence;
        public String orderCode;
        public String customerName;
        public String trackingCode;
        public String externalRef;
        public String description;
    }

    public static class ExceptionCounts {
        public long todo;
        public long pending;
        public long done;
        public long all;
        public long overdue;
    }

    public static class SourceAmount {
        public String source;
        public long amount;
        public long count;
    }

    public static class FeeDiffTotal {
        public long amount;
        public long count;
    }

    public static class MatchGroupRow {
        public long id;
        public String method;
        public double confidence;
        public String orderCodes;
        public String paymentRef;
        public long amount;
        public long fee;
    }

    public static class AgingRow {
        public String source;
        public long b0;
        public long b1;
        public long b2;
        public long b3;
    }

    public static class JournalRow {
        public LocalDate date;
        public String source;
        public String kind;
        public long amount;
        public String ref;
    }

    public static class FileArrivalRow {
        public String source;
        public Instant receivedAt;
        public long totalRows;
        public String status;
    }
}
