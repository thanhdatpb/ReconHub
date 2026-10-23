package vn.reconhub.core.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import vn.reconhub.core.domain.Enums.ActionKind;
import vn.reconhub.core.domain.Enums.ExceptionStatus;
import vn.reconhub.core.domain.Enums.ExceptionType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Something the matching rules could not settle and a person has to look at. */
@Entity
@Table(name = "recon_exceptions")
public class ReconException {
    @Id
    private String id;
    @Enumerated(EnumType.STRING)
    private ExceptionType type;
    private String sourceCode;
    @Enumerated(EnumType.STRING)
    private ExceptionStatus status;
    private long diffAmount;
    private LocalDate runDate;
    private Instant createdAt;
    private Instant slaDueAt;
    private Instant resolvedAt;
    private String assigneeId;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> meta = new HashMap<>();
    @JdbcTypeCode(SqlTypes.JSON)
    private AiSuggestion ai;
    @JdbcTypeCode(SqlTypes.JSON)
    private WriteOff writeOff;
    /** Optimistic lock: two people resolving the same exception cannot both win. */
    @Version
    private int version;

    @ManyToMany
    @JoinTable(name = "exception_orders", joinColumns = @JoinColumn(name = "exception_id"),
            inverseJoinColumns = @JoinColumn(name = "order_code"))
    private List<Order> orders = new ArrayList<>();

    @ManyToMany
    @JoinTable(name = "exception_payments", joinColumns = @JoinColumn(name = "exception_id"),
            inverseJoinColumns = @JoinColumn(name = "payment_line_id"))
    @OrderColumn(name = "position")
    private List<PaymentLine> payments = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "exception_id")
    @OrderBy("at ASC, id ASC")
    private List<ExceptionAction> actions = new ArrayList<>();

    protected ReconException() {}

    public ReconException(String id, ExceptionType type, String sourceCode, long diffAmount, LocalDate runDate,
                          Instant createdAt, Instant slaDueAt) {
        this.id = id;
        this.type = type;
        this.sourceCode = sourceCode;
        this.diffAmount = diffAmount;
        this.runDate = runDate;
        this.createdAt = createdAt;
        this.slaDueAt = slaDueAt;
        this.status = ExceptionStatus.OPEN;
    }

    public void log(Instant at, String actor, ActionKind kind, String note) {
        actions.add(new ExceptionAction(id, at, actor, kind, note));
    }

    public void close(ExceptionStatus closedStatus, Instant at) {
        this.status = closedStatus;
        this.resolvedAt = at;
    }

    public String getId() { return id; }
    public ExceptionType getType() { return type; }
    public String getSourceCode() { return sourceCode; }
    public ExceptionStatus getStatus() { return status; }
    public void setStatus(ExceptionStatus status) { this.status = status; }
    public long getDiffAmount() { return diffAmount; }
    public LocalDate getRunDate() { return runDate; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSlaDueAt() { return slaDueAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public String getAssigneeId() { return assigneeId; }
    public void setAssigneeId(String assigneeId) { this.assigneeId = assigneeId; }
    public Map<String, Object> getMeta() { return meta; }
    public AiSuggestion getAi() { return ai; }
    public void setAi(AiSuggestion ai) { this.ai = ai; }
    public WriteOff getWriteOff() { return writeOff; }
    public void setWriteOff(WriteOff writeOff) { this.writeOff = writeOff; }
    public int getVersion() { return version; }
    public List<Order> getOrders() { return orders; }
    public List<PaymentLine> getPayments() { return payments; }
    public List<ExceptionAction> getActions() { return actions; }
}
