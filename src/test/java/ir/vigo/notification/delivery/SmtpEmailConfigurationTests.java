package ir.vigo.notification.delivery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class SmtpEmailConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
            .withUserConfiguration(SmtpEmailConfiguration.class);

    @Test
    void defaultsDisableDeliveryAndHaveNoEnvironmentSpecificDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(EmailDelivery.class);
            var env = context.getEnvironment();
            assertThat(env.getProperty("notification.email.enabled")).isEqualTo("false");
            for (String key : new String[]{"spring.mail.host", "spring.mail.port", "spring.mail.username",
                    "spring.mail.password", "notification.email.from-address"}) {
                assertThat(env.getProperty(key)).isEmpty();
            }
            assertThat(env.getProperty("spring.mail.test-connection")).isEqualTo("false");
            assertThat(env.getProperty("management.health.mail.enabled")).isEqualTo("false");
        });
    }

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues("notification.email.enabled=true", "spring.mail.host=smtp.example.invalid",
                "spring.mail.port=587", "notification.email.from-address=sender@example.invalid",
                "spring.mail.username=smtp-user", "spring.mail.password=smtp-password");
    }

    @Test
    void missingConfigurationFailsClearly() {
        runner.withPropertyValues("notification.email.enabled=true").run(context ->
                assertThat(context).hasFailed().getFailure().hasRootCauseMessage(
                        "SMTP_HOST is required when email delivery is enabled"));
    }

    @ParameterizedTest
    @CsvSource({"spring.mail.host,SMTP_HOST", "spring.mail.port,SMTP_PORT",
            "notification.email.from-address,SMTP_FROM_ADDRESS", "spring.mail.username,SMTP_USERNAME",
            "spring.mail.password,SMTP_PASSWORD"})
    void requiredSettingsFailWithoutLeakingSecrets(String property, String variable, CapturedOutput output) {
        enabled().withPropertyValues(property + "=").run(context ->
                assertThat(context).hasFailed().getFailure().hasRootCauseMessage(
                        variable + " is required when email delivery is enabled"));
        assertThat(output.getAll()).doesNotContain("smtp-user", "smtp-password", "sender@example.invalid");
    }

    @Test
    void bootManagesSenderWithBoundedTimeoutsAndUtf8WithoutConnecting() {
        enabled().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(EmailDelivery.class);
            var sender = context.getBean(JavaMailSenderImpl.class);
            assertThat(sender.getHost()).isEqualTo("smtp.example.invalid");
            assertThat(sender.getPort()).isEqualTo(587);
            assertThat(sender.getDefaultEncoding()).isEqualTo("UTF-8");
            assertThat(sender.getJavaMailProperties()).containsEntry("mail.smtp.auth", "true")
                    .containsEntry("mail.smtp.starttls.enable", "true")
                    .containsEntry("mail.smtp.starttls.required", "true")
                    .containsEntry("mail.smtp.connectiontimeout", "5000")
                    .containsEntry("mail.smtp.timeout", "5000")
                    .containsEntry("mail.smtp.writetimeout", "5000").containsEntry("mail.debug", "false");
        });
    }

    @Test
    void unauthenticatedRelayDoesNotRequireCredentials() {
        enabled().withPropertyValues("spring.mail.properties[mail.smtp.auth]=false", "spring.mail.username=",
                "spring.mail.password=").run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(EmailDelivery.class));
    }

    @ParameterizedTest
    @CsvSource({"mail.smtp.connectiontimeout,0", "mail.smtp.timeout,-1", "mail.smtp.writetimeout,0"})
    void rejectsInfiniteTimeouts(String property, String value) {
        enabled().withPropertyValues("spring.mail.properties[" + property + "]=" + value)
                .run(context -> assertThat(context).hasFailed());
    }
}
