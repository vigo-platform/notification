package ir.vigo.notification.messaging;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import javax.sql.DataSource;

import ir.vigo.notification.delivery.SmtpEmailDelivery;
import ir.vigo.notification.delivery.SmtpFailure;
import ir.vigo.notification.persistence.NotificationDatabaseRuntime;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.ListenerUtils;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.FixedBackOff;

import static ir.vigo.notification.messaging.NotificationCommandConsumer.forRecord;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "notification.kafka.user-events.enabled", havingValue = "true")
@Import(NotificationDatabaseRuntime.class)
class CoachApplicationKafkaConfiguration {
    static final String TOPIC = "user.events";
    private static final long RETRY_DELAY_MS = 1000;
    private static final Logger log = LoggerFactory.getLogger(CoachApplicationKafkaConfiguration.class);

    @Bean
    CoachApplicationEventConsumer coachApplicationEventConsumer(DataSource notificationDataSource,
            Flyway notificationFlyway, SmtpEmailDelivery smtp,
            @Value("${notification.kafka.user-events.registration-url}") String registrationUrl) {
        // Depend on Flyway so Kafka cannot consume before the inbox table exists.
        return new CoachApplicationEventConsumer(new CoachApplicationEmailInbox(notificationDataSource),
                smtp::deliver, registrationLink(registrationUrl));
    }

    @Bean
    KafkaMessageListenerContainer<byte[], byte[]> coachApplicationEventContainer(
            KafkaProperties kafka, CoachApplicationEventConsumer consumer,
            @Value("${notification.kafka.user-events.group-id}") String group,
            @Value("${notification.kafka.v2.group-id:vigo-notification-service-v2}") String v2Group) {
        var settings = new HashMap<>(kafka.buildConsumerProperties());
        if (!(settings.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG) instanceof List<?> brokers)
                || brokers.isEmpty() || brokers.stream().allMatch(broker -> broker.toString().isBlank())) {
            throw new IllegalStateException("KAFKA_BOOTSTRAP_SERVERS is required for user.events consumption");
        }
        if (group.isBlank() || group.equals(settings.get(ConsumerConfig.GROUP_ID_CONFIG)) || group.equals(v2Group)) {
            throw new IllegalStateException("user.events requires a separate Kafka consumer group");
        }
        settings.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        settings.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        settings.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        settings.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        // Decode explicitly in the listener and keep record values out of framework logs.
        settings.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        settings.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        var factory = new DefaultKafkaConsumerFactory<byte[], byte[]>(settings);
        var properties = new ContainerProperties(TOPIC);
        properties.setGroupId(group);
        properties.setAckMode(ContainerProperties.AckMode.RECORD);
        properties.setSyncCommits(true);
        properties.setAuthExceptionRetryInterval(kafka.getListener().getAuthExceptionRetryInterval());
        properties.setMessageListener(consumer);
        var container = new KafkaMessageListenerContainer<>(factory, properties);
        container.setAutoStartup(kafka.getListener().isAutoStartup());
        container.setCommonErrorHandler(errorHandler());
        return container;
    }

    @Bean
    HealthIndicator coachApplicationEventConsumerHealthIndicator(
            @Qualifier("coachApplicationEventContainer")
            KafkaMessageListenerContainer<byte[], byte[]> coachApplicationEventContainer) {
        return () -> coachApplicationEventContainer.isRunning() && coachApplicationEventContainer.isInExpectedState()
                ? Health.up().build() : Health.down().build();
    }

    static DefaultErrorHandler errorHandler() {
        var handler = new DefaultErrorHandler((record, exception) -> forRecord(log.atError(), record)
                .log("Coach application email delivery attempts exhausted."),
                new FixedBackOff(RETRY_DELAY_MS, FixedBackOff.UNLIMITED_ATTEMPTS)) {
            @Override
            public void handleOtherException(Exception exception, Consumer<?, ?> consumer,
                    MessageListenerContainer container, boolean batchListener) {
                log.error("Kafka poll failed for user.events.");
                try {
                    ListenerUtils.stoppableSleep(container, RETRY_DELAY_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        // Retry transient SMTP failures twice. Keep the offset uncommitted while the inbox is unavailable.
        handler.setBackOffFunction((record, exception) ->
                NestedExceptionUtils.getMostSpecificCause(exception) instanceof SmtpFailure
                        ? new FixedBackOff(RETRY_DELAY_MS, 2) : null);
        handler.setSeekAfterError(false);
        return handler;
    }

    private static String registrationLink(String value) {
        try {
            URI uri = URI.create(value);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null) {
                return uri.toASCIIString();
            }
        } catch (IllegalArgumentException ignored) {
            // Do not echo invalid configuration.
        }
        throw new IllegalStateException("NOTIFICATION_REGISTRATION_URL must be an absolute HTTPS URL");
    }
}
