package vn.reconhub.api.web;

import vn.reconhub.core.domain.AppUser;
import vn.reconhub.core.domain.ExceptionAction;
import vn.reconhub.core.domain.FeeRule;
import vn.reconhub.core.domain.FileError;
import vn.reconhub.core.domain.ImportFile;
import vn.reconhub.core.domain.MappingRow;
import vn.reconhub.core.domain.Order;
import vn.reconhub.core.domain.PaymentLine;
import vn.reconhub.core.domain.Source;
import vn.reconhub.core.domain.WriteOff;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** JSON shapes of the REST API. They mirror web/src/data/types.ts field for field. */
public final class Views {
    private Views() {}

    public record UserView(String id, String name, String role, String title) {}

    public record OrderView(String code, String channel, String customer, String phone, long amount, long codAmount, String payVia,
                            String trackingCode, Instant createdAt, Instant deliveredAt, String status) {}

    public record PaymentView(String id, String source, String externalRef, String description, long amount, long fee,
                              Instant txnDate, String fileName) {}

    public record ActionView(Instant at, String actor, String kind, String note) {}

    public record ExceptionView(String id, String type, String source, String status, long diffAmount, Instant createdAt,
                                Instant slaDueAt, String assigneeId, List<OrderView> orders, List<PaymentView> payments,
                                Map<String, Object> meta, Object ai, List<ActionView> actions, WriteOff writeOff, int version) {}

    public record FileView(String id, String source, String fileName, Instant receivedAt, String status, int totalRows, int errorRows,
                           long amountTotal, String checksum, Long jobExecutionId, String message, String uploadedBy) {}

    public record FileErrorView(int row, String column, String value, String reason) {}

    public record SourceView(String code, String name, String kind, String status, String channel, String filePattern, String schedule,
                             Integer expectedHour, String encoding, String dateFormat, List<String> headers, List<List<String>> sampleRows,
                             List<MappingRow> mapping, List<FeeRule> feeRules, Instant updatedAt, String updatedBy) {}

    public record ReconDayView(LocalDate date, String source, long totalLines, long autoMatched, long manualMatched, long exceptions,
                               long amountIn, long feeTotal) {}

    public record MatchGroupView(String id, String method, List<String> orderCodes, String paymentRef, long amount, long fee, double confidence) {}

    public record AgingView(String source, List<Long> buckets) {}

    public record JournalView(LocalDate date, String voucher, String description, String debit, String credit, long amount, String source) {}

    public record StepView(String name, String status, long read, long write, long skip, long commits, long durationMs, String exitMessage) {}

    /** The file that loaded after this failed one: same source and business date, received later. */
    public record Superseded(String fileId, String fileName, Instant receivedAt) {}

    /** Someone marked the failed run as handled. {@code by} is the user id, {@code byName} the full name. */
    public record Acknowledgement(String by, String byName, Instant at, String note) {}

    /** {@code restartedAs}, {@code supersededBy} and {@code acknowledged} are only set on failed or stopped runs. */
    public record JobView(long id, String job, Map<String, String> params, String status, Instant startTime, Instant endTime,
                          List<StepView> steps, String exitMessage, Long restartedAs, Superseded supersededBy,
                          Acknowledgement acknowledged) {
        public static boolean failedOrStopped(String status) {
            return "FAILED".equals(status) || "STOPPED".equals(status);
        }

        /** Failed or stopped, and nobody has dealt with it yet: not restarted, no replacement file, not acknowledged. */
        public boolean needsAction() {
            return failedOrStopped(status) && restartedAs == null && supersededBy == null && acknowledged == null;
        }
    }

    /** One tile of "today's file arrivals": received | late | waiting | due | none. */
    public record Arrival(String source, String state, Instant at, Long rows, Long minutes, String expected) {}

    public static UserView user(AppUser u) {
        return new UserView(u.getId(), u.getFullName(), u.getRole().name(), u.getTitle());
    }

    public static OrderView order(Order o) {
        return new OrderView(o.getCode(), o.getChannel().name(), o.getCustomerName(), o.getPhone(), o.getAmount(), o.getCodAmount(),
                o.getPayVia(), o.getTrackingCode(), o.getCreatedAt(), o.getDeliveredAt(), o.getStatus().name());
    }

    public static PaymentView payment(PaymentLine p, String fileName) {
        return new PaymentView(String.valueOf(p.getId()), p.getSourceCode(), p.getExternalRef(), p.getDescription() == null ? "" : p.getDescription(),
                p.getAmount(), p.getFee(), p.getTxnAt(), fileName);
    }

    public static ActionView action(ExceptionAction a) {
        return new ActionView(a.getAt(), a.getActor(), a.getKind().name(), a.getNote());
    }

    public static FileView file(ImportFile f) {
        return new FileView(String.valueOf(f.getId()), f.getSourceCode(), f.getFileName(), f.getReceivedAt(), f.getStatus().name(),
                f.getTotalRows(), f.getErrorRows(), f.getAmountTotal(), f.getChecksum(), f.getJobExecutionId(), f.getMessage(), f.getUploadedBy());
    }

    public static FileErrorView fileError(FileError e) {
        return new FileErrorView(e.getRowNo(), e.getColumnName() == null ? "" : e.getColumnName(), e.getValue() == null ? "" : e.getValue(), e.getReason());
    }

    public static SourceView source(Source s) {
        return new SourceView(s.getCode(), s.getName(), s.getKind().name(), s.getStatus().name(), s.getChannel(), s.getFilePattern(),
                s.getSchedule(), s.getExpectedHour(), s.getEncoding(), s.getDateFormat(), s.getHeaders(), s.getSampleRows(), s.getMapping(),
                s.getFeeRules(), s.getUpdatedAt(), s.getUpdatedBy());
    }
}
