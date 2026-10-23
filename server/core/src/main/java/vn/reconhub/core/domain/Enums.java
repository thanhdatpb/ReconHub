package vn.reconhub.core.domain;

/** Enumerations shared by entities, DTOs and SQL. Stored as their names. */
public final class Enums {
    private Enums() {}

    public enum Role { ACCOUNTANT, APPROVER, ADMIN }

    public enum SourceKind { CARRIER, MARKETPLACE, BANK, EWALLET }

    public enum SourceStatus { ACTIVE, DRAFT }

    public enum AmountMode { NET, GROSS }

    public enum FileStatus { PROCESSING, COMPLETED, COMPLETED_WITH_ERRORS, FAILED, DUPLICATE }

    public enum LineStatus { NEW, MATCHED, EXCEPTION, DUPLICATE }

    public enum MatchMethod {
        EXACT, AMOUNT_DATE, MANY_TO_ONE, MANUAL, AI_CONFIRMED;

        public boolean automatic() {
            return this == EXACT || this == AMOUNT_DATE || this == MANY_TO_ONE;
        }
    }

    public enum ExceptionType { UNMATCHED_PAYMENT, UNPAID_ORDER, AMOUNT_DIFF, FEE_DIFF, DUPLICATE }

    public enum ExceptionStatus {
        OPEN, IN_REVIEW, WRITE_OFF_PENDING, RESOLVED, WRITTEN_OFF;

        public boolean isOpen() {
            return this == OPEN || this == IN_REVIEW;
        }

        public boolean isClosed() {
            return this == RESOLVED || this == WRITTEN_OFF;
        }
    }

    public enum ActionKind {
        CREATED, AI_SUGGESTED, ASSIGNED, COMMENT, MATCHED, CLAIM_SENT,
        WRITE_OFF_REQUESTED, APPROVED, REJECTED, MARKED_DUPLICATE
    }

    public enum FeeScheduleStatus { PROPOSED, APPROVED, REJECTED }

    public enum OrderStatus { NEW, SHIPPING, DELIVERED, CANCELLED, RETURNED }

    public enum Channel { WEBSITE, SHOPEE, TIKTOK }
}
