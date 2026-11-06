package ir.vigo.notification.messaging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import ir.vigo.notification.delivery.EmailDelivery;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

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
                        "Kafka consumption requires an EmailDelivery implementation; "
                        + "real email delivery is pending SCRUM-112. Disable NOTIFICATION_KAFKA_ENABLED."));
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
                    assertThat(properties.get(ConsumerConfig.GROUP_ID_CONFIG)).isEqualTo("vigo-notification-service-v1");
                    assertThat(properties.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)).isEqualTo("earliest");
                    assertThat(properties.get(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG)).isEqualTo("false");
                    assertThat(context.getBean(CommonErrorHandler.class).seeksAfterHandling()).isTrue();
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
    void failureStopsContainerWithoutLoggingRecordOrCause(CapturedOutput output) {
        var handler = new NotificationKafkaConfiguration().notificationErrorHandler();
        var container = mock(MessageListenerContainer.class);
        var sensitive = new IllegalStateException(NotificationCommandConsumerTests.VALID);
        Throwable thrown = catchThrowable(() -> handler.handleRemaining(sensitive,
                List.of(NotificationCommandConsumerTests.record(NotificationCommandConsumerTests.KEY,
                        NotificationCommandConsumerTests.VALID)), null, container));
        verify(container, timeout(2000)).stopAbnormally(any(Runnable.class));
        assertThat(thrown).isNotNull();
        StringWriter stack = new StringWriter();
        thrown.printStackTrace(new PrintWriter(stack));
        assertThat(stack.toString() + output.getAll())
                .doesNotContain(NotificationCommandConsumerTests.OTP, NotificationCommandConsumerTests.DESTINATION);
    }
}
