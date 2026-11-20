package ir.vigo.notification.messaging.v2;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

import ir.vigo.notification.inapp.JdbcInAppNotifications;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "notification.kafka.v2.enabled", havingValue = "true")
class V2KafkaConfiguration {
    static final String TOPIC = "notification.commands.v2";
    private static final Logger log = LoggerFactory.getLogger(V2KafkaConfiguration.class);

    @Bean Settings notificationV2Settings(Environment environment) {
        if (!environment.getProperty("notification.in-app.enabled", Boolean.class, false)) {
            throw new IllegalStateException("Kafka v2 requires the durable IN_APP runtime");
        }
        if (!"isolated-synthetic".equals(environment.getProperty("notification.kafka.v2.mode"))) {
            throw new IllegalStateException("Kafka v2 supports only the isolated-synthetic mode");
        }
        String bootstrap = environment.getProperty("notification.kafka.v2.bootstrap-servers", "");
        if (!bootstrap.matches("127\\.0\\.0\\.1:[1-9][0-9]{0,4}")
                || Integer.parseInt(bootstrap.substring(10)) > 65535) {
            throw new IllegalStateException("Kafka v2 requires one isolated IPv4 loopback broker");
        }
        String database = environment.getProperty("NOTIFICATION_DB_URL", "");
        if (!database.matches("jdbc:postgresql://127\\.0\\.0\\.1:[1-9][0-9]{0,4}"
                + "/notification_disposable_[a-f0-9]{32}\\?sslmode=disable")) {
            throw new IllegalStateException("Kafka v2 requires a disposable loopback PostgreSQL database");
        }
        String group = environment.getProperty("notification.kafka.v2.group-id", "vigo-notification-service-v2");
        if (!group.matches("vigo-notification-service-v2(?:-[a-z0-9-]{1,64})?")
                || group.equals(environment.getProperty("spring.kafka.consumer.group-id"))) {
            throw new IllegalStateException("Kafka v2 requires a separate v2 consumer group");
        }
        return new Settings(bootstrap, group,
                new SyntheticV2Policy(environment.getProperty("notification.kafka.v2.synthetic-user-ids", "")));
    }

    @Bean V2Inbox notificationV2Inbox(Settings settings, DataSource notificationDataSource,
            JdbcInAppNotifications history, Clock notificationClock) {
        // The history bean requires successful Flyway migration before either the worker or Kafka can start.
        return new V2Inbox(notificationDataSource, history, settings.policy, notificationClock);
    }

    @Bean KafkaMessageListenerContainer<byte[], byte[]> notificationV2Container(Settings settings, V2Inbox inbox) {
        var factory = new DefaultKafkaConsumerFactory<byte[], byte[]>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, settings.bootstrap,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class));
        var properties = new ContainerProperties(TOPIC);
        properties.setGroupId(settings.group);
        properties.setAckMode(ContainerProperties.AckMode.RECORD);
        properties.setSyncCommits(true);
        properties.setMissingTopicsFatal(true);
        properties.setMessageListener(listener(settings.policy, inbox));
        var container = new KafkaMessageListenerContainer<>(factory, properties);
        container.setCommonErrorHandler(new StoppingErrorHandler());
        return container;
    }

    static MessageListener<byte[], byte[]> listener(SyntheticV2Policy policy, V2Inbox inbox) {
        var parser = new V2CommandParser();
        return record -> {
            try {
                var command = parser.parse(record.key(), record.value());
                var copy = policy.authorize(command);
                inbox.accept(command, copy);
            } catch (V2CommandParser.Rejected rejected) {
                inbox.reject(record.partition(), record.offset(), rejected.reason);
                log.warn("notification v2 outcome=REJECTED reason={}", rejected.reason);
            }
        };
    }

    record Settings(String bootstrap, String group, SyntheticV2Policy policy) {}

    // Private to this container. No record or exception from the failed operation reaches framework logging.
    static final class StoppingErrorHandler extends CommonContainerStoppingErrorHandler {
        @Override
        public void handleRemaining(Exception exception, List<ConsumerRecord<?, ?>> records,
                Consumer<?, ?> consumer, MessageListenerContainer container) {
            super.handleRemaining(redactedFailure(), List.of(), consumer, container);
        }

        @Override
        public void handleOtherException(Exception exception, Consumer<?, ?> consumer,
                MessageListenerContainer container, boolean batchListener) {
            super.handleOtherException(redactedFailure(), consumer, container, batchListener);
        }

        private static IllegalStateException redactedFailure() {
            log.error("notification v2 outcome=CONSUMER_STOPPED reason=PROCESSING_FAILURE");
            return new IllegalStateException("Kafka v2 consumer stopped after processing failure");
        }
    }
}
