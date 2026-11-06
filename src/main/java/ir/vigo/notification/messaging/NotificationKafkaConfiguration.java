package ir.vigo.notification.messaging;

import java.time.Clock;
import java.util.List;

import ir.vigo.notification.delivery.EmailDelivery;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "notification.kafka.enabled", havingValue = "true")
class NotificationKafkaConfiguration {
    @Bean
    NotificationCommandConsumer notificationCommandConsumer(ObjectProvider<EmailDelivery> deliveries,
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        EmailDelivery delivery = deliveries.getIfAvailable();
        if (delivery == null) {
            throw new IllegalStateException("Kafka consumption requires an EmailDelivery implementation; "
                    + "enable NOTIFICATION_EMAIL_ENABLED with valid SMTP configuration or disable NOTIFICATION_KAFKA_ENABLED.");
        }
        if (bootstrapServers.isBlank()) {
            throw new IllegalStateException("KAFKA_BOOTSTRAP_SERVERS is required when Kafka consumption is enabled");
        }
        return new NotificationCommandConsumer(delivery, Clock.systemUTC());
    }

    @Bean
    CommonErrorHandler notificationErrorHandler() {
        return new CommonContainerStoppingErrorHandler() {
            @Override
            public void handleRemaining(Exception exception, List<ConsumerRecord<?, ?>> records,
                    Consumer<?, ?> consumer, MessageListenerContainer container) {
                // Framework wrappers may retain the raw record or a provider exception.
                super.handleRemaining(safeFailure(), List.of(), consumer, container);
            }

            @Override
            public void handleOtherException(Exception exception, Consumer<?, ?> consumer,
                    MessageListenerContainer container, boolean batchListener) {
                super.handleOtherException(safeFailure(), consumer, container, batchListener);
            }

            private IllegalStateException safeFailure() {
                LoggerFactory.getLogger(NotificationKafkaConfiguration.class)
                        .error("notification outcome=CONSUMER_STOPPED reason=PROCESSING_FAILURE");
                return new IllegalStateException("Notification consumer stopped; operator intervention required");
            }
        };
    }
}
