package ir.vigo.notification.inapp.rest;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ir.vigo.notification.inapp.JdbcInAppNotifications;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class JwtNotificationIdentityTests {
    static RSAKey key;
    static RSAKey other;
    MutableClock clock;
    JwtNotificationIdentity identity;
    Map<String, Object> claims;
    JWSHeader header;
    static final String ROOT = "/api/v1/notification/notifications";

    @BeforeAll static void keys() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("key-1").generate();
        other = new RSAKeyGenerator(2048).keyID("key-2").generate();
    }

    @BeforeEach void setup() {
        clock = new MutableClock();
        identity = verifier(() -> new JWKSet(key.toPublicJWK()).toString());
        claims = new HashMap<>(Map.of("iss", "test-issuer", "aud", List.of("test-api"), "sub", "42",
                "token_use", "access", "iat", clock.instant().getEpochSecond(),
                "nbf", clock.instant().getEpochSecond(), "exp", clock.instant().getEpochSecond() + 60));
        header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("key-1").type(new JOSEObjectType("at+jwt")).build();
    }

    JwtNotificationIdentity verifier(TrustedJwks.Fetch fetch) {
        return new JwtNotificationIdentity(new TrustedJwks(fetch, clock), "test-issuer", "test-api", clock);
    }

    String token() throws Exception { return token(key); }
    String token(RSAKey signingKey) throws Exception {
        var jws = new JWSObject(header, new Payload(claims));
        jws.sign(new RSASSASigner(signingKey));
        return jws.serialize();
    }
    void rejected() throws Exception {
        String token = token();
        assertThatThrownBy(() -> identity.verify(token)).isInstanceOf(VerifiedNotificationIdentity.Unauthorized.class);
    }

    @Test void acceptsOnlyOwnedVerifiedPrincipalsAndRechecksExpiry() throws Exception {
        var principal = identity.verify(token());
        assertThat(identity.requireOwner(principal)).isEqualTo("42");
        assertThatThrownBy(() -> identity.requireOwner(() -> "42")).isInstanceOf(VerifiedNotificationIdentity.Unauthorized.class);
        assertThatThrownBy(() -> verifier(() -> "").requireOwner(principal)).isInstanceOf(VerifiedNotificationIdentity.Unauthorized.class);
        clock.now = clock.now.plusSeconds(60);
        assertThatThrownBy(() -> identity.requireOwner(principal)).isInstanceOf(VerifiedNotificationIdentity.Unauthorized.class);
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "+1", "01", " 1", "1 ", "1.0", "1e2", "١", "9223372036854775808", "uuid", ""})
    void rejectsMalformedSubject(String subject) throws Exception { claims.put("sub", subject); rejected(); }

    @Test void acceptsMaximumInternalId() throws Exception {
        claims.put("sub", "9223372036854775807");
        assertThat(identity.requireOwner(identity.verify(token()))).isEqualTo("9223372036854775807");
    }

    @ParameterizedTest @ValueSource(strings = {"iss", "aud", "sub", "token_use", "iat", "nbf", "exp"})
    void rejectsMissingClaims(String claim) throws Exception { claims.remove(claim); rejected(); }

    @ParameterizedTest @ValueSource(strings = {"iss", "aud", "token_use"})
    void rejectsWrongClaims(String claim) throws Exception { claims.put(claim, "wrong"); rejected(); }

    @ParameterizedTest @ValueSource(strings = {"iat", "nbf"})
    void rejectsPrematureTokens(String claim) throws Exception {
        claims.put(claim, clock.now.getEpochSecond() + 1); rejected();
    }

    @ParameterizedTest @ValueSource(strings = {"iat", "nbf", "exp"})
    void rejectsNonNumericTimes(String claim) throws Exception { claims.put(claim, "123"); rejected(); }

    @Test void rejectsExpiredAndInconsistentTimes() throws Exception {
        claims.put("exp", clock.now.getEpochSecond()); rejected();
        claims.put("exp", clock.now.getEpochSecond() - 1); rejected();
        claims.put("exp", 1.5); rejected();
        claims.put("exp", clock.now.getEpochSecond() + 60);
        claims.put("sub", 42); rejected();
    }

    @Test void rejectsInvalidSignature() throws Exception {
        String token = token(other);
        assertThatThrownBy(() -> identity.verify(token)).isInstanceOf(VerifiedNotificationIdentity.Unauthorized.class);
    }

    @ParameterizedTest @ValueSource(strings = {"missing-kid", "unknown-kid", "wrong-type", "missing-type", "wrong-alg", "jku", "crit"})
    void rejectsHeaders(String kind) throws Exception {
        var builder = new JWSHeader.Builder(kind.equals("wrong-alg") ? JWSAlgorithm.RS512 : JWSAlgorithm.RS256)
                .keyID("key-1").type(new JOSEObjectType("at+jwt"));
        switch (kind) {
            case "missing-kid" -> builder.keyID(null);
            case "unknown-kid" -> builder.keyID("unknown");
            case "wrong-type" -> builder.type(JOSEObjectType.JWT);
            case "missing-type" -> builder.type(null);
            case "jku" -> builder.jwkURL(URI.create("https://untrusted.invalid/keys"));
            case "crit" -> builder.criticalParams(java.util.Set.of("custom")).customParam("custom", true);
        }
        header = builder.build(); rejected();
    }

    @ParameterizedTest @ValueSource(strings = {"", "a.b.c", "eyJhbGciOiJub25lIn0.e30.", "not-a-jwt"})
    void rejectsMalformedOrUnsigned(String token) {
        assertThatThrownBy(() -> identity.verify(token)).isInstanceOf(VerifiedNotificationIdentity.Unauthorized.class);
    }

    @Test void failsClosedOnJwksErrorsAndAmbiguousKeys() throws Exception {
        for (String jwks : List.of("bad-json", "{}", "{\"keys\":[]}",
                new JWKSet(List.of(key.toPublicJWK(), key.toPublicJWK())).toString())) {
            identity = verifier(() -> jwks); rejected();
        }
        identity = verifier(() -> { throw new java.net.SocketTimeoutException(); }); rejected();
    }

    @Test void rejectsIncompatibleKeyMetadata() throws Exception {
        for (RSAKey invalid : List.of(
                new RSAKey.Builder(key.toPublicJWK()).algorithm(JWSAlgorithm.RS512).build(),
                new RSAKey.Builder(key.toPublicJWK()).keyUse(KeyUse.ENCRYPTION).build(),
                new RSAKey.Builder(key.toPublicJWK()).keyOperations(java.util.Set.of(KeyOperation.SIGN)).build())) {
            identity = verifier(() -> new JWKSet(invalid).toString()); rejected();
        }
    }

    @Test void cachesRotatesThrottlesAndNeverUsesExpiredKeysOnFailure() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        identity = verifier(() -> {
            int count = calls.incrementAndGet();
            if (count > 2) throw new java.io.IOException();
            return new JWKSet(count == 1 ? key.toPublicJWK() : other.toPublicJWK()).toString();
        });
        identity.verify(token()); identity.verify(token());
        assertThat(calls).hasValue(1);
        header = new JWSHeader.Builder(header).keyID("key-2").build();
        String rotated = token(other);
        assertThatThrownBy(() -> identity.verify(rotated)).isInstanceOf(VerifiedNotificationIdentity.Unauthorized.class);
        assertThat(calls).hasValue(1);
        clock.now = clock.now.plusSeconds(5);
        identity.verify(rotated);
        assertThat(calls).hasValue(2);
        clock.now = clock.now.plusSeconds(301);
        claims.put("iat", clock.now.getEpochSecond()); claims.put("nbf", clock.now.getEpochSecond());
        claims.put("exp", clock.now.getEpochSecond() + 60);
        String fresh = token(other);
        assertThatThrownBy(() -> identity.verify(fresh)).isInstanceOf(VerifiedNotificationIdentity.Unauthorized.class);
        assertThatThrownBy(() -> identity.verify(fresh)).isInstanceOf(VerifiedNotificationIdentity.Unauthorized.class);
        assertThat(calls).hasValue(3);
    }

    @Test void requiresExplicitTrustedConfiguration() {
        assertThatThrownBy(() -> JwtNotificationIdentity.fromEnvironment(new MockEnvironment(), clock)).isInstanceOf(IllegalStateException.class);
        for (String url : List.of("http://auth.invalid/jwks", "file:///keys", "https://user@auth.invalid/keys", "https://auth.invalid/keys#fragment")) {
            assertThatThrownBy(() -> new JwtNotificationIdentity(URI.create(url), "issuer", "audience", clock))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(JwtNotificationIdentity.fromEnvironment(new MockEnvironment()
                .withProperty("NOTIFICATION_JWT_JWKS_URL", "https://auth.invalid/keys")
                .withProperty("NOTIFICATION_JWT_ISSUER", "issuer")
                .withProperty("NOTIFICATION_JWT_AUDIENCE", "audience"), clock)).isNotNull();
    }

    @Test void bearerFilterAuthenticatesEveryRouteBeforeDatabaseAndIgnoresSpoofing() throws Exception {
        var store = mock(JdbcInAppNotifications.class);
        var mvc = MockMvcBuilders.standaloneSetup(new NotificationRestController(new NotificationRestService(store, identity), clock))
                .addFilters(new NotificationBearerFilter(identity)).build();
        for (String path : List.of(ROOT, ROOT + "/unread-count", ROOT + "/bad/read")) {
            var request = path.endsWith("/read") ? patch(path) : get(path);
            mvc.perform(request.principal(() -> "42").header("X-User-Id", "42").param("user_id", "42"))
                    .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
        mvc.perform(get(ROOT).header("Authorization", "Bearer " + token(), "Bearer " + token()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(ROOT).header("Authorization", "Bearer " + token(other))).andExpect(status().isUnauthorized());
        verifyNoInteractions(store);
        mvc.perform(get(ROOT + "/unread-count").header("Authorization", "Bearer " + token())
                .header("X-User-Id", "99").param("owner", "99")).andExpect(status().isOk());
        verify(store).unreadCount("42");
    }

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-18T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
