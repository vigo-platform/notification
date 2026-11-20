package ir.vigo.notification.messaging;

import java.time.Clock;

import ir.vigo.notification.delivery.EmailDelivery;
import org.apache.kafka.clients.consumer.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.event.ConsumerStartedEvent;
import org.springframework.kafka.event.ConsumerStoppedEvent;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerUtils;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "notification.kafka.enabled", havingValue = "true")
class NotificationKafkaConfiguration {
    private static final Logger log = LoggerFactory.getLogger(NotificationKafkaConfiguration.class);

    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_MS = 1000;

    @EventListener
    void consumerStarted(ConsumerStartedEvent event) {
        var container = event.getContainer(MessageListenerContainer.class);
        if (NotificationCommandConsumer.LISTENER_ID.equals(container.getListenerId())) {
            log.atInfo().addKeyValue("event", "notification_consumer_started").log("OTP consumer started.");
        }
    }

    @EventListener
    void consumerStopped(ConsumerStoppedEvent event) {
        var container = event.getContainer(MessageListenerContainer.class);
        if (NotificationCommandConsumer.LISTENER_ID.equals(container.getListenerId())) {
            var entry = event.getReason() == ConsumerStoppedEvent.Reason.NORMAL ? log.atInfo() : log.atError();
            entry.addKeyValue("event", "notification_consumer_stopped")
                    .addKeyValue("reason", event.getReason().name()).log("OTP consumer stopped.");
        }
    }

    @Bean
    HealthIndicator notificationConsumerHealthIndicator(KafkaListenerEndpointRegistry registry) {
        return () -> {
            var container = registry.getListenerContainer(NotificationCommandConsumer.LISTENER_ID);
            boolean running = container != null && container.isRunning() && container.isInExpectedState();
            return running ? Health.up().build() : Health.down().build();
        };
    }

    @Bean
    NotificationCommandConsumer notificationCommandConsumer(ObjectProvider<EmailDelivery> deliveries,
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        EmailDelivery delivery = deliveries.getIfAvailable();
        if (delivery == null) {
            throw new IllegalStateException("Kafka consumption requires an EmailDelivery implementation");
        }
        if (bootstrapServers.isBlank()) {
            throw new IllegalStateException("KAFKA_BOOTSTRAP_SERVERS is required when Kafka consumption is enabled");
        }
        return new NotificationCommandConsumer(delivery, Clock.systemUTC());
    }

    @Bean
    CommonErrorHandler notificationErrorHandler() {
        var handler = new DefaultErrorHandler((record, exception) ->
                NotificationCommandConsumer.forRecord(log.atError(), record)
                        .addKeyValue("event", "notification_command_exhausted")
                        .addKeyValue("outcome", "FAILED").addKeyValue("reason", "RETRIES_EXHAUSTED")
                        .log("Notification delivery attempts exhausted."), new FixedBackOff(BACKOFF_MS, MAX_ATTEMPTS - 1)) {
            @Override
            public void handleOtherException(Exception exception, Consumer<?, ?> consumer,
                    MessageListenerContainer container, boolean batchListener) {
                // No record to acknowledge. Let the next poll retry without logging raw Kafka data.
                log.atError().addKeyValue("event", "notification_poll_failed").log("Kafka poll failed.");
                try {
                    ListenerUtils.stoppableSleep(container, 1000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        handler.setRetryListeners((record, exception, attempt) -> {
            if (attempt < MAX_ATTEMPTS) {
                NotificationCommandConsumer.forRecord(log.atWarn(), record)
                        .addKeyValue("event", "notification_command_retry")
                        .addKeyValue("failed_attempt", attempt).addKeyValue("next_attempt", attempt + 1)
                        .addKeyValue("backoff_ms", BACKOFF_MS).log("Retrying notification command.");
            }
        });
        handler.setSeekAfterError(false);
        return handler;
    }
}
