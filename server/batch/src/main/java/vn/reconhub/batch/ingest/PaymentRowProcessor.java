package vn.reconhub.batch.ingest;

import org.springframework.batch.item.ItemProcessor;
import vn.reconhub.core.domain.Enums.AmountMode;
import vn.reconhub.core.domain.Enums.SourceKind;
import vn.reconhub.core.domain.PaymentLine;
import vn.reconhub.core.domain.Source;
import vn.reconhub.core.util.Money;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Applies the source's column mapping to a raw row. Returns null (filtered, not an error) for rows that are not
 * money received, such as bank debits; throws {@link RowException} (skipped and recorded) for rows that are wrong.
 */
public class PaymentRowProcessor implements ItemProcessor<RawRow, PaymentLine> {
    private final Source source;
    private final long fileId;
    private final ZoneId zone;
    private final DateTimeFormatter dateFormat;
    private final boolean hasTime;

    public PaymentRowProcessor(Source source, long fileId, ZoneId zone) {
        this.source = source;
        this.fileId = fileId;
        this.zone = zone;
        this.dateFormat = DateTimeFormatter.ofPattern(source.getDateFormat());
        this.hasTime = source.getDateFormat().contains("H");
    }

    @Override
    public PaymentLine process(RawRow row) {
        String refCol = column("externalRef");
        String amountCol = column("amount");
        String dateCol = column("txnDate");
        String ref = row.values().getOrDefault(refCol, "");
        String rawAmount = row.values().getOrDefault(amountCol, "");

        if (source.getKind() == SourceKind.BANK && rawAmount.isBlank()) {
            return null; // a debit line (money going out) - not part of reconciliation
        }
        if (ref.isBlank()) throw new RowException(row.rowNo(), refCol, ref, "Thiếu mã tham chiếu");

        long amount;
        try {
            amount = Money.parse(rawAmount);
        } catch (IllegalArgumentException e) {
            throw new RowException(row.rowNo(), amountCol, rawAmount, "Số tiền không hợp lệ");
        }
        if (amount <= 0) throw new RowException(row.rowNo(), amountCol, rawAmount, "Số tiền phải lớn hơn 0");

        long fee = 0;
        String feeCol = source.columnFor("fee").orElse(null);
        if (feeCol != null) {
            String rawFee = row.values().getOrDefault(feeCol, "");
            if (!rawFee.isBlank()) {
                try {
                    fee = Money.parse(rawFee);
                } catch (IllegalArgumentException e) {
                    throw new RowException(row.rowNo(), feeCol, rawFee, "Phí không hợp lệ");
                }
            }
        }
        if (source.getAmountMode() == AmountMode.GROSS) amount -= fee;

        String rawDate = row.values().getOrDefault(dateCol, "");
        Instant txnAt;
        try {
            txnAt = hasTime ? LocalDateTime.parse(rawDate, dateFormat).atZone(zone).toInstant()
                    : LocalDate.parse(rawDate, dateFormat).atStartOfDay(zone).toInstant();
        } catch (DateTimeParseException e) {
            throw new RowException(row.rowNo(), dateCol, rawDate, "Không đọc được ngày theo mẫu " + source.getDateFormat());
        }

        return new PaymentLine(source.getCode(), fileId, row.rowNo(), ref,
                blankToNull(value(row, "orderCode")), blankToNull(value(row, "trackingCode")), blankToNull(value(row, "description")),
                amount, fee, txnAt);
    }

    private String column(String field) {
        return source.columnFor(field).orElseThrow(() -> new IllegalStateException("Nguồn " + source.getCode() + " chưa ánh xạ trường " + field));
    }

    private String value(RawRow row, String field) {
        return source.columnFor(field).map(c -> row.values().getOrDefault(c, "")).orElse(null);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
