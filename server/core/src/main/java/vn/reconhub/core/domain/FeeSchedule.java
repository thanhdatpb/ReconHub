package vn.reconhub.core.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import vn.reconhub.core.domain.Enums.FeeScheduleStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A version of a source's fee schedule, or a proposal for one. Only approved schedules have a version number and are
 * used to compute fees; see {@link vn.reconhub.core.matching.FeeCalculator} for how one is picked by date.
 */
@Entity
@Table(name = "fee_schedules")
public class FeeSchedule {
    /** Version 1 of every source applies "from the start"; the UI shows this date as "Áp dụng từ đầu". */
    public static final LocalDate FROM_THE_START = LocalDate.of(2000, 1, 1);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String sourceCode;
    private Integer versionNo;
    @Enumerated(EnumType.STRING)
    private FeeScheduleStatus status;
    private LocalDate effectiveFrom;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<FeeComponent> components = new ArrayList<>();
    private String note;
    private String fromException;
    private String proposedBy;
    private Instant proposedAt;
    private String decidedBy;
    private Instant decidedAt;
    private String decisionNote;
    @Version
    private int version;

    protected FeeSchedule() {}

    public FeeSchedule(String sourceCode, LocalDate effectiveFrom, List<FeeComponent> components, String note, String fromException,
                       String proposedBy, Instant proposedAt) {
        this.sourceCode = sourceCode;
        this.status = FeeScheduleStatus.PROPOSED;
        this.effectiveFrom = effectiveFrom;
        this.components = new ArrayList<>(components);
        this.note = note;
        this.fromException = fromException;
        this.proposedBy = proposedBy;
        this.proposedAt = proposedAt;
    }

    public void approve(int versionNo, String by, Instant at, String note) {
        this.status = FeeScheduleStatus.APPROVED;
        this.versionNo = versionNo;
        decide(by, at, note);
    }

    public void reject(String by, Instant at, String note) {
        this.status = FeeScheduleStatus.REJECTED;
        decide(by, at, note);
    }

    private void decide(String by, Instant at, String note) {
        this.decidedBy = by;
        this.decidedAt = at;
        this.decisionNote = note;
    }

    public Long getId() { return id; }
    public String getSourceCode() { return sourceCode; }
    public Integer getVersionNo() { return versionNo; }
    public FeeScheduleStatus getStatus() { return status; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public List<FeeComponent> getComponents() { return components; }
    public String getNote() { return note; }
    public String getFromException() { return fromException; }
    public String getProposedBy() { return proposedBy; }
    public Instant getProposedAt() { return proposedAt; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecisionNote() { return decisionNote; }
    public int getVersion() { return version; }
}
