package ir.vigo.notification.messaging.v2;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;

import ir.vigo.notification.inapp.JdbcInAppNotifications;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import static ir.vigo.notification.messaging.v2.V2CommandParser.Channel.IN_APP;

final class V2Inbox {
    private static final Logger log = LoggerFactory.getLogger(V2Inbox.class);
    // Demonstration policy for the isolated runtime only.
    private static final int MAX_ATTEMPTS = 4;
    private final DataSource source;
    private final JdbcInAppNotifications history;
    private final SyntheticV2Policy policy;
    private final Clock clock;

    V2Inbox(DataSource source, JdbcInAppNotifications history, SyntheticV2Policy policy, Clock clock) {
        this.source = source;
        this.history = history;
        this.policy = policy;
        this.clock = clock;
    }

    void accept(V2CommandParser.Command command, SyntheticV2Policy.Copy copy) {
        transaction(connection -> {
            long now = clock.millis();
            try (var insert = connection.prepareStatement("""
                    INSERT INTO notification_v2_command (producer, event_id, fingerprint, accepted_at_ms)
                    VALUES (?, ?, ?, ?) ON CONFLICT (producer, event_id) DO NOTHING
                    """)) {
                insert.setString(1, command.producer());
                insert.setObject(2, command.eventId());
                insert.setString(3, command.fingerprint());
                insert.setLong(4, now);
                if (insert.executeUpdate() == 0) {
                    requireIdentical(connection, command);
                    return false;
                }
            }
            try (var delivery = connection.prepareStatement("""
                    INSERT INTO notification_v2_delivery (producer, event_id, channel, state, next_attempt_ms,
                        expires_epoch_seconds, owner_id, title, body)
                    VALUES (?, ?, 'IN_APP', 'PENDING', ?, ?, ?, ?, ?)
                    """)) {
                delivery.setString(1, command.producer());
                delivery.setObject(2, command.eventId());
                delivery.setLong(3, now);
                delivery.setString(4, command.expiresAt() == null ? null : command.expiresAt().toString());
                delivery.setString(5, command.recipients().get(IN_APP));
                delivery.setString(6, copy.title());
                delivery.setString(7, copy.body());
                delivery.executeUpdate();
            }
            return true;
        });
    }

    private void requireIdentical(Connection connection, V2CommandParser.Command command) throws SQLException {
        // At READ COMMITTED this statement sees the committed winner of the unique-key race.
        try (var select = connection.prepareStatement("""
                SELECT fingerprint FROM notification_v2_command WHERE producer = ? AND event_id = ?
                """)) {
            select.setString(1, command.producer());
            select.setObject(2, command.eventId());
            try (var row = select.executeQuery()) {
                if (!row.next()) throw new SQLException("V2 command disappeared");
                if (!command.fingerprint().equals(row.getString(1))) {
                    throw new V2CommandParser.Rejected(V2CommandParser.Rejection.IDENTITY_CONFLICT);
                }
            }
        }
    }

    void reject(int partition, long offset, V2CommandParser.Rejection reason) {
        transaction(connection -> {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO notification_v2_rejection (partition_id, record_offset, reason, rejected_at_ms)
                    VALUES (?, ?, ?, ?) ON CONFLICT (partition_id, record_offset) DO NOTHING
                    """)) {
                insert.setInt(1, partition);
                insert.setLong(2, offset);
                insert.setString(3, reason.name());
                insert.setLong(4, clock.millis());
                insert.executeUpdate();
            }
            return true;
        });
    }

    @Scheduled(fixedDelay = 1000)
    void drainPending() {
        try {
            for (int i = 0; i < 10 && deliverNext(); i++) {
                // Limit each scheduled batch so the worker yields between bursts.
            }
        } catch (RuntimeException failure) {
            log.error("notification v2 outcome=WORKER_FAILED reason=PROCESSING_FAILURE");
        }
    }

    boolean deliverNext() {
        return transaction(connection -> {
            try (var claim = connection.prepareStatement("""
                    SELECT producer, event_id, expires_epoch_seconds, owner_id, title, body, attempts
                    FROM notification_v2_delivery
                    WHERE state = 'PENDING' AND next_attempt_ms <= ?
                    ORDER BY next_attempt_ms, producer, event_id
                    LIMIT 1 FOR UPDATE SKIP LOCKED
                    """)) {
                claim.setLong(1, clock.millis());
                try (var row = claim.executeQuery()) {
                    if (!row.next()) return false;
                    var pending = new Pending(row.getString(1), row.getObject(2, UUID.class), row.getString(3),
                            row.getString(4), row.getString(5), row.getString(6), row.getInt(7));
                    deliver(connection, pending);
                    return true;
                }
            }
        });
    }

    private void deliver(Connection connection, Pending pending) throws SQLException {
        if (!policy.allowsRecipient(pending.owner)) {
            finish(connection, pending, "AUTHORIZATION_REVOKED", pending.attempts, null, clock.millis());
            return;
        }
        var savepoint = connection.setSavepoint();
        if (pending.expiry != null && V2CommandParser.epoch(clock.instant()).compareTo(new BigDecimal(pending.expiry)) >= 0) {
            finish(connection, pending, "EXPIRED", pending.attempts, null, clock.millis());
            return;
        }
        int attempt = pending.attempts + 1;
        UUID historyId;
        try {
            historyId = history.createInTransaction(connection, pending.owner, pending.title, pending.body).id();
        } catch (SQLException failure) {
            // If the connection failed, rollback or bookkeeping will fail too. The outer
            // transaction then rolls back and the previously committed pending row survives.
            connection.rollback(savepoint);
            long delay = (1000L << (attempt - 1)) + ThreadLocalRandom.current().nextLong(250);
            finish(connection, pending, attempt >= MAX_ATTEMPTS ? "EXHAUSTED" : "PENDING",
                    attempt, null, clock.millis() + delay);
            return;
        }
        finish(connection, pending, "SUCCEEDED", attempt, historyId, clock.millis());
    }

    private static void finish(Connection connection, Pending pending, String state, int attempts,
            UUID historyId, long nextAttempt) throws SQLException {
        try (var update = connection.prepareStatement("""
                UPDATE notification_v2_delivery SET state = ?, attempts = ?, history_id = ?, next_attempt_ms = ?
                WHERE producer = ? AND event_id = ? AND channel = 'IN_APP' AND state = 'PENDING'
                """)) {
            update.setString(1, state);
            update.setInt(2, attempts);
            update.setString(3, historyId == null ? null : historyId.toString());
            update.setLong(4, nextAttempt);
            update.setString(5, pending.producer);
            update.setObject(6, pending.eventId);
            if (update.executeUpdate() != 1) throw new SQLException("V2 delivery state changed unexpectedly");
        }
    }

    private boolean transaction(Transaction action) {
        try (var connection = source.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                boolean result = action.run(connection);
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Kafka v2 inbox persistence failed");
        }
    }

    @FunctionalInterface
    private interface Transaction {
        boolean run(Connection connection) throws SQLException;
    }

    private record Pending(String producer, UUID eventId, String expiry, String owner, String title, String body,
            int attempts) {
        @Override public String toString() { return "Pending[redacted]"; }
    }
}
