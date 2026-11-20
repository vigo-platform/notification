package ir.vigo.notification.inapp;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Internal persistence boundary; intentionally not registered as a Spring bean.
 * Callers must supply an owner derived from a verified principal for reads/updates,
 * or an authorized internal recipient for creation. This class does not authenticate.
 * The default methods use dedicated auto-commit connections.
 */
public final class JdbcInAppNotifications {
    private final DataSource dataSource;
    private final Clock clock;

    public JdbcInAppNotifications(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.clock = Objects.requireNonNull(clock);
    }

    /** No public creation API or EMAIL-command mapping is implied by this method. */
    public InAppNotification create(String recipient, String title, String body) {
        try (var connection = connection()) {
            return insert(connection, recipient, title, body);
        } catch (SQLException failure) {
            throw storageFailure();
        }
    }

    /** Allows delivery bookkeeping and history to commit in one transaction owned by the caller. */
    public InAppNotification createInTransaction(Connection connection, String recipient, String title, String body)
            throws SQLException {
        if (connection.getAutoCommit()) throw new SQLException("IN_APP write requires an active transaction");
        return insert(connection, recipient, title, body);
    }

    private InAppNotification insert(Connection connection, String recipient, String title, String body)
            throws SQLException {
        var notification = new InAppNotification(UUID.randomUUID(), recipient, title, body,
                Instant.ofEpochMilli(clock.millis()), null);
        try (var statement = connection.prepareStatement("""
                INSERT INTO in_app_notification (id, owner_id, title, body, created_at_ms, read_at_ms)
                VALUES (?, ?, ?, ?, ?, NULL)
                """)) {
            statement.setString(1, notification.id().toString());
            statement.setString(2, notification.owner());
            statement.setString(3, notification.title());
            statement.setString(4, notification.body());
            statement.setLong(5, notification.createdAt().toEpochMilli());
            statement.executeUpdate();
            return notification;
        }
    }

    /** Bounded, zero-based pages. New inserts can shift offsets between requests. */
    public List<InAppNotification> list(String owner, int page, int size) {
        validateOwner(owner);
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Invalid pagination");
        }
        try (var connection = connection(); var statement = connection.prepareStatement("""
                SELECT id, owner_id, title, body, created_at_ms, read_at_ms
                FROM in_app_notification WHERE owner_id = ?
                ORDER BY created_at_ms DESC, id DESC LIMIT ? OFFSET ?
                """)) {
            statement.setString(1, owner);
            statement.setInt(2, size);
            statement.setLong(3, (long) page * size);
            try (var rows = statement.executeQuery()) {
                var result = new ArrayList<InAppNotification>();
                while (rows.next()) {
                    result.add(map(rows));
                }
                return List.copyOf(result);
            }
        } catch (SQLException failure) {
            throw storageFailure();
        }
    }

    public long unreadCount(String owner) {
        validateOwner(owner);
        try (var connection = connection(); var statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM in_app_notification WHERE owner_id = ? AND read_at_ms IS NULL
                """)) {
            statement.setString(1, owner);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        } catch (SQLException failure) {
            throw storageFailure();
        }
    }

    /** Returns true for owned rows, including already-read rows; false otherwise. */
    public boolean markRead(String owner, UUID id) {
        validateOwner(owner);
        Objects.requireNonNull(id, "id");
        try (var connection = connection(); var statement = connection.prepareStatement("""
                UPDATE in_app_notification
                SET read_at_ms = CASE WHEN created_at_ms > ? THEN created_at_ms ELSE ? END
                WHERE owner_id = ? AND id = ? AND read_at_ms IS NULL
                """)) {
            long now = clock.millis();
            statement.setLong(1, now);
            statement.setLong(2, now);
            statement.setString(3, owner);
            statement.setString(4, id.toString());
            if (statement.executeUpdate() == 1) {
                return true;
            }
            // The ownership predicate applies to both paths; never fetch by ID alone.
            try (var exists = connection.prepareStatement(
                    "SELECT 1 FROM in_app_notification WHERE owner_id = ? AND id = ?")) {
                exists.setString(1, owner);
                exists.setString(2, id.toString());
                try (var rows = exists.executeQuery()) {
                    return rows.next();
                }
            }
        } catch (SQLException failure) {
            throw storageFailure();
        }
    }

    private Connection connection() throws SQLException {
        var connection = dataSource.getConnection();
        try {
            if (!connection.getAutoCommit()) {
                throw new SQLException("Auto-commit required");
            }
            return connection;
        } catch (SQLException failure) {
            connection.close();
            throw failure;
        }
    }

    private static InAppNotification map(ResultSet rows) throws SQLException {
        long readMillis = rows.getLong("read_at_ms");
        Instant readAt = rows.wasNull() ? null : Instant.ofEpochMilli(readMillis);
        return new InAppNotification(UUID.fromString(rows.getString("id")), rows.getString("owner_id"),
                rows.getString("title"), rows.getString("body"),
                Instant.ofEpochMilli(rows.getLong("created_at_ms")), readAt);
    }

    private static void validateOwner(String owner) {
        InAppNotification.requireText(owner, 255, "owner");
    }

    private static IllegalStateException storageFailure() {
        // Driver errors can contain SQL parameters, content or connection credentials.
        return new IllegalStateException("IN_APP persistence failed");
    }
}
