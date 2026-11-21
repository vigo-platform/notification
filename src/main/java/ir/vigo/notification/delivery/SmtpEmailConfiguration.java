package ir.vigo.notification.delivery;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${notification.kafka.enabled:false} or ${notification.kafka.user-events.enabled:false}")
@EnableConfigurationProperties(SmtpProperties.class)
class SmtpEmailConfiguration {
    @Bean
    SmtpEmailDelivery smtpEmailDelivery(SmtpProperties properties) {
        return new SmtpEmailDelivery(properties, Clock.systemUTC());
    }
}
