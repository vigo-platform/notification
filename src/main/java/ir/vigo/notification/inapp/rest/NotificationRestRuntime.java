package ir.vigo.notification.inapp.rest;

import java.time.Clock;
import javax.sql.DataSource;
import ir.vigo.notification.inapp.JdbcInAppNotifications;
import ir.vigo.notification.persistence.NotificationDatabaseRuntime;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/** Opt-in JDBC runtime; no global datasource/Flyway auto-configuration or JPA. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "notification.in-app.enabled", havingValue = "true")
@Import(NotificationDatabaseRuntime.class)
public class NotificationRestRuntime {
    @Bean Clock notificationClock() { return Clock.systemUTC(); }

    @Bean JwtNotificationIdentity notificationIdentity(Environment environment, Clock clock) {
        try { return JwtNotificationIdentity.fromEnvironment(environment, clock); }
        catch (RuntimeException invalid) { throw new IllegalStateException("Invalid IN_APP JWT configuration"); }
    }

    @Bean JdbcInAppNotifications inAppNotifications(DataSource dataSource, Clock clock, Flyway notificationFlyway) {
        // The store and controller cannot be created until migration has succeeded.
        return new JdbcInAppNotifications(dataSource, clock);
    }

    @Bean NotificationRestService notificationRestService(JdbcInAppNotifications store, JwtNotificationIdentity identity) {
        return new NotificationRestService(store, identity);
    }

    @Bean FilterRegistrationBean<NotificationBearerFilter> notificationBearerFilter(JwtNotificationIdentity identity) {
        var registration = new FilterRegistrationBean<>(new NotificationBearerFilter(identity));
        registration.setName("notificationBearerFilter");
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
