package ir.vigo.notification.inapp.rest;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import ir.vigo.notification.inapp.JdbcInAppNotifications;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Opt-in, disposable local PostgreSQL only; cannot target an arbitrary database/host. */
@EnabledIfSystemProperty(named = "notification.test.postgres.port", matches = "[0-9]+")
class PostgresNotificationTests {
    private final PGSimpleDataSource source = new PGSimpleDataSource();
    private final String schema = "notification_test_" + UUID.randomUUID().toString().replace("-", "");
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-10T08:00:00Z"), ZoneOffset.UTC);
    private JdbcInAppNotifications store;

    @BeforeEach
    void migrate() throws Exception {
        source.setServerNames(new String[] {"127.0.0.1"});
        source.setPortNumbers(new int[] {Integer.parseInt(System.getProperty("notification.test.postgres.port"))});
        source.setDatabaseName("notification_test");
        source.setUser("notification_test");
        source.setPassword("notification_test_only");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        source.setCurrentSchema(schema);
        try (var connection = source.getConnection(); var reader = new InputStreamReader(
                getClass().getResourceAsStream("/inapp/postgresql/V1__create_in_app_notification.sql"), StandardCharsets.UTF_8)) {
            RunScript.execute(connection, reader);
        }
        store = new JdbcInAppNotifications(source, clock);
    }

    @AfterEach
    void removeOnlyGeneratedTestSchema() throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void verifiedBearerTokensEnforceCrossUserIsolationOnPostgres() throws Exception {
        var key = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("postgres-test").generate();
        var identity = new JwtNotificationIdentity(new TrustedJwks(
                () -> new com.nimbusds.jose.jwk.JWKSet(key.toPublicJWK()).toString(), clock),
                "test-issuer", "test-api", clock);
        var secured = NotificationRestAssembly.prepareSecured(source, clock, identity);
        var mvc = MockMvcBuilders.standaloneSetup(secured.controller()).addFilters(secured.filter()).build();
        var notification = store.create("42", "Private title", "Private body");
        String root = "/api/v1/notification/notifications";
        for (String owner : java.util.List.of("43", "42")) {
            var jwt = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256)
                    .keyID("postgres-test").type(new com.nimbusds.jose.JOSEObjectType("at+jwt")).build(),
                    new com.nimbusds.jwt.JWTClaimsSet.Builder().issuer("test-issuer").audience("test-api")
                            .subject(owner).claim("token_use", "access").issueTime(java.util.Date.from(clock.instant()))
                            .notBeforeTime(java.util.Date.from(clock.instant()))
                            .expirationTime(java.util.Date.from(clock.instant().plusSeconds(60))).build());
            jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(key));
            String bearer = "Bearer " + jwt.serialize();
            boolean own = owner.equals("42");
            mvc.perform(get(root).header("Authorization", bearer).header("X-User-Id", "42").param("owner_id", "42"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(own ? 1 : 0));
            mvc.perform(get(root + "/unread-count").header("Authorization", bearer))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.unread_count").value(own ? 1 : 0));
            mvc.perform(patch(root + "/" + notification.id() + "/read").header("Authorization", bearer))
                    .andExpect(status().is(own ? 200 : 404));
            if (!own) {
                mvc.perform(patch(root + "/" + UUID.randomUUID() + "/read").header("Authorization", bearer))
                        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
                assertThat(store.unreadCount("42")).isEqualTo(1);
            }
        }
        assertThat(store.unreadCount("42")).isZero();
    }

    @Test
    void postgresPersistsOrdersPaginatesAndSeparatesCaseSensitiveOwners() {
        var first = store.create("test-a", "First", "Test body");
        store.create("test-A", "Other case", "Test body");
        var second = new JdbcInAppNotifications(source, Clock.offset(clock, java.time.Duration.ofSeconds(1)))
                .create("test-a", "Second", "Test body");
        var reopened = new JdbcInAppNotifications(source, clock);
        assertThat(reopened.list("test-a", 0, 1)).containsExactly(second);
        assertThat(reopened.list("test-a", 1, 1)).containsExactly(first);
        assertThat(reopened.unreadCount("test-a")).isEqualTo(2);
        assertThat(reopened.unreadCount("test-A")).isEqualTo(1);
        assertThat(reopened.markRead("test-A", first.id())).isFalse();
        assertThat(reopened.markRead("test-a", first.id())).isTrue();
        assertThat(reopened.markRead("test-a", first.id())).isTrue();
        assertThat(reopened.unreadCount("test-a")).isEqualTo(1);
    }

    @Test
    void postgresConcurrentReadUpdatesPreserveFirstRead() throws Exception {
        var notification = store.create("test-a", "Title", "Body");
        try (var pool = Executors.newFixedThreadPool(4)) {
            var calls = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 12; i++) calls.add(() -> store.markRead("test-a", notification.id()));
            for (var result : pool.invokeAll(calls)) assertThat(result.get()).isTrue();
        }
        assertThat(store.unreadCount("test-a")).isZero();
        assertThat(store.list("test-a", 0, 20).getFirst().readAt()).isEqualTo(clock.instant());
    }

    @Test
    void controllerAssemblyEnforcesOwnershipAgainstRealPostgres() throws Exception {
        Principal a = () -> "test-a";
        Principal b = () -> "test-b";
        VerifiedNotificationIdentity identity = principal -> {
            if (principal == a) return "test-a";
            if (principal == b) return "test-b";
            throw new VerifiedNotificationIdentity.Unauthorized();
        };
        var mvc = MockMvcBuilders.standaloneSetup(NotificationRestAssembly.prepare(source, clock, identity)).build();
        var notification = store.create("test-b", "Title", "Body");
        String root = "/api/v1/notification/notifications";
        mvc.perform(get(root).principal(a)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(patch(root + "/" + notification.id() + "/read").principal(a)).andExpect(status().isNotFound());
        mvc.perform(get(root + "/unread-count").principal(b)).andExpect(jsonPath("$.data.unread_count").value(1));
        mvc.perform(patch(root + "/" + notification.id() + "/read").principal(b)).andExpect(status().isOk());
        mvc.perform(patch(root + "/" + notification.id() + "/read").principal(b)).andExpect(status().isOk());
        mvc.perform(get(root + "/unread-count").principal(b)).andExpect(jsonPath("$.data.unread_count").value(0));
        mvc.perform(get(root)).andExpect(status().isUnauthorized());
    }
}
