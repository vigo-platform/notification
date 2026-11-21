package ir.vigo.notification.messaging;

import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;

final class CoachApplicationEmailInbox {
    private final DataSource dataSource;

    CoachApplicationEmailInbox(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    boolean claim(UUID eventId) {
        return update("""
                INSERT INTO coach_application_email (event_id) VALUES (?) ON CONFLICT DO NOTHING
                """, eventId) == 1;
    }

    void release(UUID eventId) {
        update("DELETE FROM coach_application_email WHERE event_id = ?", eventId);
    }

    private int update(String sql, UUID eventId) {
        // The notification pool uses auto-commit. The claim must commit before invoking SMTP.
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, eventId);
            return statement.executeUpdate();
        } catch (SQLException ignored) {
            throw new IllegalStateException("Coach application email inbox unavailable");
        }
    }
}
