package ir.vigo.notification.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationDatabaseRuntimeTests {
    @ParameterizedTest @ValueSource(strings = {"", "public,other", "public;drop", "pg_catalog", "information_schema", "Public"})
    void rejectsUnsafeSchema(String schema) {
        assertThatThrownBy(() -> settings("jdbc:postgresql://localhost/test?sslmode=disable", schema))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"jdbc:h2:mem:test", "jdbc:postgresql://remote.invalid/test",
            "jdbc:postgresql://remote.invalid/test?sslmode=disable", "jdbc:postgresql://remote.invalid/test?sslmode=require",
            "jdbc:postgresql://user:secret@remote.invalid/test?sslmode=verify-full",
            "jdbc:postgresql://localhost/test?sslmode=disable&currentSchema=other",
            "jdbc:postgresql://localhost/test?sslmode=disable&password=secret",
            "jdbc:postgresql://localhost/test?sslmode=disable&sslmode=verify-full"})
    void rejectsUnsafeDatabaseConfigurationWithoutEchoingValues(String url) {
        assertThatThrownBy(() -> settings(url, "public")).isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid notification PostgreSQL URL or TLS configuration");
    }

    @Test void acceptsExplicitPublicOrOwnedSchemaAndRemoteVerifiedTls() {
        assertThat(settings("jdbc:postgresql://db.invalid/dedicated?sslmode=verify-full", "public").schema).isEqualTo("public");
        assertThat(settings("jdbc:postgresql://localhost/test?sslmode=disable", "notification").schema).isEqualTo("notification");
    }

    private NotificationDatabaseRuntime.Settings settings(String url, String schema) {
        return new NotificationDatabaseRuntime.Settings(url, "test", "test-only", schema);
    }
}
