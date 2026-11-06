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
            if (!clock.instant().isBefore(command.expiresAt())) {
                log.info("notification outcome=REJECTED reason=EXPIRED");
                return;
            }
            // Only a successful boundary return permits normal record acknowledgment.
            delivery.deliver(command);
            log.info("notification outcome=BOUNDARY_ACCEPTED");
        } catch (CommandRejected e) {
            log.warn("notification outcome=REJECTED reason={}", e.getMessage());
        } catch (RuntimeException e) {
            // Provider/parser exception messages and causes may contain secrets.
            log.error("notification outcome=FAILED reason=PROCESSING_FAILURE");
            throw new IllegalStateException("Notification processing failed; consumer must stop");
        }
    }
}
