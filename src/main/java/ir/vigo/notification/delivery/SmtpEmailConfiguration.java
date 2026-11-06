package ir.vigo.notification.delivery;

import java.time.Clock;

import jakarta.mail.internet.InternetAddress;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "notification.email.enabled", havingValue = "true")
public class SmtpEmailConfiguration {
    @Bean
    EmailDelivery emailDelivery(Environment environment, ObjectProvider<JavaMailSender> senders) {
        required(environment, "spring.mail.host", "SMTP_HOST");
        positiveNumber(environment, "spring.mail.port", "SMTP_PORT", 65535);
        String from = required(environment, "notification.email.from-address", "SMTP_FROM_ADDRESS");
        try {
            InternetAddress address = new InternetAddress(from, true);
            address.validate();
            if (!from.equals(address.getAddress()) || !from.contains("@")
                    || from.contains("\r") || from.contains("\n")) {
                throw new IllegalArgumentException();
            }
        } catch (Exception ignored) {
            throw new IllegalStateException("SMTP_FROM_ADDRESS must be a valid mailbox");
        }
        if (Boolean.parseBoolean(environment.getProperty("spring.mail.properties[mail.smtp.auth]", "true"))) {
            required(environment, "spring.mail.username", "SMTP_USERNAME");
            required(environment, "spring.mail.password", "SMTP_PASSWORD");
        }
        positiveNumber(environment, "spring.mail.properties[mail.smtp.connectiontimeout]",
                "SMTP_CONNECTION_TIMEOUT_MS", Integer.MAX_VALUE);
        positiveNumber(environment, "spring.mail.properties[mail.smtp.timeout]",
                "SMTP_READ_TIMEOUT_MS", Integer.MAX_VALUE);
        positiveNumber(environment, "spring.mail.properties[mail.smtp.writetimeout]",
                "SMTP_WRITE_TIMEOUT_MS", Integer.MAX_VALUE);
        return new SmtpEmailDelivery(senders.getObject(), from, new OtpEmailRenderer(), Clock.systemUTC());
    }

    private static String required(Environment environment, String property, String variable) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(variable + " is required when email delivery is enabled");
        }
        return value;
    }

    private static void positiveNumber(Environment environment, String property, String variable, int maximum) {
        String value = required(environment, property, variable);
        try {
            int number = Integer.parseInt(value);
            if (number > 0 && number <= maximum) {
                return;
            }
        } catch (NumberFormatException ignored) {
            // Never include the supplied value or original exception in diagnostics.
        }
        throw new IllegalStateException(variable + " must be a positive integer within its supported range");
    }
}
