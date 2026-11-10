package ir.vigo.notification.inapp;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcInAppNotificationsTests {
    private static final String A = "test-user-a";
    private static final String B = "test-user-b";
    private static final Instant CREATED = Instant.parse("2026-11-10T08:00:00Z");
    @TempDir Path directory;
    private String url;
    private JdbcDataSource source;
    private JdbcInAppNotifications store;

    @BeforeEach
    void initialize() throws Exception {
        url = "jdbc:h2:file:" + directory.resolve("notifications").toAbsolutePath();
        source = source();
        try (var connection = source.getConnection();
                var reader = new InputStreamReader(getClass().getResourceAsStream("/inapp/schema.sql"),
                        StandardCharsets.UTF_8)) {
            RunScript.execute(connection, reader);
        }
        store = at(CREATED);
    }

    @Test
    void creationAndReadStateSurviveDatabaseCloseAndReopen() {
        var created = store.create(A, "Test title", "Test body");
        assertThat(created.readAt()).isNull();
        assertThat(store.unreadCount(A)).isEqualTo(1);
        // No keep-alive connection or DB_CLOSE_DELAY: each operation closes the file database.
        var reopened = new JdbcInAppNotifications(source(), fixed(CREATED.plusSeconds(60)));
        assertThat(reopened.list(A, 0, 20)).containsExactly(created);
        assertThat(reopened.markRead(A, created.id())).isTrue();
        var reopenedAgain = new JdbcInAppNotifications(source(), fixed(CREATED.plusSeconds(120)));
        assertThat(reopenedAgain.unreadCount(A)).isZero();
        assertThat(reopenedAgain.list(A, 0, 20).getFirst().readAt()).isEqualTo(CREATED.plusSeconds(60));
    }

    @Test
    void repeatedReadPreservesOriginalTimestampAndCount() {
        var first = store.create(A, "First", "Test body");
        store.create(A, "Second", "Test body");
        assertThat(at(CREATED.plusSeconds(10)).markRead(A, first.id())).isTrue();
        assertThat(at(CREATED.plusSeconds(20)).markRead(A, first.id())).isTrue();
        assertThat(store.unreadCount(A)).isEqualTo(1);
        assertThat(store.list(A, 0, 20).stream().filter(n -> n.id().equals(first.id())).findFirst()
                .orElseThrow().readAt()).isEqualTo(CREATED.plusSeconds(10));
    }

    @Test
    void allQueriesAndBothReadUpdatePathsAreOwnerScoped() {
        var a = store.create(A, "For A", "Test body");
        var b = store.create(B, "For B", "Test body");
        assertThat(store.list(A, 0, 20)).containsExactly(a);
        assertThat(store.list(B, 0, 20)).containsExactly(b);
        assertThat(store.markRead(A, b.id())).isFalse();
        assertThat(store.markRead(A, UUID.randomUUID())).isFalse();
        assertThat(store.unreadCount(B)).isEqualTo(1);
        assertThat(store.markRead(B, b.id())).isTrue();
        assertThat(store.markRead(A, b.id())).isFalse();
        assertThat(store.unreadCount(A)).isEqualTo(1);
        assertThat(store.unreadCount(B)).isZero();
        assertThat(store.list("test-unknown", 0, 20)).isEmpty();
        assertThat(store.unreadCount("test-unknown")).isZero();
    }

    @Test
    void paginatesNewestFirstWithDeterministicIdTieBreak() {
        var first = store.create(A, "First", "Test body");
        var second = store.create(A, "Second", "Test body");
        var newest = at(CREATED.plusSeconds(1)).create(A, "Newest", "Test body");
        store.create(B, "Other owner", "Test body");
        var tied = new ArrayList<>(java.util.List.of(first, second));
        tied.sort(Comparator.comparing((InAppNotification n) -> n.id().toString()).reversed());
        assertThat(store.list(A, 0, 2)).containsExactly(newest, tied.get(0));
        assertThat(store.list(A, 1, 2)).containsExactly(tied.get(1));
        assertThat(store.list(A, 2, 2)).isEmpty();
        assertThat(store.list(A, Integer.MAX_VALUE, 100)).isEmpty();
    }

    @Test
    void rejectsUnboundedOrNegativePages() {
        assertThatIllegalArgumentException().isThrownBy(() -> store.list(A, -1, 20));
        assertThatIllegalArgumentException().isThrownBy(() -> store.list(A, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> store.list(A, 0, 101));
        assertThat(store.list(A, 0, 100)).isEmpty();
    }

    @Test
    void validatesOwnerAndContentWithoutEchoingValues() {
        assertThatIllegalArgumentException().isThrownBy(() -> store.create(null, "Title", "Body"));
        assertThatIllegalArgumentException().isThrownBy(() -> store.create(A, " ", "Body"));
        assertThatIllegalArgumentException().isThrownBy(() -> store.create(A, "x".repeat(201), "Body"));
        assertThatIllegalArgumentException().isThrownBy(() -> store.create(A, "Title", "x".repeat(4001)));
        assertThatIllegalArgumentException().isThrownBy(() -> store.list(" ", 0, 20));
        assertThatIllegalArgumentException().isThrownBy(() -> store.unreadCount(null));
        assertThatIllegalArgumentException().isThrownBy(() -> store.markRead("x".repeat(256), UUID.randomUUID()));
        assertThat(store.unreadCount(A)).isZero();
        var maximum = store.create(A, "x".repeat(200), "x".repeat(4000));
        assertThat(store.list(A, 0, 20)).containsExactly(maximum);
        assertThat(maximum.toString()).isEqualTo("InAppNotification[redacted]");
    }

    @Test
    void bindsSqlParametersAndRoundTripsPlainTextUnicode() {
        var n = store.create(A, "اعلان آزمایشی", "Test ' body <b>plain text</b>");
        assertThat(store.list(A, 0, 20)).containsExactly(n);
        assertThat(store.list("' OR 1=1 --", 0, 20)).isEmpty();
        assertThat(store.unreadCount("' OR 1=1 --")).isZero();
        assertThat(store.markRead("' OR 1=1 --", n.id())).isFalse();
        assertThat(store.unreadCount(A)).isEqualTo(1);
    }

    @Test
    void concurrentReadAttemptsAreIdempotent() throws Exception {
        var n = store.create(A, "Concurrent", "Test body");
        try (var executor = Executors.newFixedThreadPool(4)) {
            var calls = new ArrayList<Callable<Boolean>>();
            for (int i = 1; i <= 12; i++) {
                var reader = at(CREATED.plusSeconds(i));
                calls.add(() -> reader.markRead(A, n.id()));
            }
            for (var result : executor.invokeAll(calls)) {
                assertThat(result.get()).isTrue();
            }
        }
        var readAt = store.list(A, 0, 20).getFirst().readAt();
        assertThat(readAt).isBetween(CREATED.plusSeconds(1), CREATED.plusSeconds(12));
        assertThat(at(CREATED.plusSeconds(100)).markRead(A, n.id())).isTrue();
        assertThat(store.list(A, 0, 20).getFirst().readAt()).isEqualTo(readAt);
        assertThat(store.unreadCount(A)).isZero();
    }

    @Test
    void clockRollbackCannotCreateReadTimeBeforeCreation() {
        var n = store.create(A, "Title", "Body");
        assertThat(at(CREATED.minusSeconds(60)).markRead(A, n.id())).isTrue();
        assertThat(store.list(A, 0, 20).getFirst().readAt()).isEqualTo(CREATED);
    }

    @Test
    void databaseFailuresAreSanitizedAndNeverReportedAsSuccess() throws Exception {
        var unavailable = mock(DataSource.class);
        when(unavailable.getConnection()).thenThrow(new SQLException("test-private-driver-detail"));
        var failed = new JdbcInAppNotifications(unavailable, fixed(CREATED));
        assertThatIllegalStateException().isThrownBy(() -> failed.create(A, "Title", "Body"))
                .withMessage("IN_APP persistence failed").withNoCause();
        assertThatIllegalStateException().isThrownBy(() -> failed.list(A, 0, 20))
                .withMessage("IN_APP persistence failed").withNoCause();
        assertThatIllegalStateException().isThrownBy(() -> failed.unreadCount(A))
                .withMessage("IN_APP persistence failed").withNoCause();
        assertThatIllegalStateException().isThrownBy(() -> failed.markRead(A, UUID.randomUUID()))
                .withMessage("IN_APP persistence failed").withNoCause();
    }

    private JdbcDataSource source() {
        var result = new JdbcDataSource();
        result.setURL(url);
        return result;
    }

    private JdbcInAppNotifications at(Instant time) {
        return new JdbcInAppNotifications(source, fixed(time));
    }

    private static Clock fixed(Instant time) {
        return Clock.fixed(time, ZoneOffset.UTC);
    }
}
