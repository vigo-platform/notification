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
import java.util.UUID;
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
import ir.vigo.notification.inapp.JdbcInAppNotifications;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
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

    private ServletWebServerApplicationContext start(String... overrides) throws Exception {
        try (var ignored = fixture.connection()) { /* Revalidate before application migration. */ }
        var arguments = new java.util.ArrayList<>(List.of(
                "--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                "--notification.in-app.enabled=true", "--notification.email.enabled=false", "--notification.kafka.enabled=false",
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
        var application = new SpringApplicationBuilder(NotificationServiceApplication.class).build();
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

    private HttpResponse<String> request(ServletWebServerApplicationContext app, String method, String path, String token) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.getWebServer().getPort() + path))
                    .timeout(Duration.ofSeconds(15)).header("X-User-Id", "42").method(method, HttpRequest.BodyPublishers.noBody());
            if (token != null) request.header("Authorization", "Bearer " + token);
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
