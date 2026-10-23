package vn.reconhub.core.domain;

import jakarta.persistence.*;
import vn.reconhub.core.domain.Enums.ActionKind;

import java.time.Instant;

/** Append-only history entry of an exception; doubles as its audit log. */
@Entity
@Table(name = "exception_actions")
public class ExceptionAction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "exception_id")
    private String exceptionId;
    private Instant at;
    private String actor;
    @Enumerated(EnumType.STRING)
    private ActionKind kind;
    private String note;

    protected ExceptionAction() {}

    public ExceptionAction(String exceptionId, Instant at, String actor, ActionKind kind, String note) {
        this.exceptionId = exceptionId;
        this.at = at;
        this.actor = actor;
        this.kind = kind;
        this.note = note == null ? null : (note.length() > 500 ? note.substring(0, 497) + "..." : note);
    }

    public Long getId() { return id; }
    public String getExceptionId() { return exceptionId; }
    public Instant getAt() { return at; }
    public String getActor() { return actor; }
    public ActionKind getKind() { return kind; }
    public String getNote() { return note; }
}
