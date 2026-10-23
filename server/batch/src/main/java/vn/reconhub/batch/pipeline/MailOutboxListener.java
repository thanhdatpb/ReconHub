package vn.reconhub.batch.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import vn.reconhub.core.config.ReconProperties;
import vn.reconhub.core.messaging.Messages;

/** Sends the e-mails the API queued (claims to partners). If SMTP is down the message stays on the queue and is retried. */
@Component
public class MailOutboxListener {
    private static final Logger log = LoggerFactory.getLogger(MailOutboxListener.class);

    private final JavaMailSender mail;
    private final ReconProperties props;

    public MailOutboxListener(JavaMailSender mail, ReconProperties props) {
        this.mail = mail;
        this.props = props;
    }

    @JmsListener(destination = Messages.MAIL_OUTBOX)
    public void onMail(Messages.MailMessage m) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(props.getMailFrom());
        msg.setTo(m.to());
        msg.setSubject(m.subject());
        msg.setText(m.body());
        mail.send(msg);
        log.info("Sent mail for {} to {}", m.relatedId(), m.to());
    }
}
