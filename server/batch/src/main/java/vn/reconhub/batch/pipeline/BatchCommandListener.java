package vn.reconhub.batch.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;
import vn.reconhub.core.messaging.Messages;
import vn.reconhub.core.messaging.Messages.BatchCommand;

/**
 * Commands from the API. Commands are safe to repeat (an INGEST for a file that already ran is ignored, a RESTART of a
 * finished run does nothing). Rejected commands are logged; other errors (database down) roll the message back for redelivery.
 */
@Component
public class BatchCommandListener {
    private static final Logger log = LoggerFactory.getLogger(BatchCommandListener.class);

    private final ReconPipeline pipeline;

    public BatchCommandListener(ReconPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @JmsListener(destination = Messages.BATCH_COMMANDS)
    public void onCommand(BatchCommand command) {
        log.info("Command {} from {}", command.kind(), command.requestedBy());
        try {
            pipeline.exclusive(() -> {
                switch (command.kind()) {
                    case "INGEST" -> pipeline.ingestRegistered(command.fileId());
                    case "RESTART" -> pipeline.restart(command.executionId());
                    case "RUN" -> pipeline.runNow(command.job(), command.requestedBy());
                    case "REVALIDATE_FEES" -> pipeline.feeRevalidation(Long.parseLong(command.params().get("scheduleId")), command.requestedBy());
                    default -> log.warn("Unknown command {}", command.kind());
                }
                return null;
            });
        } catch (IllegalArgumentException | IllegalStateException e) {
            // A command that cannot run (unknown file, job already complete) will not succeed on redelivery either.
            log.error("Command {} from {} rejected: {}", command.kind(), command.requestedBy(), e.getMessage());
        }
    }
}
