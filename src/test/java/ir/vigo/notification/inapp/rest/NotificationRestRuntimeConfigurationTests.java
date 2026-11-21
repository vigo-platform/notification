package ir.vigo.notification.inapp.rest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class NotificationRestRuntimeConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(NotificationRestRuntime.class);

    @Test void disabledNeedsNoDatabaseOrAuthAndCreatesNoRuntimeBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(javax.sql.DataSource.class)
                    .doesNotHaveBean(org.flywaydb.core.Flyway.class)
                    .doesNotHaveBean(VerifiedNotificationIdentity.class)
                    .doesNotHaveBean(org.springframework.boot.web.servlet.FilterRegistrationBean.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOTIFICATION_DB_URL", "NOTIFICATION_DB_USERNAME", "NOTIFICATION_DB_PASSWORD",
            "NOTIFICATION_DB_SCHEMA", "NOTIFICATION_JWT_JWKS_URL", "NOTIFICATION_JWT_ISSUER", "NOTIFICATION_JWT_AUDIENCE"})
    void enabledRejectsEachMissingRequiredSetting(String missing) {
        var values = new java.util.LinkedHashMap<String, String>();
        values.put("notification.in-app.enabled", "true");
        values.put("NOTIFICATION_DB_URL", "jdbc:postgresql://127.0.0.1:1/disposable?sslmode=disable");
        values.put("NOTIFICATION_DB_USERNAME", "test");
        values.put("NOTIFICATION_DB_PASSWORD", "test-only");
        values.put("NOTIFICATION_DB_SCHEMA", "public");
        values.put("NOTIFICATION_JWT_JWKS_URL", "https://auth.invalid/jwks");
        values.put("NOTIFICATION_JWT_ISSUER", "test-issuer");
        values.put("NOTIFICATION_JWT_AUDIENCE", "test-api");
        values.put(missing, "");
        runner.withPropertyValues(values.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new))
                .run(context -> assertThat(context).hasFailed());
    }
}
