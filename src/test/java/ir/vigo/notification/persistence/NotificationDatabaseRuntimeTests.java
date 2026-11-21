package ir.vigo.notification.persistence;

import ir.vigo.notification.inapp.rest.VerifiedNotificationIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.Driver;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationDatabaseRuntimeTests {
    @ParameterizedTest @ValueSource(strings = {"", "public,other", "public;drop", "pg_catalog", "information_schema", "Public"})
    void rejectsUnsafeSchema(String schema) {
        assertThatThrownBy(() -> settings("jdbc:postgresql://localhost/test?sslmode=disable", schema))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Notification schema must be an explicit non-system PostgreSQL schema name");
    }

    @ParameterizedTest @ValueSource(strings = {"jdbc:h2:mem:test", "jdbc:postgresql:///test", "jdbc:postgresql://postgres/",
            "jdbc:postgresql://user:secret@remote.invalid/test?sslmode=verify-full",
            "jdbc:postgresql://postgres/test#fragment", "jdbc:postgresql://postgres/test?sslmode=invalid",
            "jdbc:postgresql://postgres/test?sslmode=",
            "jdbc:postgresql://localhost/test?sslmode=disable&currentSchema=other",
            "jdbc:postgresql://localhost/test?sslmode=disable&password=secret",
            "jdbc:postgresql://localhost/test?sslmode=disable&sslmode=verify-full"})
    void rejectsUnsafeDatabaseConfigurationWithoutEchoingValues(String url) {
        assertThatThrownBy(() -> settings(url, "public")).isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid notification PostgreSQL URL or TLS configuration");
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            jdbc:postgresql://postgres:5432/vigo_notification_service?sslmode=disable, disable
            jdbc:postgresql://localhost/test?sslmode=disable, disable
            jdbc:postgresql://localhost/test, disable
            jdbc:postgresql://postgres/test, disable
            jdbc:postgresql://db.invalid/dedicated?sslmode=allow, allow
            jdbc:postgresql://db.invalid/dedicated?sslmode=prefer, prefer
            jdbc:postgresql://db.invalid/dedicated?sslmode=require, require
            jdbc:postgresql://db.invalid/dedicated?sslmode=verify-ca, verify-ca
            jdbc:postgresql://db.invalid/dedicated?sslmode=verify-full, verify-full
            jdbc:postgresql://db.invalid/dedicated?sslmode=verify-full&sslrootcert=/etc/postgresql/ca.pem, verify-full
            """)
    void usesExplicitSslModeOrDefaultsToDisabled(String url, String expectedSslMode) {
        var settings = settings(url, "public");
        var identity = new DefaultListableBeanFactory().getBeanProvider(VerifiedNotificationIdentity.class);
        try (var dataSource = new NotificationDatabaseRuntime().notificationDataSource(settings, identity)) {
            assertThat(dataSource.getJdbcUrl()).isEqualTo(url);
            assertThat(dataSource.getSchema()).isEqualTo("public");
            assertThat(Driver.parseURL(dataSource.getJdbcUrl(), dataSource.getDataSourceProperties()))
                    .containsEntry("sslmode", expectedSslMode);
        }
    }

    @Test void acceptsExplicitOwnedSchema() {
        assertThat(settings("jdbc:postgresql://localhost/test?sslmode=disable", "notification").schema).isEqualTo("notification");
    }

    private NotificationDatabaseRuntime.Settings settings(String url, String schema) {
        return new NotificationDatabaseRuntime.Settings(url, "test", "test-only", schema);
    }
}
