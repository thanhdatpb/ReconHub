package vn.reconhub.batch.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;
import vn.reconhub.core.messaging.Messages;
import vn.reconhub.core.messaging.Messages.BatchCommand;

/**
 * Commands from the API. A command that throws is rolled back to the queue and redelivered, so commands are
 * written to be safe to repeat (an INGEST for a file that already ran is ignored, a RESTART of a finished run does nothing).
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
        pipeline.exclusive(() -> {
            switch (command.kind()) {
                case "INGEST" -> pipeline.ingestRegistered(command.fileId());
                case "RESTART" -> pipeline.restart(command.executionId());
                case "RUN" -> pipeline.runNow(command.job(), command.requestedBy());
                default -> log.warn("Unknown command {}", command.kind());
            }
            return null;
        });
    }
}
