package ir.vigo.notification.messaging;

import java.time.Clock;

import ir.vigo.notification.delivery.EmailDelivery;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

public final class NotificationCommandConsumer {
    private static final Logger log = LoggerFactory.getLogger(NotificationCommandConsumer.class);
    private final NotificationCommandParser parser = new NotificationCommandParser();
    private final EmailDelivery delivery;
    private final Clock clock;

    public NotificationCommandConsumer(EmailDelivery delivery, Clock clock) {
        this.delivery = delivery;
        this.clock = clock;
    }

    @KafkaListener(id = "notificationCommands", idIsGroup = false, topics = "notification.commands")
    public void consume(ConsumerRecord<String, String> record) {
        try {
            var command = parser.parse(record.key(), record.value());
            if (!clock.instant().isBefore(command.expiresAt()) || !delivery.deliver(command)) {
                log.atInfo().addKeyValue("event", "notification_command_rejected")
                        .addKeyValue("outcome", "REJECTED").addKeyValue("reason", "EXPIRED")
                        .log("Notification command has expired.");
                return;
            }
            log.atInfo().addKeyValue("event", "notification_command_accepted")
                    .addKeyValue("outcome", "BOUNDARY_ACCEPTED")
                    .log("SMTP relay accepted the notification email.");
        } catch (CommandRejected e) {
            log.atWarn().addKeyValue("event", "notification_command_rejected")
                    .addKeyValue("outcome", "REJECTED").addKeyValue("reason", e.getMessage())
                    .log("Notification command rejected.");
        } catch (RuntimeException e) {
            // Provider/parser exception messages and causes may contain secrets.
            log.atError().addKeyValue("event", "notification_command_failed")
                    .addKeyValue("outcome", "FAILED").addKeyValue("reason", "PROCESSING_FAILURE")
                    .log("Notification command processing failed.");
            throw new IllegalStateException("Notification processing failed; consumer must stop");
        }
    }
}
