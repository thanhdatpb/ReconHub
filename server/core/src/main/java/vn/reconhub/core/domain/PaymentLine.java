package vn.reconhub.core.domain;

import jakarta.persistence.*;
import vn.reconhub.core.domain.Enums.LineStatus;

import java.time.Instant;

/** One money movement from a partner file, normalised to the canonical fields. */
@Entity
@Table(name = "payment_lines")
public class PaymentLine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String sourceCode;
    private Long fileId;
    private int rowNo;
    private String externalRef;
    private String orderRef;
    private String trackingCode;
    private String description;
    /** Money actually received, after the partner's fee. */
    private long amount;
    private long fee;
    private Instant txnAt;
    @Enumerated(EnumType.STRING)
    private LineStatus status = LineStatus.NEW;
    private Long matchGroupId;
    private boolean feeChecked;
    private Instant createdAt = Instant.now();

    protected PaymentLine() {}

    public PaymentLine(String sourceCode, Long fileId, int rowNo, String externalRef, String orderRef, String trackingCode,
                       String description, long amount, long fee, Instant txnAt) {
        this.sourceCode = sourceCode;
        this.fileId = fileId;
        this.rowNo = rowNo;
        this.externalRef = externalRef;
        this.orderRef = orderRef;
        this.trackingCode = trackingCode;
        this.description = description;
        this.amount = amount;
        this.fee = fee;
        this.txnAt = txnAt;
    }

    /** Amount before the partner's fee, comparable with the order value. */
    public long gross() {
        return amount + fee;
    }

    public void matched(Long groupId) {
        this.status = LineStatus.MATCHED;
        this.matchGroupId = groupId;
    }

    public Long getId() { return id; }
    public String getSourceCode() { return sourceCode; }
    public Long getFileId() { return fileId; }
    public int getRowNo() { return rowNo; }
    public String getExternalRef() { return externalRef; }
    public String getOrderRef() { return orderRef; }
    public String getTrackingCode() { return trackingCode; }
    public String getDescription() { return description; }
    public long getAmount() { return amount; }
    public long getFee() { return fee; }
    public Instant getTxnAt() { return txnAt; }
    public LineStatus getStatus() { return status; }
    public void setStatus(LineStatus status) { this.status = status; }
    public Long getMatchGroupId() { return matchGroupId; }
    public boolean isFeeChecked() { return feeChecked; }
    public void setFeeChecked(boolean feeChecked) { this.feeChecked = feeChecked; }
}
