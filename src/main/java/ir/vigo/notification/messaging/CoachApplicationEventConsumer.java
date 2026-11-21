package ir.vigo.notification.messaging;

import java.util.function.BiConsumer;

import ir.vigo.notification.delivery.CoachApplicationEmailTemplate;
import ir.vigo.notification.delivery.EmailContent;
import ir.vigo.notification.delivery.SmtpFailure;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.MessageListener;

import static ir.vigo.notification.messaging.NotificationCommandConsumer.forRecord;

final class CoachApplicationEventConsumer implements MessageListener<byte[], byte[]> {
    private static final Logger log = LoggerFactory.getLogger(CoachApplicationEventConsumer.class);
    private final CoachApplicationEventParser parser = new CoachApplicationEventParser();
    private final CoachApplicationEmailInbox inbox;
    private final BiConsumer<String, EmailContent> delivery;
    private final String registrationUrl;

    CoachApplicationEventConsumer(CoachApplicationEmailInbox inbox, BiConsumer<String, EmailContent> delivery,
            String registrationUrl) {
        this.inbox = inbox;
        this.delivery = delivery;
        this.registrationUrl = registrationUrl;
    }

    @Override
    public void onMessage(ConsumerRecord<byte[], byte[]> record) {
        CoachApplicationEventParser.Result result;
        try {
            result = parser.parse(record.key(), record.value());
        } catch (CommandRejected rejected) {
            forRecord(log.atWarn(), record).addKeyValue("reason", rejected.getMessage())
                    .log("Coach application email event rejected.");
            return;
        }
        if (result == null) {
            return;
        }

        var content = CoachApplicationEmailTemplate.render(result.locale(), result.expiresAt(),
                registrationUrl, result.rejectionReason());
        if (!inbox.claim(result.eventId())) {
            forRecord(log.atDebug(), record).addKeyValue("event_id", result.eventId())
                    .log("Coach application email event was already claimed.");
            return;
        }
        try {
            delivery.accept(result.email(), content);
            forRecord(log.atInfo(), record).addKeyValue("event_id", result.eventId())
                    .log("SMTP relay accepted the coach application email.");
        } catch (SmtpFailure failure) {
            forRecord(log.atWarn(), record).addKeyValue("event_id", result.eventId())
                    .addKeyValue("reason", failure.reason().name()).log("Coach application SMTP delivery failed.");
            if (failure.retryable()) {
                inbox.release(result.eventId());
                throw failure;
            }
        } catch (RuntimeException ignored) {
            forRecord(log.atError(), record).addKeyValue("event_id", result.eventId())
                    .log("Coach application email delivery ended with an unknown outcome.");
            // Keep the claim whenever SMTP may have accepted the message.
        }
    }
}
