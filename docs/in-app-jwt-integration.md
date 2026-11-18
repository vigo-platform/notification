# IN_APP JWT integration — 2026-11-18

REST remains hard-disabled. This update implements and tests the authentication
boundary, but does not register production routes, a filter, a datasource or a
migration runner. Environment variables alone cannot enable REST.

## Contract evidence

Checkout: `feature/notification-email-smtp-v1`, baseline and unchanged HEAD
`8089b1574106316b6a933e7c608a483bd11fcc43`; initial working tree was clean.

Auth source inspected at `b9d8ae5409543c5b5686598379e7cbf9b13dec3b`:

- [AccessTokenService](https://github.com/vigo-platform/auth/blob/b9d8ae5409543c5b5686598379e7cbf9b13dec3b/src/main/java/ir/vigoapp/auth/security/jwt/AccessTokenService.java):
  RS256, kid, `typ=at+jwt`, `token_use=access`, decimal User ID sub, issuer,
  audience, iat, nbf and exp.
- [JwtConfig](https://github.com/vigo-platform/auth/blob/b9d8ae5409543c5b5686598379e7cbf9b13dec3b/src/main/java/ir/vigoapp/auth/config/JwtConfig.java):
  explicit access-token type/use checks and zero-skew timestamp validation.
- [Auth API](https://github.com/vigo-platform/auth/blob/b9d8ae5409543c5b5686598379e7cbf9b13dec3b/docs/api.md):
  public JWKS endpoint; access tokens can remain valid until expiry after logout.
- Local sibling `api-contracts/messaging/notification/README.v2.md` records Taha's
  confirmation of internal positive int64 ownership, overriding stale UUID wording
  elsewhere. No messaging v2 implementation is included here.

## Security and configuration

`JwtNotificationIdentity.fromEnvironment(environment, clock)` requires all of:

| Variable | Requirement |
|---|---|
| `NOTIFICATION_JWT_JWKS_URL` | Trusted deployment-supplied HTTPS URL, no userinfo or fragment |
| `NOTIFICATION_JWT_ISSUER` | Exact nonblank deployed issuer |
| `NOTIFICATION_JWT_AUDIENCE` | Nonblank expected audience, present in signed aud |

No environment address, issuer or audience is assumed. The factory is explicitly
called by future runtime assembly, not startup today. Nimbus JOSE is provided by
the Boot-managed `spring-security-oauth2-jose` dependency (resolved Nimbus 10.9.1).
No global Spring Security filter chain or default login behavior is introduced.

Verification requires RS256, nonblank bounded kid, exact `at+jwt`, a unique
compatible public RSA key of at least 2048 bits and a valid signature. Token-supplied
jku/jwk/x5u and critical extensions are rejected. Issuer, audience and access use
are checked. Required integral NumericDate iat/nbf/exp claims use zero clock skew:
iat and nbf must not be in the future; exp must be after now and both other times.
Subject must be a string matching canonical ASCII decimal `1..9223372036854775807`;
whitespace, signs, leading zeros, UUIDs and numeric JSON subjects are rejected.

Only the verifier can construct its private principal; the identity adapter also
checks the issuing verifier instance and expiration. No caller-supplied Principal,
identity header, query parameter or body field supplies ownership. The bearer
filter accepts exactly one Authorization header and replaces any incoming servlet
principal. Authentication precedes service validation and JDBC access. Failed
authentication uses the unchanged controller's generic 401 envelope, Bearer
challenge and no-store header. No token or JWKS failure detail is logged.

JWKS uses JVM HTTPS certificate/hostname verification, no redirects, 2-second
connect/read timeouts and a 64-KiB response limit. Keys cache for 5 minutes.
Unknown kid triggers refresh, limited to once per 5 seconds per verifier, including
failures. A failed refresh clears the cache; expired keys never serve as fallback.
Known cached keys can remain accepted until cache expiry without a network request.
Rotation must publish new keys before use (allow the 5-second refresh throttle)
and retain old keys through outstanding access-token lifetimes. Emergency key
removal may take up to the cache lifetime to reach this service.

`NotificationRestAssembly.prepareSecured` returns the controller and its bearer
filter together for explicit registration. Both must be registered when removing
the hard gate. Current standalone HTTP tests register both; production does neither.

## PostgreSQL review and exact enablement blockers

The existing V1 SQL and JDBC implementation are unchanged. The migration creates
owner/order and owner/unread indexes, case-sensitive C-collated identifiers,
nonempty content constraints and monotonic read timestamps. It is an initial
schema creation, not an upgrade migration. Epoch-millisecond storage and
parameterized owner predicates remain intact.

Before enabling REST, supply and verify:

1. Production PostgreSQL driver scope and pool dependency/bean. The current driver
   is test-only; there is no production datasource. Preserve auto-commit=true.
2. Approved database URL, credentials, TLS trust/hostname verification, schema/search
   path, pool sizing and connectivity. Existing example YAML is inert.
3. An authorized migration operator and confirmed V1 rollout in the service-owned
   schema. Runtime needs schema USAGE and table SELECT/INSERT/UPDATE, not DDL.
4. Deployed Auth issuer/audience/JWKS values, TLS reachability and rotation policy;
   register verifier/filter/controller together and verify direct-service requests.

The test database uses a privileged disposable role for schema creation; it does
not establish production least-privilege grants. No shared database or real shared
JWT was used. Gateway routing, live Auth HTTPS/JWKS interoperability, certificate
failure behavior against a real server, production DB/TLS/grants/migrations and
actual IN_APP ingestion remain unverified. Transport failure tests use controlled
connection/fetch doubles. Account existence/session revocation introspection is
not added; this is offline access-token validation consistent with Auth's expiry
semantics. Ingestion ownership authorization remains a separate integration task.

## Cumulative review

Changes are limited to the JOSE dependency; verifier, bounded JWKS client and
bearer adapter; explicit secured assembly; identity-interface documentation;
security/transport/PostgreSQL tests; environment examples and status documentation.
Controller routes, response DTOs, persistence, migration and application YAML are
unchanged. EMAIL, OTP, Kafka and SMTP implementations are unchanged. No commit,
push, merge or deployment was performed.

Exact changed files (relative to this repository):

- `pom.xml`, `.env.example`, `README.md`, `docs/in-app-jwt-integration.md`
- `src/main/java/ir/vigo/notification/inapp/rest/JwtNotificationIdentity.java`
- `src/main/java/ir/vigo/notification/inapp/rest/TrustedJwks.java`
- `src/main/java/ir/vigo/notification/inapp/rest/NotificationBearerFilter.java`
- `src/main/java/ir/vigo/notification/inapp/rest/NotificationRestAssembly.java`
- `src/main/java/ir/vigo/notification/inapp/rest/VerifiedNotificationIdentity.java`
- `src/test/java/ir/vigo/notification/inapp/rest/JwtNotificationIdentityTests.java`
- `src/test/java/ir/vigo/notification/inapp/rest/TrustedJwksTests.java`
- `src/test/java/ir/vigo/notification/inapp/rest/PostgresNotificationTests.java`

Reproduce with Maven 3.9.16 / Java 21 and the disposable database setup in
`in-app-rest-preparation.md`: `mvn -B -ntp -Dnotification.test.postgres.port=PORT verify`.
Without that property, the four PostgreSQL tests explicitly skip. Generated keys
and synthetic claims are test-local. All 146 baseline tests remain in the suite.

Final verification: **202 tests, 0 failures, 0 errors, 0 skipped**, with executable
JAR packaging successful. Includes 47 JWT/identity/filter cases, 8 JWKS transport
cases, 18 existing controller cases and 4 PostgreSQL cases on disposable
PostgreSQL **16.11**. The initial focused JWT run passed 46 cases; expanding the
raw-type check exposed numeric-subject coercion, which was fixed and verified in
the final full run. Production startup tests confirm routes remain 404 and no
identity/store/controller beans are registered. `git diff --check` passed.

Review artifacts (ignored build output): `target/in-app-jwt-reviewed.patch` includes
all tracked and new files; `target/jwt-final-verify.log` contains the final run.
