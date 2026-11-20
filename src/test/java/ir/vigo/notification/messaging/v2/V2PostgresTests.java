package ir.vigo.notification.messaging.v2;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import ir.vigo.notification.inapp.JdbcInAppNotifications;
import ir.vigo.notification.inapp.rest.DisposablePostgresFixture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static ir.vigo.notification.messaging.v2.V2CommandTests.bytes;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named = "notification.test.postgres.port", matches = "[0-9]+")
class V2PostgresTests {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-19T08:00:00Z"), ZoneOffset.UTC);
    private final SyntheticV2Policy policy = new SyntheticV2Policy("12345");
    private DisposablePostgresFixture fixture;
    private JdbcInAppNotifications history;
    private V2Inbox inbox;

    @BeforeEach void migrate() throws Exception {
        fixture = new DisposablePostgresFixture();
        fixture.create();
        Flyway.configure().dataSource(fixture.dataSource()).locations("classpath:inapp/postgresql")
                .schemas(fixture.schemaName()).defaultSchema(fixture.schemaName()).createSchemas(false).load().migrate();
        history = new JdbcInAppNotifications(fixture.dataSource(), clock);
        inbox = new V2Inbox(fixture.dataSource(), history, policy, clock);
    }

    @AfterEach void cleanUp() throws Exception {
        if (fixture != null) fixture.close();
    }

    @Test void concurrentDuplicateIngressAndWorkersPreserveReadHistoryAndRejectConflicts() throws Exception {
        String payload = V2CommandTests.inApp();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var calls = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 16; i++) {
                long offset = i;
                calls.add(() -> {
                    var instance = new V2Inbox(fixture.dataSource(), history, policy, clock);
                    V2KafkaConfiguration.listener(policy, instance).onMessage(record(offset, payload));
                    instance.deliverNext();
                    return null;
                });
            }
            for (var result : pool.invokeAll(calls)) result.get();
        }
        assertThat(scalar("SELECT count(*) FROM notification_v2_command")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM notification_v2_delivery WHERE state='SUCCEEDED' AND attempts=1")).isEqualTo(1);
        var saved = history.list("12345", 0, 10);
        assertThat(saved).hasSize(1);
        assertThat(history.markRead("12345", saved.getFirst().id())).isTrue();
        var read = history.list("12345", 0, 10);
        assertThat(read.getFirst().readAt()).isNotNull();

        var restarted = new V2Inbox(fixture.dataSource(), history, policy, clock);
        var listener = V2KafkaConfiguration.listener(policy, restarted);
        listener.onMessage(record(17, payload));
        listener.onMessage(record(18, payload.replace("\"fa\"", "\"en\"")));
        listener.onMessage(record(19, payload + "null"));
        assertThat(restarted.deliverNext()).isFalse();
        assertThat(history.list("12345", 0, 10)).isEqualTo(read);
        assertThat(scalar("SELECT count(*) FROM notification_v2_rejection WHERE reason='IDENTITY_CONFLICT'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM notification_v2_rejection WHERE reason='JSON'")).isEqualTo(1);
    }

    @Test void acceptedPendingSurvivesFailureBetweenHistoryAndStateAndRecoversAfterRestart() throws Exception {
        V2KafkaConfiguration.listener(policy, inbox).onMessage(record(0, V2CommandTests.inApp()));
        assertThat(scalar("SELECT count(*) FROM notification_v2_delivery WHERE state='PENDING'")).isEqualTo(1);
        sql("ALTER TABLE notification_v2_delivery ADD CONSTRAINT fixture_no_success CHECK (state <> 'SUCCEEDED')");
        assertThatThrownBy(inbox::deliverNext).hasMessage("Kafka v2 inbox persistence failed").hasNoCause();
        assertThat(history.list("12345", 0, 10)).isEmpty();
        assertThat(scalar("SELECT count(*) FROM notification_v2_delivery WHERE state='PENDING' AND attempts=0")).isEqualTo(1);
        sql("ALTER TABLE notification_v2_delivery DROP CONSTRAINT fixture_no_success");

        var restarted = new V2Inbox(fixture.dataSource(), history, policy, clock);
        assertThat(restarted.deliverNext()).isTrue();
        assertThat(history.list("12345", 0, 10)).hasSize(1);
        assertThat(scalar("SELECT count(*) FROM notification_v2_delivery WHERE state='SUCCEEDED'")).isEqualTo(1);
    }

    @Test void rejectsDispatchAtExpiryBoundaryAndNeverRevivesExpiredWork() throws Exception {
        String expired = V2CommandTests.inApp().replace("\"locale\"",
                "\"expires_at\":\"2026-11-19T08:00:00Z\", \"locale\"");
        var listener = V2KafkaConfiguration.listener(policy, inbox);
        listener.onMessage(record(0, expired));
        assertThat(inbox.deliverNext()).isTrue();
        listener.onMessage(record(1, expired));
        assertThat(inbox.deliverNext()).isFalse();
        assertThat(history.list("12345", 0, 10)).isEmpty();
        assertThat(scalar("SELECT count(*) FROM notification_v2_delivery WHERE state='EXPIRED' AND attempts=0")).isEqualTo(1);
    }

    @Test void historyFailuresBackOffAndReplayDoesNotResetExhaustedAttempts() throws Exception {
        String payload = V2CommandTests.inApp();
        var listener = V2KafkaConfiguration.listener(policy, inbox);
        listener.onMessage(record(0, payload));
        sql("ALTER TABLE in_app_notification ADD CHECK (false) NOT VALID");
        assertThat(inbox.deliverNext()).isTrue();
        assertThat(inbox.deliverNext()).isFalse();
        for (int i = 1; i < 4; i++) {
            listener.onMessage(record(i, payload));
            var later = Clock.offset(clock, java.time.Duration.ofSeconds(20L * i));
            assertThat(new V2Inbox(fixture.dataSource(), history, policy, later).deliverNext()).isTrue();
        }
        listener.onMessage(record(5, payload));
        assertThat(scalar("SELECT count(*) FROM notification_v2_delivery WHERE state='EXHAUSTED' AND attempts=4")).isEqualTo(1);
        assertThat(history.list("12345", 0, 10)).isEmpty();
    }

    private static ConsumerRecord<byte[], byte[]> record(long offset, String payload) {
        return new ConsumerRecord<>(V2KafkaConfiguration.TOPIC, 0, offset,
                bytes("user-service:4a86376e-d876-45cf-9b19-9a204f151e53"), bytes(payload));
    }

    private long scalar(String sql) throws Exception {
        try (var connection = fixture.connection(); var statement = connection.createStatement(); var row = statement.executeQuery(sql)) {
            assertThat(row.next()).isTrue();
            return row.getLong(1);
        }
    }

    private void sql(String sql) throws Exception {
        try (var connection = fixture.connection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
