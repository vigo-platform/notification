package ir.vigo.notification.messaging;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import ir.vigo.notification.delivery.EmailDelivery;
import ir.vigo.notification.delivery.SmtpFailure;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.mock.MockConsumerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class NotificationKafkaConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withUserConfiguration(NotificationKafkaConfiguration.class);

    @Test
    void disabledRequiresNoBrokerAndRegistersNoConsumer() {
        runner.withPropertyValues("notification.kafka.enabled=false", "spring.kafka.bootstrap-servers=")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(NotificationCommandConsumer.class)
                            .doesNotHaveBean(EmailDelivery.class);
                    assertThat(context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainers()).isEmpty();
                });
    }

    @Test
    void enabledWithoutDeliveryFailsBeforeListenerStartup(CapturedOutput output) {
        runner.withPropertyValues("notification.kafka.enabled=true",
                "spring.kafka.bootstrap-servers=unused.invalid:9092")
                .run(context -> assertThat(context).hasFailed().getFailure().hasRootCauseMessage(
                        "Kafka consumption requires an EmailDelivery implementation"));
        assertThat(output.getAll()).doesNotContain("BOUNDARY_ACCEPTED", "KafkaConsumer",
                NotificationCommandConsumerTests.OTP, NotificationCommandConsumerTests.DESTINATION);
    }

    @Test
    void enabledWithoutAddressFailsClearly() {
        runner.withBean(EmailDelivery.class, () -> mock(EmailDelivery.class))
                .withPropertyValues("notification.kafka.enabled=true", "spring.kafka.bootstrap-servers=")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasRootCauseMessage(
                                "KAFKA_BOOTSTRAP_SERVERS is required when Kafka consumption is enabled"));
    }

    @Test
    void enabledWiringUsesRecordCommitsAndInjectedTestBoundary(CapturedOutput output) {
        EmailDelivery delivery = mock(EmailDelivery.class);
        when(delivery.deliver(any())).thenReturn(true);
        runner.withBean(EmailDelivery.class, () -> delivery)
                .withPropertyValues("notification.kafka.enabled=true",
                "spring.kafka.bootstrap-servers=unused.invalid:9092", "spring.kafka.listener.auto-startup=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(NotificationCommandConsumer.class);
                    var container = context.getBean(KafkaListenerEndpointRegistry.class)
                            .getListenerContainer("notificationCommands");
                    assertThat(container).isNotNull();
                    assertThat(container.isRunning()).isFalse();
                    assertThat(container.getContainerProperties().getTopics()).containsExactly("notification.commands");
                    assertThat(container.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
                    var properties = context.getBean(ConsumerFactory.class).getConfigurationProperties();
                    assertThat(properties.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isEqualTo(false);
                    assertThat(properties.get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG)).isEqualTo(1);
                    assertThat(container.getContainerProperties().getAuthExceptionRetryInterval())
                            .isEqualTo(Duration.ofSeconds(10));
                    assertThat(properties.get(ConsumerConfig.GROUP_ID_CONFIG)).isEqualTo("vigo-notification-service-v1");
                    assertThat(properties.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)).isEqualTo("earliest");
                    assertThat(properties.get(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG)).isEqualTo("false");
                    assertThat(context.getBean(CommonErrorHandler.class).seeksAfterHandling()).isFalse();
                    assertThat(context.getBean(EmailDelivery.class)).isSameAs(delivery);
                    String valid = NotificationCommandConsumerTests.VALID
                            .replace("2026-01-01T12:03:00Z", "2099-01-01T12:03:00Z");
                    context.getBean(NotificationCommandConsumer.class).consume(
                            NotificationCommandConsumerTests.record(NotificationCommandConsumerTests.KEY, valid));
                    verify(delivery).deliver(any());
                    assertThat(output.getAll()).contains("BOUNDARY_ACCEPTED")
                            .doesNotContain(NotificationCommandConsumerTests.OTP, NotificationCommandConsumerTests.DESTINATION);
                });
    }

    @Test
    void retriesAndCommitsInOrderWithoutStoppingTheListener(CapturedOutput output) {
        var partition = new TopicPartition("notification.commands", 0);
        var kafka = new MockConsumer<Object, Object>("earliest");
        var attempts = new CopyOnWriteArrayList<String>();
        List<String> codes = List.of("111111", "222222", "333333", "444444", "555555");
        EmailDelivery delivery = request -> {
            String code = request.otpCode();
            boolean firstAttempt = !attempts.contains(code);
            attempts.add(code);
            if ((code.equals("111111") && firstAttempt) || code.equals("222222")) {
                throw new SmtpFailure(SmtpFailure.Reason.TEMPORARY_REJECTION);
            }
            if (code.equals("333333")) throw new SmtpFailure(SmtpFailure.Reason.PERMANENT_REJECTION);
            if (code.equals("444444")) throw new SmtpFailure(SmtpFailure.Reason.ACCEPTANCE_UNKNOWN);
            return true;
        };
        kafka.schedulePollTask(() -> {
            kafka.updateBeginningOffsets(Map.of(partition, 0L));
            kafka.rebalance(List.of(partition));
            kafka.setPollException(new TimeoutException("sensitive poll detail"));
        });
        kafka.schedulePollTask(() -> {
            for (int i = 0; i < codes.size(); i++) {
                String key = UUID.randomUUID().toString();
                String payload = NotificationCommandConsumerTests.VALID
                        .replace(NotificationCommandConsumerTests.KEY, key)
                        .replace(NotificationCommandConsumerTests.OTP, codes.get(i))
                        .replace("2026-01-01T12:03:00Z", "2099-01-01T12:03:00Z");
                kafka.addRecord(new ConsumerRecord<>(partition.topic(), 0, i, key, payload));
            }
        });
        runner.withBean(ConsumerFactory.class, () -> new MockConsumerFactory<Object, Object>(() -> kafka) {
                    @Override
                    public Map<String, Object> getConfigurationProperties() {
                        return Map.of(ConsumerConfig.GROUP_ID_CONFIG, "notification-test",
                                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
                    }
                })
                .withBean(EmailDelivery.class, () -> delivery)
                .withPropertyValues("notification.kafka.enabled=true", "spring.kafka.bootstrap-servers=unused.invalid:9092")
                .run(context -> {
                    var container = context.getBean(KafkaListenerEndpointRegistry.class)
                            .getListenerContainer("notificationCommands");
                    var health = context.getBean(HealthIndicator.class);
                    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                        var committed = kafka.committed(Set.of(partition)).get(partition);
                        assertThat(committed).isNotNull();
                        assertThat(committed.offset()).isEqualTo(codes.size());
                    });
                    assertThat(attempts).containsExactly("111111", "111111", "222222", "222222",
                            "222222", "333333", "444444", "555555");
                    assertThat(health.health().getStatus().getCode()).isEqualTo("UP");
                    container.stop();
                    assertThat(health.health().getStatus().getCode()).isEqualTo("DOWN");
                    assertThat(output.getAll()).contains("notification_consumer_started", "notification_consumer_stopped");
                    var retries = output.getAll().lines()
                            .filter(line -> line.contains("notification_command_retry")).toList();
                    assertThat(retries).hasSize(3).allSatisfy(line ->
                            assertThat(line).contains("partition", "offset", "failed_attempt", "next_attempt", "backoff_ms"));
                    assertThat(output.getAll()).contains("notification_command_exhausted", "notification_poll_failed")
                            .doesNotContain("sensitive poll detail", NotificationCommandConsumerTests.DESTINATION);
                });
    }
}
