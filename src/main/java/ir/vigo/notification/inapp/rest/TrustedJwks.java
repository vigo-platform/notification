package ir.vigo.notification.inapp.rest;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyOperation;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;

/** Bounded, fixed-origin JWKS fetch with no redirects or stale-cache fallback. */
final class TrustedJwks {
    @FunctionalInterface interface Fetch { String read() throws Exception; }
    private final Fetch fetch;
    private final Clock clock;
    private JWKSet cached;
    private Instant expires = Instant.MIN;
    private Instant retryAfter = Instant.MIN;

    TrustedJwks(URI uri, Clock clock) {
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Trusted JWKS URL must be HTTPS without userinfo or fragment");
        }
        this.fetch = () -> download(uri);
        this.clock = Objects.requireNonNull(clock);
    }

    TrustedJwks(Fetch fetch, Clock clock) { this.fetch = fetch; this.clock = clock; }

    synchronized RSAKey key(String kid) throws Exception {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(expires)) {
            RSAKey key = select(cached, kid);
            if (key != null) return key;
        }
        if (now.isBefore(retryAfter)) throw new IOException("JWKS refresh throttled");
        retryAfter = now.plusSeconds(5);
        // A failed refresh invalidates the cache, including previously known keys.
        cached = null;
        JWKSet loaded = JWKSet.parse(fetch.read());
        cached = loaded;
        expires = now.plusSeconds(300);
        RSAKey key = select(loaded, kid);
        if (key == null) throw new IOException("Unknown signing key");
        return key;
    }

    private static RSAKey select(JWKSet set, String kid) throws IOException {
        var matches = set.getKeys().stream().filter(key -> kid.equals(key.getKeyID())).toList();
        if (matches.isEmpty()) return null;
        if (matches.size() != 1 || !(matches.getFirst() instanceof RSAKey key)
                || key.isPrivate() || key.size() < 2048
                || (key.getAlgorithm() != null && !JWSAlgorithm.RS256.equals(key.getAlgorithm()))
                || (key.getKeyUse() != null && !KeyUse.SIGNATURE.equals(key.getKeyUse()))
                || (key.getKeyOperations() != null && !key.getKeyOperations().contains(KeyOperation.VERIFY))) {
            throw new IOException("Invalid signing key");
        }
        return key;
    }

    private static String download(URI uri) throws IOException {
        return download((HttpURLConnection) uri.toURL().openConnection());
    }

    static String download(HttpURLConnection connection) throws IOException {
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("JWKS unavailable");
            try (var stream = connection.getInputStream()) {
                byte[] bytes = stream.readNBytes(65537);
                if (bytes.length > 65536) throw new IOException("JWKS too large");
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }
}
