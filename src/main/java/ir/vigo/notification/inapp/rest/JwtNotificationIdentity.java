package ir.vigo.notification.inapp.rest;

import java.net.URI;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.core.env.Environment;

/** Service-owned verifier. Deliberately not auto-registered while REST is gated. */
public final class JwtNotificationIdentity implements VerifiedNotificationIdentity {
    private final TrustedJwks keys;
    private final String issuer;
    private final String audience;
    private final Clock clock;

    public static JwtNotificationIdentity fromEnvironment(Environment environment, Clock clock) {
        return new JwtNotificationIdentity(
                URI.create(environment.getRequiredProperty("NOTIFICATION_JWT_JWKS_URL")),
                environment.getRequiredProperty("NOTIFICATION_JWT_ISSUER"),
                environment.getRequiredProperty("NOTIFICATION_JWT_AUDIENCE"), clock);
    }

    public JwtNotificationIdentity(URI jwksUrl, String issuer, String audience, Clock clock) {
        this(new TrustedJwks(jwksUrl, clock), issuer, audience, clock);
    }

    JwtNotificationIdentity(TrustedJwks keys, String issuer, String audience, Clock clock) {
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("JWT issuer and audience are required");
        }
        this.keys = Objects.requireNonNull(keys);
        this.issuer = issuer;
        this.audience = audience;
        this.clock = Objects.requireNonNull(clock);
    }

    public Principal verify(String compact) {
        try {
            if (compact == null || compact.length() > 16384) throw new Unauthorized();
            var jwt = SignedJWT.parse(compact);
            var header = jwt.getHeader();
            if (!JWSAlgorithm.RS256.equals(header.getAlgorithm())
                    || header.getType() == null || !"at+jwt".equals(header.getType().toString())
                    || header.getKeyID() == null || header.getKeyID().isBlank() || header.getKeyID().length() > 256
                    || header.getCriticalParams() != null || !header.isBase64URLEncodePayload()
                    || header.getJWKURL() != null || header.getJWK() != null || header.getX509CertURL() != null) {
                throw new Unauthorized();
            }
            RSAKey key = keys.key(header.getKeyID());
            if (!jwt.verify(new RSASSAVerifier(key.toRSAPublicKey()))) throw new Unauthorized();
            var raw = jwt.getPayload().toJSONObject();
            if (!(raw.get("sub") instanceof String) || !(raw.get("iss") instanceof String)
                    || !(raw.get("token_use") instanceof String)) throw new Unauthorized();
            Object rawAudience = raw.get("aud");
            if (!(rawAudience instanceof String)
                    && !(rawAudience instanceof java.util.List<?> values
                        && values.stream().allMatch(value -> value instanceof String))) throw new Unauthorized();
            for (String name : new String[] {"iat", "nbf", "exp"}) {
                Object value = raw.get(name);
                if (!(value instanceof Long || value instanceof Integer)
                        || ((Number) value).longValue() < 0
                        || ((Number) value).longValue() > Long.MAX_VALUE / 1000) throw new Unauthorized();
            }
            var claims = jwt.getJWTClaimsSet();
            String owner = claims.getSubject();
            if (!issuer.equals(claims.getIssuer()) || !claims.getAudience().contains(audience)
                    || !"access".equals(claims.getStringClaim("token_use"))
                    || owner == null || !owner.matches("[1-9][0-9]{0,18}") || Long.parseLong(owner) <= 0) {
                throw new Unauthorized();
            }
            Instant issued = requiredTime(claims.getClaim("iat"));
            Instant notBefore = requiredTime(claims.getClaim("nbf"));
            Instant expires = requiredTime(claims.getClaim("exp"));
            Instant now = clock.instant();
            // No clock-skew grace: expiration is exclusive; iat and nbf are inclusive.
            if (!now.isBefore(expires) || now.isBefore(issued) || now.isBefore(notBefore)
                    || !expires.isAfter(issued) || !expires.isAfter(notBefore)) throw new Unauthorized();
            return new VerifiedPrincipal(owner, expires);
        } catch (Exception rejected) {
            // Never expose claims, bearer material, JWKS URLs or parsing/network details.
            throw new Unauthorized();
        }
    }

    private static Instant requiredTime(Object value) {
        if (!(value instanceof java.util.Date date)) throw new Unauthorized();
        return date.toInstant();
    }

    @Override public String requireOwner(Principal principal) {
        if (!(principal instanceof JwtNotificationIdentity.VerifiedPrincipal verified)
                || verified.verifier() != this || !clock.instant().isBefore(verified.expires)) throw new Unauthorized();
        return verified.owner;
    }

    private final class VerifiedPrincipal implements Principal {
        private final String owner;
        private final Instant expires;
        private VerifiedPrincipal(String owner, Instant expires) { this.owner = owner; this.expires = expires; }
        private JwtNotificationIdentity verifier() { return JwtNotificationIdentity.this; }
        @Override public String getName() { return owner; }
        @Override public String toString() { return "VerifiedNotificationPrincipal[redacted]"; }
    }
}
