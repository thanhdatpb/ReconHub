package vn.reconhub.core.messaging;

import java.util.Map;

/** JMS queue names and message bodies exchanged between api and batch (sent as JSON). */
public final class Messages {
    public static final String BATCH_COMMANDS = "reconhub.batch.commands";
    public static final String MAIL_OUTBOX = "reconhub.mail.outbox";

    private Messages() {}

    /** Ask the batch app to load a registered file, restart an execution, or run a job now. */
    public record BatchCommand(String kind, Long fileId, Long executionId, String job, Map<String, String> params, String requestedBy) {
        public static BatchCommand ingest(long fileId, String by) {
            return new BatchCommand("INGEST", fileId, null, null, Map.of(), by);
        }

        public static BatchCommand restart(long executionId, String by) {
            return new BatchCommand("RESTART", null, executionId, null, Map.of(), by);
        }

        public static BatchCommand run(String job, String by) {
            return new BatchCommand("RUN", null, null, job, Map.of(), by);
        }
    }

    public record MailMessage(String to, String subject, String body, String relatedId) {}
}
