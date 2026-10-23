package vn.reconhub.core.workflow;

/** A rule of the exception workflow was broken; {@code kind} maps to an HTTP status in the API. */
public class WorkflowException extends RuntimeException {
    public enum Kind { NOT_FOUND, CONFLICT, FORBIDDEN, INVALID }

    private final Kind kind;

    public WorkflowException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
