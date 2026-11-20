package ir.vigo.notification.inapp.rest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DisposablePostgresFixtureTests {
    private static final String DATABASE = "notification_disposable_" + "a".repeat(32);
    private static final String IDENTITY = "b".repeat(32);

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {"wrong", "vigo-notification-disposable:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void missingOrWrongMarkerRejectsDestructiveSetup(String marker) throws Exception {
        var connection = connection(DATABASE, "notification_test", marker);
        assertThatThrownBy(() -> {
            DisposablePostgresFixture.requireIdentity(connection, DATABASE, IDENTITY);
            connection.createStatement().execute("DROP SCHEMA should_never_execute CASCADE");
        }).isInstanceOf(java.sql.SQLException.class).hasMessageContaining("refusing database mutation");
        verify(connection, never()).createStatement();
    }

    @Test void rejectsDeploymentDatabaseEvenWithMatchingMarker() throws Exception {
        var connection = connection("vigo_notification_service", "notification_test", "vigo-notification-disposable:" + IDENTITY);
        assertThatThrownBy(() -> DisposablePostgresFixture.requireIdentity(connection, "vigo_notification_service", IDENTITY))
                .isInstanceOf(java.sql.SQLException.class);
        verify(connection, never()).createStatement();
    }

    @Test void rejectsDifferentDatabaseOrRole() throws Exception {
        for (var connection : java.util.List.of(
                connection("notification_disposable_" + "c".repeat(32), "notification_test", "vigo-notification-disposable:" + IDENTITY),
                connection(DATABASE, "deployed_role", "vigo-notification-disposable:" + IDENTITY))) {
            assertThatThrownBy(() -> DisposablePostgresFixture.requireIdentity(connection, DATABASE, IDENTITY))
                    .isInstanceOf(java.sql.SQLException.class);
            verify(connection, never()).createStatement();
        }
    }

    @Test void acceptsOnlyExactExternalIdentityUsingReadOnlyQuery() throws Exception {
        var connection = connection(DATABASE, "notification_test", "vigo-notification-disposable:" + IDENTITY);
        DisposablePostgresFixture.requireIdentity(connection, DATABASE, IDENTITY);
        verify(connection, never()).createStatement();
    }

    private Connection connection(String database, String role, String marker) throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString(1)).thenReturn(database);
        when(rows.getString(2)).thenReturn(role);
        when(rows.getString(3)).thenReturn(marker);
        return connection;
    }
}
