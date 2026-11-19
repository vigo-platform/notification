package ir.vigo.notification.inapp.rest;

import java.net.URI;
import java.time.Clock;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import ir.vigo.notification.inapp.JdbcInAppNotifications;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/** Opt-in JDBC runtime; no global datasource/Flyway auto-configuration or JPA. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "notification.in-app.enabled", havingValue = "true")
public class NotificationRestRuntime {
    @Bean Clock notificationClock() { return Clock.systemUTC(); }

    @Bean JwtNotificationIdentity notificationIdentity(Environment environment, Clock clock) {
        try { return JwtNotificationIdentity.fromEnvironment(environment, clock); }
        catch (RuntimeException invalid) { throw new IllegalStateException("Invalid IN_APP JWT configuration"); }
    }

    @Bean Settings notificationDatabaseSettings(Environment environment) {
        return new Settings(required(environment, "NOTIFICATION_DB_URL"),
                required(environment, "NOTIFICATION_DB_USERNAME"), required(environment, "NOTIFICATION_DB_PASSWORD"),
                required(environment, "NOTIFICATION_DB_SCHEMA"));
    }

    @Bean(destroyMethod = "close")
    HikariDataSource notificationDataSource(Settings settings, JwtNotificationIdentity identity) {
        // Identity configuration must validate before any database connection or DDL.
        var config = new HikariConfig();
        config.setPoolName("notification-in-app");
        config.setJdbcUrl(settings.url);
        config.setUsername(settings.username);
        config.setPassword(settings.password);
        config.setSchema(settings.schema);
        config.setAutoCommit(true);
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5000);
        config.setValidationTimeout(2000);
        config.setInitializationFailTimeout(-1);
        config.addDataSourceProperty("connectTimeout", "5");
        config.addDataSourceProperty("socketTimeout", "10");
        config.addDataSourceProperty("options", "-c statement_timeout=10000 -c lock_timeout=5000");
        return new HikariDataSource(config);
    }

    @Bean Flyway notificationFlyway(DataSource dataSource, Settings settings) {
        var flyway = Flyway.configure().dataSource(dataSource)
                .locations("classpath:inapp/postgresql")
                .schemas(settings.schema).defaultSchema(settings.schema).createSchemas(false)
                .baselineOnMigrate(false).cleanDisabled(true).validateOnMigrate(true)
                .validateMigrationNaming(true).failOnMissingLocations(true).load();
        try { flyway.migrate(); }
        catch (RuntimeException failure) { throw new IllegalStateException("IN_APP database migration failed"); }
        return flyway;
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

    private static String required(Environment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required for IN_APP");
        return value;
    }

    static final class Settings {
        final String url;
        final String username;
        final String password;
        final String schema;

        Settings(String url, String username, String password, String schema) {
            try {
                if (!url.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException();
                URI address = URI.create(url.substring(5));
                if (address.getHost() == null || address.getUserInfo() != null || address.getFragment() != null
                        || address.getPath() == null || !address.getPath().matches("/[A-Za-z0-9_-]+")) {
                    throw new IllegalArgumentException();
                }
                boolean loopback = java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(address.getHost());
                var query = new java.util.HashMap<String, String>();
                if (address.getRawQuery() != null) {
                    for (String part : address.getRawQuery().split("&", -1)) {
                        String[] pair = part.split("=", 2);
                        if (pair.length != 2 || !java.util.Set.of("sslmode", "sslrootcert").contains(pair[0])
                                || query.put(pair[0], pair[1]) != null) throw new IllegalArgumentException();
                    }
                }
                if (!"verify-full".equals(query.get("sslmode"))
                        && !(loopback && "disable".equals(query.get("sslmode")))) throw new IllegalArgumentException();
            } catch (RuntimeException invalid) { throw new IllegalStateException("Invalid IN_APP PostgreSQL URL or TLS configuration"); }
            if (!schema.matches("[a-z][a-z0-9_]{0,62}") || schema.startsWith("pg_") || schema.equals("information_schema")) {
                throw new IllegalStateException("IN_APP schema must be an explicit non-system PostgreSQL schema name");
            }
            this.url = url;
            this.username = username;
            this.password = password;
            this.schema = schema;
        }

        @Override public String toString() { return "NotificationDatabaseSettings[redacted]"; }
    }
}
