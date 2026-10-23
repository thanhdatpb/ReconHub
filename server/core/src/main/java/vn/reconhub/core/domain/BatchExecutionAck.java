package vn.reconhub.core.domain;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * Someone marked a failed batch run as handled, with a note. The id is the Spring Batch execution id, so a new
 * row is always inserted ({@link #isNew()}): a second acknowledgement fails on the primary key instead of
 * silently replacing the first note.
 */
@Entity
@Table(name = "batch_execution_acks")
public class BatchExecutionAck implements Persistable<Long> {
    @Id
    private Long jobExecutionId;
    private String acknowledgedBy;
    private Instant acknowledgedAt;
    private String note;
    @Transient
    private boolean fresh = true;

    protected BatchExecutionAck() {}

    public BatchExecutionAck(long jobExecutionId, String acknowledgedBy, Instant acknowledgedAt, String note) {
        this.jobExecutionId = jobExecutionId;
        this.acknowledgedBy = acknowledgedBy;
        this.acknowledgedAt = acknowledgedAt;
        this.note = note;
    }

    @PostLoad
    @PostPersist
    void stored() {
        this.fresh = false;
    }

    @Override
    public Long getId() { return jobExecutionId; }

    @Override
    public boolean isNew() { return fresh; }

    public Long getJobExecutionId() { return jobExecutionId; }
    public String getAcknowledgedBy() { return acknowledgedBy; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public String getNote() { return note; }
}
