package vn.reconhub.core.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends JMS messages without ever holding up a web request: a broker that blocks producers (for example when its
 * disk is nearly full) or is down makes {@link #send} return false after a few seconds instead of hanging. The
 * message is still delivered once the broker accepts it.
 */
@Component
public class JmsOutbox {
    private static final Logger log = LoggerFactory.getLogger(JmsOutbox.class);
    private static final long WAIT_SECONDS = 8;

    private final JmsTemplate jms;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jms-outbox");
        t.setDaemon(true);
        return t;
    });

    public JmsOutbox(JmsTemplate jms) {
        this.jms = jms;
    }

    /** Returns true when the broker accepted the message within a few seconds. */
    public boolean send(String destination, Object payload) {
        Future<?> f = sender.submit(() -> jms.convertAndSend(destination, payload));
        try {
            f.get(WAIT_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (TimeoutException e) {
            log.warn("Broker did not accept a message for {} within {} s; it stays queued in this process", destination, WAIT_SECONDS);
            return false;
        } catch (ExecutionException e) {
            log.warn("Could not send to {}: {}", destination, e.getCause().getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Sends only if the surrounding transaction commits, so a rolled-back action never e-mails anyone. */
    public void sendAfterCommit(String destination, Object payload) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            send(destination, payload);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sender.submit(() -> {
                    try {
                        jms.convertAndSend(destination, payload);
                    } catch (RuntimeException e) {
                        log.warn("Could not send to {}: {}", destination, e.getMessage());
                    }
                });
            }
        });
    }
}
