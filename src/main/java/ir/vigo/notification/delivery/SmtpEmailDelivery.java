package ir.vigo.notification.delivery;

import java.time.Clock;

import ir.vigo.notification.messaging.model.NotificationRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

final class SmtpEmailDelivery implements EmailDelivery {
    private static final Logger log = LoggerFactory.getLogger(SmtpEmailDelivery.class);
    private final JavaMailSender sender;
    private final String from;
    private final OtpEmailRenderer renderer;
    private final Clock clock;

    SmtpEmailDelivery(JavaMailSender sender, String from, OtpEmailRenderer renderer, Clock clock) {
        this.sender = sender;
        this.from = from;
        this.renderer = renderer;
        this.clock = clock;
    }

    @Override
    public void deliver(NotificationRequested request) {
        try {
            var content = renderer.render(request);
            var message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(request.destination());
            message.setSubject(content.subject());
            message.setText(content.body());
            // Last application-side check, after rendering and immediately before SMTP handoff.
            if (!clock.instant().isBefore(request.expiresAt())) {
                log.info("notification email outcome=REJECTED reason=EXPIRED");
                return;
            }
            sender.send(message);
            log.info("notification email outcome=SENT");
        } catch (RuntimeException ignored) {
            // Mail exceptions (including nested/suppressed causes) may contain credentials or content.
            throw new IllegalStateException("SMTP email delivery failed");
        }
    }
}
