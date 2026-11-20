package ir.vigo.notification.messaging;

import java.time.Clock;

import ir.vigo.notification.delivery.EmailDelivery;
import ir.vigo.notification.delivery.SmtpFailure;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.kafka.annotation.KafkaListener;

public final class NotificationCommandConsumer {
    static final String LISTENER_ID = "notificationCommands";
    private static final Logger log = LoggerFactory.getLogger(NotificationCommandConsumer.class);
    private final NotificationCommandParser parser = new NotificationCommandParser();
    private final EmailDelivery delivery;
    private final Clock clock;

    public NotificationCommandConsumer(EmailDelivery delivery, Clock clock) {
        this.delivery = delivery;
        this.clock = clock;
    }

    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = "notification.commands")
    public void consume(ConsumerRecord<String, String> record) {
        try {
            var command = parser.parse(record.key(), record.value());
            if (!clock.instant().isBefore(command.expiresAt()) || !delivery.deliver(command)) {
                forRecord(log.atInfo(), record).addKeyValue("event", "notification_command_rejected")
                        .addKeyValue("outcome", "REJECTED").addKeyValue("reason", "EXPIRED")
                        .log("Notification command has expired.");
                return;
            }
            forRecord(log.atInfo(), record).addKeyValue("event", "notification_command_accepted")
                    .addKeyValue("outcome", "BOUNDARY_ACCEPTED")
                    .log("SMTP relay accepted the notification email.");
        } catch (CommandRejected e) {
            forRecord(log.atWarn(), record).addKeyValue("event", "notification_command_rejected")
                    .addKeyValue("outcome", "REJECTED").addKeyValue("reason", e.getMessage())
                    .log("Notification command rejected.");
        } catch (SmtpFailure failure) {
            forRecord(log.atError(), record).addKeyValue("event", "notification_command_failed")
                    .addKeyValue("outcome", failure.retryable() ? "RETRYABLE_FAILURE" : "FAILED")
                    .addKeyValue("reason", failure.reason().name()).log("SMTP delivery failed.");
            if (failure.retryable()) {
                throw failure;
            }
        } catch (RuntimeException e) {
            // Provider/parser exception messages and causes may contain secrets.
            forRecord(log.atError(), record).addKeyValue("event", "notification_command_failed")
                    .addKeyValue("outcome", "RETRYABLE_FAILURE").addKeyValue("reason", "PROCESSING_FAILURE")
                    .log("Notification command processing failed.");
            throw new IllegalStateException("Notification processing failed");
        }
    }

    static LoggingEventBuilder forRecord(LoggingEventBuilder entry, ConsumerRecord<?, ?> record) {
        return entry.addKeyValue("partition", record.partition()).addKeyValue("offset", record.offset());
    }
}
