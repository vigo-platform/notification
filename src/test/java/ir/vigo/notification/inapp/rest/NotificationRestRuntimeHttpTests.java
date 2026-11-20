package ir.vigo.notification.inapp.rest;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.net.ssl.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import ir.vigo.notification.NotificationServiceApplication;
import ir.vigo.notification.delivery.EmailDelivery;
import ir.vigo.notification.inapp.JdbcInAppNotifications;
import ir.vigo.notification.messaging.model.NotificationRequested;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** Real servlet HTTP + real HTTPS JWKS + dedicated disposable PostgreSQL only. */
@EnabledIfSystemProperty(named = "notification.test.postgres.port", matches = "[0-9]+")
class NotificationRestRuntimeHttpTests {
    @TempDir static Path temporary;
    static HttpsServer jwksServer;
    static SSLSocketFactory originalTls;
    static RSAKey signingKey;
    static RSAKey invalidKey;
    static volatile boolean jwksUnavailable;
    static final String ROOT = "/api/v1/notification/notifications";
    final ObjectMapper json = new ObjectMapper();
    DisposablePostgresFixture fixture;

    @BeforeAll static void startSyntheticHttpsJwks() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("runtime-test").generate();
        invalidKey = new RSAKeyGenerator(2048).keyID("runtime-test").generate();
        Path store = temporary.resolve("https-test.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        var process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "localhost", "-keyalg", "RSA",
                "-keysize", "2048", "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", "test-only-password", "-dname", "CN=localhost", "-validity", "2",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-noprompt")
                .redirectErrorStream(true).redirectOutput(temporary.resolve("keytool.log").toFile()).start();
        assertThat(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        var keyStore = KeyStore.getInstance("PKCS12");
        try (var input = java.nio.file.Files.newInputStream(store)) { keyStore.load(input, "test-only-password".toCharArray()); }
        var keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, "test-only-password".toCharArray());
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(keyStore);
        var tls = SSLContext.getInstance("TLS");
        tls.init(keyManagers.getKeyManagers(), trust.getTrustManagers(), null);
        originalTls = HttpsURLConnection.getDefaultSSLSocketFactory();
        HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());
        jwksServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwksServer.setHttpsConfigurator(new HttpsConfigurator(tls));
        jwksServer.createContext("/jwks", exchange -> {
            if (jwksUnavailable) { exchange.sendResponseHeaders(503, -1); exchange.close(); return; }
            byte[] bytes = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        jwksServer.start();
    }

    @AfterAll static void stopSyntheticHttpsJwks() {
        if (jwksServer != null) jwksServer.stop(0);
        if (originalTls != null) HttpsURLConnection.setDefaultSSLSocketFactory(originalTls);
    }

    @BeforeEach void dedicatedDatabase() throws Exception {
        fixture = new DisposablePostgresFixture();
        fixture.create();
    }

    @AfterEach void removeOnlyOwnedSchema() throws Exception {
        if (fixture != null) fixture.close();
    }

    @Test void emptyDatabaseMigratesOnceAndHttpOwnershipSurvivesRestart() throws Exception {
        UUID id;
        String token42 = token("42");
        try (var app = start()) {
            var pool = app.getBean(com.zaxxer.hikari.HikariDataSource.class);
            assertThat(pool.getMaximumPoolSize()).isEqualTo(5);
            assertThat(pool.isAutoCommit()).isTrue();
            assertThat(pool.getSchema()).isEqualTo(fixture.schema);
            assertThat(scalar("SELECT count(*) FROM flyway_schema_history WHERE version='1' AND success")).isEqualTo(1);
            id = app.getBean(JdbcInAppNotifications.class).create("42", "Private title", "Private body").id();
            var list = request(app, "GET", ROOT, token42);
            assertResponse(list, 200, "NOTIFICATIONS_LISTED");
            assertThat(json.readTree(list.body()).at("/data/items/0/id").asText()).isEqualTo(id.toString());
            assertThat(list.body()).doesNotContain("owner_id", "owner", "schema", "jdbc:");
            assertCount(app, token42, 1);
            assertCount(app, token("43"), 0);
            var otherList = request(app, "GET", ROOT + "?owner_id=42&user_id=42", token("43"));
            assertThat(json.readTree(otherList.body()).at("/data/items").size()).isZero();
            var foreign = request(app, "PATCH", ROOT + "/" + id + "/read", token("43"));
            var missing = request(app, "PATCH", ROOT + "/" + UUID.randomUUID() + "/read", token("43"));
            assertResponse(foreign, 404, "NOTIFICATION_NOT_FOUND");
            assertResponse(missing, 404, "NOTIFICATION_NOT_FOUND");
            var foreignJson = json.readTree(foreign.body()).deepCopy();
            var missingJson = json.readTree(missing.body()).deepCopy();
            ((tools.jackson.databind.node.ObjectNode) foreignJson).remove("timestamp");
            ((tools.jackson.databind.node.ObjectNode) missingJson).remove("timestamp");
            assertThat(foreignJson).isEqualTo(missingJson);
            assertCount(app, token42, 1);
            assertResponse(request(app, "PATCH", ROOT + "/" + id + "/read", token42), 200, "NOTIFICATION_READ");
            long firstRead = scalar("SELECT read_at_ms FROM in_app_notification");
            assertResponse(request(app, "PATCH", ROOT + "/" + id + "/read", token42), 200, "NOTIFICATION_READ");
            assertThat(scalar("SELECT read_at_ms FROM in_app_notification")).isEqualTo(firstRead);
            assertCount(app, token42, 0);
            assertResponse(request(app, "GET", ROOT + "?page=-1", token42), 400, "VALIDATION_FAILED");
            assertResponse(request(app, "PATCH", ROOT + "/not-a-uuid/read", token42), 400, "VALIDATION_FAILED");
            for (String path : List.of(ROOT, ROOT + "/unread-count", ROOT + "/" + id + "/read")) {
                var unauthenticated = request(app, path.endsWith("/read") ? "PATCH" : "GET", path, null);
                assertResponse(unauthenticated, 401, "UNAUTHORIZED");
                assertThat(unauthenticated.headers().firstValue("WWW-Authenticate")).contains("Bearer");
            }
        }
        try (var restarted = start()) {
            assertThat(scalar("SELECT count(*) FROM flyway_schema_history WHERE version='1' AND success")).isEqualTo(1);
            assertThat(scalar("SELECT count(*) FROM in_app_notification")).isEqualTo(1);
            assertCount(restarted, token42, 0);
            assertResponse(request(restarted, "PATCH", ROOT + "/" + id + "/read", token42), 200, "NOTIFICATION_READ");
        }
    }

    @Test void realHttpRejectsInvalidJwtAndSanitizesDatabaseFailure() throws Exception {
        try (var app = start()) {
            for (String invalid : List.of("not-a-token", token("42", "signature"), token("42", "kid"), token("42", "issuer"),
                    token("42", "audience"), token("42", "type"), token("42", "use"), token("42", "exp"),
                    token("42", "nbf"), token("42", "iat"), token("42", "missing-exp"), token("01"), token("0"),
                    token("9223372036854775808"))) {
                assertResponse(request(app, "GET", ROOT + "?page=bad", invalid), 401, "UNAUTHORIZED");
            }
            try (var connection = fixture.connection(); var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE in_app_notification RENAME TO unmanaged_fixture");
            }
            var failure = request(app, "GET", ROOT, token("42"));
            assertResponse(failure, 500, "INTERNAL_ERROR");
            assertThat(failure.body()).doesNotContain("SQLException", "jdbc", "public", "notification_test", "relation", "stack");
        }
    }

    @Test void upgradesVersionOneWithKafkaDisabledAndPreservesHistory() throws Exception {
        try (var ignored = fixture.connection()) {
            org.flywaydb.core.Flyway.configure().dataSource(fixture.dataSource())
                    .locations("classpath:inapp/postgresql").schemas(fixture.schema).defaultSchema(fixture.schema)
                    .createSchemas(false).baselineOnMigrate(false).cleanDisabled(true).target("1").load().migrate();
        }
        var id = UUID.randomUUID();
        executeSql("INSERT INTO in_app_notification (id, owner_id, title, body, created_at_ms, read_at_ms)"
                + " VALUES ('" + id + "', '42', 'Existing title', 'Existing body', 1, 2)");
        try (var app = start()) {
            assertThat(app.containsBean("notificationV2Container")).isFalse();
            assertThat(scalar("SELECT count(*) FROM flyway_schema_history WHERE version IN ('1', '2') AND success"))
                    .isEqualTo(2);
            var list = request(app, "GET", ROOT, token("42"));
            assertResponse(list, 200, "NOTIFICATIONS_LISTED");
            assertThat(json.readTree(list.body()).at("/data/items/0/id").asText()).isEqualTo(id.toString());
            assertThat(scalar("SELECT read_at_ms FROM in_app_notification")).isEqualTo(2);
        }
    }

    @Test void forwardedProxyHeadersPreserveBearerOwnership() throws Exception {
        String[] headers = {"X-Forwarded-Proto", "https", "X-Forwarded-Host", "notification.example.invalid",
                "X-Forwarded-Prefix", "/edge"};
        try (var app = start()) {
            app.getBean(JdbcInAppNotifications.class).create("42", "Private title", "Private body");
            assertResponse(request(app, "GET", ROOT, null, headers), 401, "UNAUTHORIZED");
            var owner = request(app, "GET", ROOT + "/unread-count", token("42"), headers);
            var other = request(app, "GET", ROOT + "/unread-count", token("43"), headers);
            assertResponse(owner, 200, "UNREAD_COUNT_RETURNED");
            assertResponse(other, 200, "UNREAD_COUNT_RETURNED");
            assertThat(json.readTree(owner.body()).at("/data/unread_count").asInt()).isEqualTo(1);
            assertThat(json.readTree(other.body()).at("/data/unread_count").asInt()).isZero();
        }
    }

    @Test void refusesUnversionedNonemptyDatabaseWithoutBaselining() throws Exception {
        try (var connection = fixture.connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE unmanaged_fixture (id integer)");
        }
        assertThatThrownBy(() -> { try (var ignored = start()) { fail("Startup must reject unmanaged schema"); } })
                .hasStackTraceContaining("IN_APP database migration failed");
        assertThat(scalar("SELECT count(*) FROM information_schema.tables WHERE table_schema='" + fixture.schema + "' AND table_name='in_app_notification'")).isZero();
    }

    @Test void rejectsChecksumMismatchOnRestart() throws Exception {
        try (var ignored = start()) { assertThat(scalar("SELECT count(*) FROM flyway_schema_history WHERE version='1'")).isEqualTo(1); }
        try (var connection = fixture.connection(); var statement = connection.createStatement()) {
            statement.execute("UPDATE flyway_schema_history SET checksum=0 WHERE version='1'");
        }
        assertThatThrownBy(() -> { try (var ignored = start()) { fail("Startup must reject migration mismatch"); } })
                .hasStackTraceContaining("IN_APP database migration failed");
    }

    @Test void missingSchemaIsNotCreatedAutomatically() throws Exception {
        assertThatThrownBy(() -> { try (var ignored = start("--NOTIFICATION_DB_SCHEMA=notification_missing")) {
            fail("Startup must reject missing schema");
        } }).hasStackTraceContaining("IN_APP database migration failed");
        assertThat(scalar("SELECT count(*) FROM pg_namespace WHERE nspname='notification_missing'")).isZero();
        assertThat(scalar("SELECT count(*) FROM information_schema.tables WHERE table_schema='" + fixture.schema + "' AND table_name='in_app_notification'")).isZero();
    }

    @Test void invalidDatabaseCredentialsPreventStartup() {
        assertThatThrownBy(() -> { try (var ignored = start("--NOTIFICATION_DB_USERNAME=nonexistent_fixture_role")) {
            fail("Startup must reject invalid database credentials");
        } }).hasStackTraceContaining("IN_APP database migration failed");
    }

    @Test void jwksOutageFailsClosedThroughRealHttps() throws Exception {
        jwksUnavailable = true;
        try (var app = start()) {
            assertResponse(request(app, "GET", ROOT, token("42")), 401, "UNAUTHORIZED");
            assertThat(scalar("SELECT count(*) FROM in_app_notification")).isZero();
        } finally { jwksUnavailable = false; }
    }

    @Test
    @EnabledIfSystemProperty(named = "notification.test.kafka.port", matches = "[0-9]+")
    void kafkaAckRecoveryAndRoutingThroughAuthenticatedHttp() throws Exception {
        String cluster = System.getProperty("notification.test.kafka.cluster", "");
        assertThat(cluster).matches("[A-Za-z0-9_-]{22}");
        String broker = "127.0.0.1:" + System.getProperty("notification.test.kafka.port");
        var partition = new TopicPartition("notification.commands.v2", 0);
        var v1Topic = "notification.commands";
        var v1Partition = new TopicPartition(v1Topic, 0);
        var v1Group = "vigo-notification-service-v1-fixture";
        var group = "vigo-notification-service-v2-fixture";
        var settings = new String[] {"--notification.kafka.v2.enabled=true", "--notification.kafka.v2.mode=isolated-synthetic",
                "--notification.kafka.v2.synthetic-user-ids=42", "--notification.kafka.v2.bootstrap-servers=" + broker,
                "--notification.kafka.v2.group-id=" + group, "--spring.kafka.bootstrap-servers=" + broker,
                "--spring.kafka.consumer.group-id=" + v1Group,
                "--notification.kafka.enabled=true",
                // V1 validates SMTP settings at startup. The primary sink handles every delivery in this fixture.
                "--notification.smtp.host=127.0.0.1", "--notification.smtp.port=1",
                "--notification.smtp.security=NONE", "--notification.smtp.auth=false",
                "--notification.smtp.from-address=sender@example.invalid"};
        String first = UUID.randomUUID().toString();
        String pending = UUID.randomUUID().toString();
        String rejectedByDatabase = UUID.randomUUID().toString();
        String ownerToken = token("42");
        try (var admin = Admin.create(Map.of("bootstrap.servers", broker, "default.api.timeout.ms", 30000))) {
            assertThat(admin.describeCluster().clusterId().get(35, TimeUnit.SECONDS)).isEqualTo(cluster);
            admin.createTopics(List.of(new NewTopic(partition.topic(), 1, (short) 1), new NewTopic(v1Topic, 1, (short) 1)))
                    .all().get(15, TimeUnit.SECONDS);
            try (var producer = new KafkaProducer<>(Map.of("bootstrap.servers", broker, "acks", "all"),
                    new StringSerializer(), new StringSerializer())) {
                long readAt;
                try (var app = start(SyntheticV1Configuration.class, settings)) {
                    String otpId = UUID.randomUUID().toString();
                    publish(producer, v1Topic, otpId, v1Otp(otpId));
                    publish(producer, v1Topic, "user-service:" + first, v2Notice(first));
                    publish(producer, partition.topic(), otpId, v1Otp(otpId));
                    publish(producer, partition.topic(), "user-service:" + first, v2Notice(first));
                    await(() -> app.getBean(SyntheticV1Sink.class).deliveries.get() == 1, "one synthetic v1 delivery");
                    awaitOffset(admin, group, partition, 2);
                    assertThat(scalar("SELECT count(*) FROM notification_v2_rejection WHERE reason='VERSION'")).isEqualTo(1);
                    awaitSql("SELECT count(*) FROM notification_v2_delivery WHERE state='SUCCEEDED'", 1);

                    var list = request(app, "GET", ROOT, ownerToken);
                    assertResponse(list, 200, "NOTIFICATIONS_LISTED");
                    assertThat(json.readTree(list.body()).at("/data/items/0/title").asText()).isEqualTo("Coach application approved");
                    String id = json.readTree(list.body()).at("/data/items/0/id").asText();
                    assertResponse(request(app, "PATCH", ROOT + "/" + id + "/read", ownerToken), 200, "NOTIFICATION_READ");
                    readAt = scalar("SELECT read_at_ms FROM in_app_notification");
                    assertThat(readAt).isPositive();

                    executeSql("ALTER TABLE notification_v2_delivery ADD CONSTRAINT fixture_block_success CHECK (state <> 'SUCCEEDED') NOT VALID");
                    publish(producer, partition.topic(), "user-service:" + pending, v2Notice(pending));
                    awaitOffset(admin, group, partition, 3);
                    assertThat(scalar("SELECT count(*) FROM notification_v2_delivery WHERE state='PENDING'")).isEqualTo(1);
                    assertThat(scalar("SELECT count(*) FROM in_app_notification")).isEqualTo(1);

                    executeSql("ALTER TABLE notification_v2_command ADD CONSTRAINT fixture_block_acceptance CHECK (false) NOT VALID");
                    publish(producer, partition.topic(), "user-service:" + rejectedByDatabase, v2Notice(rejectedByDatabase));
                    var container = app.getBean("notificationV2Container", KafkaMessageListenerContainer.class);
                    await(() -> !container.isRunning(), "v2 consumer stops on database failure");
                    assertThat(offset(admin, group, partition)).isEqualTo(3);
                    var anotherOtp = UUID.randomUUID().toString();
                    publish(producer, v1Topic, anotherOtp, v1Otp(anotherOtp));
                    await(() -> app.getBean(SyntheticV1Sink.class).deliveries.get() == 2, "v1 still runs after v2 stops");
                    awaitOffset(admin, v1Group, v1Partition, 3);
                }

                executeSql("ALTER TABLE notification_v2_delivery DROP CONSTRAINT fixture_block_success");
                executeSql("ALTER TABLE notification_v2_command DROP CONSTRAINT fixture_block_acceptance");
                try (var restarted = start(SyntheticV1Configuration.class, settings)) {
                    publish(producer, partition.topic(), "user-service:" + first, v2Notice(first));
                    publish(producer, partition.topic(), "user-service:" + first, v2Notice(first).replace("approved.v1", "rejected.v1"));
                    awaitOffset(admin, group, partition, 6);
                    awaitSql("SELECT count(*) FROM notification_v2_delivery WHERE state='SUCCEEDED'", 3);
                    assertThat(scalar("SELECT count(*) FROM notification_v2_command")).isEqualTo(3);
                    assertThat(scalar("SELECT count(*) FROM notification_v2_rejection WHERE reason='IDENTITY_CONFLICT'")).isEqualTo(1);
                    assertThat(scalar("SELECT read_at_ms FROM in_app_notification WHERE read_at_ms IS NOT NULL")).isEqualTo(readAt);
                    assertCount(restarted, ownerToken, 2);
                    assertCount(restarted, token("43"), 0);

                    var v1Container = restarted.getBean(KafkaListenerEndpointRegistry.class)
                            .getListenerContainer("notificationCommands");
                    assertThat(v1Container).isNotNull();
                    assertThat(v1Container.isRunning()).isTrue();
                    restarted.getBean(SyntheticV1Sink.class).failDelivery = true;
                    String failedOtp = UUID.randomUUID().toString();
                    publish(producer, v1Topic, failedOtp, v1Otp(failedOtp));
                    awaitOffset(admin, v1Group, v1Partition, 4);
                    assertThat(v1Container.isRunning()).isTrue();
                    var sink = restarted.getBean(SyntheticV1Sink.class);
                    sink.failDelivery = false;
                    String freshOtp = UUID.randomUUID().toString();
                    publish(producer, v1Topic, freshOtp, v1Otp(freshOtp));
                    awaitOffset(admin, v1Group, v1Partition, 5);
                    assertThat(sink.deliveries.get()).isEqualTo(1);
                }
            }
        }
    }

    private static void publish(KafkaProducer<String, String> producer, String topic, String key, String payload) throws Exception {
        producer.send(new ProducerRecord<>(topic, 0, key, payload)).get(10, TimeUnit.SECONDS);
    }

    private static String v2Notice(String id) {
        return """
                {"event_id":"%s","event_type":"NotificationRequested","event_version":2,
                 "occurred_at":"2026-11-18T08:00:00Z","producer":"user-service",
                 "correlation_id":"39c08cbe-74b4-4adc-a120-09a840a76662",
                 "data":{"locale":"en","template_id":"notification.coach_application.approved.v1",
                   "template_parameters":{},"deliveries":[{"channel":"IN_APP","recipient":{"user_id":"42"}}]}}
                """.formatted(id);
    }

    private static String v1Otp(String id) {
        return """
                {"event_id":"%s","event_type":"NotificationRequested","event_version":1,
                 "occurred_at":"%s","producer":"auth-service","correlation_id":"39c08cbe-74b4-4adc-a120-09a840a76662",
                 "data":{"channel":"EMAIL","purpose":"LOGIN_OTP","locale":"en",
                   "template_id":"auth.login_otp.email.v1","recipient":{"destination":"otp@example.invalid"},
                   "template_parameters":{"otp_code":"012345"},"expires_at":"%s"}}
                """.formatted(id, Instant.now(), Instant.now().plusSeconds(120));
    }

    private static long offset(Admin admin, String group, TopicPartition partition) throws Exception {
        var committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                .get(10, TimeUnit.SECONDS).get(partition);
        return committed == null ? -1 : committed.offset();
    }

    private static void awaitOffset(Admin admin, String group, TopicPartition partition, long expected) throws Exception {
        for (int attempt = 0; attempt < 120; attempt++) {
            if (offset(admin, group, partition) == expected) return;
            Thread.sleep(250);
        }
        assertThat(offset(admin, group, partition)).as("committed offset for %s on %s", group, partition)
                .isEqualTo(expected);
    }

    private void awaitSql(String sql, long expected) throws Exception {
        for (int attempt = 0; attempt < 120; attempt++) {
            if (scalar(sql) == expected) return;
            Thread.sleep(250);
        }
        assertThat(scalar(sql)).isEqualTo(expected);
    }

    private static void await(BooleanSupplier ready, String description) throws InterruptedException {
        for (int attempt = 0; attempt < 120; attempt++) {
            if (ready.getAsBoolean()) return;
            Thread.sleep(250);
        }
        assertThat(ready.getAsBoolean()).as(description).isTrue();
    }

    private void executeSql(String sql) throws Exception {
        try (var connection = fixture.connection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SyntheticV1Configuration {
        @Bean
        @Primary
        SyntheticV1Sink syntheticV1Sink() { return new SyntheticV1Sink(); }
    }

    static final class SyntheticV1Sink implements EmailDelivery {
        final AtomicInteger deliveries = new AtomicInteger();
        volatile boolean failDelivery;

        @Override public boolean deliver(NotificationRequested command) {
            if (failDelivery) throw new IllegalStateException("Synthetic delivery failure");
            deliveries.incrementAndGet();
            return true;
        }
    }

    private ServletWebServerApplicationContext start(String... overrides) throws Exception {
        return start(null, overrides);
    }

    private ServletWebServerApplicationContext start(Class<?> extraSource, String... overrides) throws Exception {
        try (var ignored = fixture.connection()) { /* Revalidate before application migration. */ }
        var arguments = new java.util.ArrayList<>(List.of(
                "--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                "--notification.in-app.enabled=true", "--notification.kafka.enabled=false",
                "--NOTIFICATION_DB_URL=jdbc:postgresql://127.0.0.1:" + System.getProperty("notification.test.postgres.port")
                        + "/" + fixture.database + "?sslmode=disable",
                "--NOTIFICATION_DB_USERNAME=notification_test", "--NOTIFICATION_DB_PASSWORD=notification_test_only",
                "--NOTIFICATION_DB_SCHEMA=" + fixture.schema, "--NOTIFICATION_JWT_ISSUER=runtime-issuer", "--NOTIFICATION_JWT_AUDIENCE=runtime-api",
                "--NOTIFICATION_JWT_JWKS_URL=https://127.0.0.1:" + jwksServer.getAddress().getPort() + "/jwks"));
        for (String override : overrides) {
            String prefix = override.substring(0, override.indexOf('=') + 1);
            arguments.removeIf(argument -> argument.startsWith(prefix));
            arguments.add(override);
        }
        var builder = new SpringApplicationBuilder(NotificationServiceApplication.class);
        if (extraSource != null) builder.sources(extraSource);
        var application = builder.build();
        // Repeated real contexts must not reset the JVM logger/appender used by v1 output-capture tests.
        application.setListeners(application.getListeners().stream()
                .filter(listener -> !(listener instanceof org.springframework.boot.context.logging.LoggingApplicationListener)).toList());
        return (ServletWebServerApplicationContext) application.run(arguments.toArray(String[]::new));
    }

    private String token(String subject) throws Exception { return token(subject, "valid"); }
    private String token(String subject, String variation) throws Exception {
        Instant now = Instant.now();
        var builder = new JWTClaimsSet.Builder().subject(subject).issuer(variation.equals("issuer") ? "wrong" : "runtime-issuer")
                .audience(variation.equals("audience") ? "wrong" : "runtime-api")
                .claim("token_use", variation.equals("use") ? "refresh" : "access")
                .issueTime(Date.from(now.plusSeconds(variation.equals("iat") ? 60 : -1)))
                .notBeforeTime(Date.from(now.plusSeconds(variation.equals("nbf") ? 60 : -1)));
        if (!variation.equals("missing-exp")) builder.expirationTime(Date.from(now.plusSeconds(variation.equals("exp") ? -1 : 300)));
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(variation.equals("kid") ? "unknown" : "runtime-test")
                .type(new JOSEObjectType(variation.equals("type") ? "JWT" : "at+jwt")).build(), builder.build());
        jwt.sign(new RSASSASigner(variation.equals("signature") ? invalidKey : signingKey));
        return jwt.serialize();
    }

    private HttpResponse<String> request(ServletWebServerApplicationContext app, String method, String path, String token,
            String... forwardedHeaders) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.getWebServer().getPort() + path))
                    .timeout(Duration.ofSeconds(15)).header("X-User-Id", "42").method(method, HttpRequest.BodyPublishers.noBody());
            if (token != null) request.header("Authorization", "Bearer " + token);
            if (forwardedHeaders.length > 0) request.headers(forwardedHeaders);
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private void assertResponse(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(json.readTree(response.body()).get("code").asText()).isEqualTo(code);
    }

    private void assertCount(ServletWebServerApplicationContext app, String token, int expected) throws Exception {
        var response = request(app, "GET", ROOT + "/unread-count", token);
        assertResponse(response, 200, "UNREAD_COUNT_RETURNED");
        assertThat(json.readTree(response.body()).at("/data/unread_count").asInt()).isEqualTo(expected);
    }

    private long scalar(String sql) throws Exception {
        try (var connection = fixture.connection(); var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue(); return result.getLong(1);
        }
    }
}
