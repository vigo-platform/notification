package ir.vigo.notification.delivery;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "notification.kafka.enabled", havingValue = "true")
@EnableConfigurationProperties(SmtpProperties.class)
class SmtpEmailConfiguration {
    @Bean
    EmailDelivery smtpEmailDelivery(SmtpProperties properties) {
        return new SmtpEmailDelivery(properties, Clock.systemUTC());
    }
}
