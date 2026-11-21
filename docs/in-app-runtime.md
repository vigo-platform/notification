# IN_APP REST runtime — 2026-11-19

The existing REST API now has opt-in production code for PostgreSQL, startup
Flyway migration and service-owned JWT authentication. Default startup remains
v1-only: no IN_APP routes, datasource, migration or authentication bean.

## Source and review

Branch: `feature/notification-email-smtp-v1`. Baseline and unchanged HEAD:
`08925763e53e35cf3f9d4a6764a845e7cf61e9b7`. The initial tree was clean and the
fetched feature ref matched exactly. Fetched main was
`0fdfecaa5fce849ba1a98c7dd580067af5204b40`: six main-only commits and five
feature-only commits. Main's SMTP/logging/metrics/publishing work was inspected
at the history/scope level, not merged or rebased. No changes to those behaviors
are included here. Branch integration with main remains a separate review.

Targeted comparisons: [Auth dependencies](https://github.com/vigo-platform/auth/blob/main/pom.xml),
[Auth configuration](https://github.com/vigo-platform/auth/blob/main/src/main/resources/application.yaml),
[User dependencies](https://github.com/vigo-platform/user/blob/main/pom.xml), and
[User configuration](https://github.com/vigo-platform/user/blob/main/src/main/resources/application.yaml).
Both use a runtime PostgreSQL driver and startup Flyway migrations. Notification
uses direct Boot-managed Hikari/Flyway dependencies to keep its existing JDBC
implementation and avoid JPA or global datasource auto-configuration. Auth's
automatic baseline setting is deliberately not copied: an existing unmanaged
schema requires investigation, not silent acceptance.

The controller change only replaces its always-false condition with the runtime
opt-in condition. Its operations, response envelopes and exception handling are
unchanged. JWT verifier, bearer filter, identity boundary, service, DTOs, JDBC
persistence, SQL migration and all existing tests are unchanged. No Kafka v2,
EMAIL/OTP/Kafka/SMTP implementation, Gateway, shared database or deployment change.

## Configuration and startup ordering

| Environment variable | Meaning |
|---|---|
| `NOTIFICATION_IN_APP_ENABLED` | Default `false`; set `true` to activate |
| `NOTIFICATION_DB_URL` | Required PostgreSQL JDBC URL when enabled |
| `NOTIFICATION_DB_USERNAME` | Required startup migration/runtime principal |
| `NOTIFICATION_DB_PASSWORD` | Required secret; inject securely, never commit |
| `NOTIFICATION_DB_SCHEMA` | Required existing schema; no implicit default |
| `NOTIFICATION_JWT_JWKS_URL` | Required trusted HTTPS JWKS URL |
| `NOTIFICATION_JWT_ISSUER` | Required exact issuer |
| `NOTIFICATION_JWT_AUDIENCE` | Required expected audience |

Per the confirmed development convention, deployment may use `public`
inside a dedicated `vigo_notification_service` database. Neither database name nor
schema is hardcoded in runtime code. An alternative deployment schema must already
exist and be explicitly approved/configured. System schemas and unsafe identifiers
are rejected. Schema is set on pooled connections and on Flyway; URL-supplied
search-path overrides are rejected.

The JDBC URL must name a database explicitly. The service defaults to
`sslmode=disable` for every host, including Docker service names. An explicit
`sslmode` in the URL overrides the default. Use `sslmode=verify-full` for TLS with
server certificate and hostname verification. `sslrootcert` can supply the trust
root for verified TLS. Query parameters are limited to `sslmode` and `sslrootcert`.
Credentials in URLs, duplicate parameters and fragments are rejected.

Enabled startup validates required settings and JWT URL/issuer/audience before
database access. It creates a Hikari pool (maximum 5, minimum idle 0, auto-commit,
5-second acquisition timeout, 2-second validation timeout; driver connect 5 seconds,
socket 10 seconds, statement 10 seconds and lock 5 seconds). Flyway migrates before
constructing the store/service/controller. Connection/migration errors abort startup.
The pool closes when startup fails or the application shuts down.

The same opt-in condition registers controller and runtime configuration; the
configuration installs the existing bearer filter with highest servlet precedence
and the same verifier instance used by the service. The filter's existing scope
limits authentication to notification routes. There are no test identities or
auth bypasses in runtime code. Issuer/audience semantics, keys and JWT temporal
claims are verified per request. A JWKS outage fails authentication closed (401);
startup does not fetch JWKS or assert remote reachability. Existing bounded cache
and rotation behavior remains as documented in the JWT integration report.

## Migration and permissions

Flyway discovers `classpath:inapp/postgresql` only, reusing the existing
`V1__create_in_app_notification.sql` byte-for-byte. It is not copied to a second
location. Its historical preparation-only comment is retained to preserve the
reviewed migration contents; explicit runtime wiring now selects that location.
The H2 fixture under `inapp/schema.sql` is never a runtime migration.

Validation is enabled. Baseline, clean and schema creation are disabled. Missing
schema, missing location, invalid credentials, an unmanaged nonempty schema or a
checksum mismatch stop startup. No automatic repair is attempted. Restart validates
history and applies only pending migrations; V1 is not executed again.

The configured principal needs CONNECT to the dedicated database, USAGE and CREATE
on the target schema, and ownership/DDL privileges needed for migrations and the
Flyway history table. It also needs SELECT/INSERT/UPDATE for runtime operations.
This implementation uses one principal for automatic startup migration and runtime;
a deployment requiring separate migration credentials needs an explicit follow-up.
The disposable fixture role is privileged, so production least-privilege grants
are not claimed as verified.

## Isolated verification

Tests now use a new loopback-only PostgreSQL 16 container and a randomly named
`notification_disposable_<32 hex digits>` database. Both PostgreSQL suites require
an externally provisioned database comment `vigo-notification-disposable:<identity>`
matching a separately supplied random 32-hex fixture identity. Tests only read this
marker; they never create or repair it. The guard checks current database, role and
marker before schema creation, before runtime migration, and on every direct test
SQL connection including cleanup. Missing/malformed configuration or absent/wrong
marker fails closed, without mutation. A localhost port alone cannot authorize setup.

Each test creates a fresh UUID-named schema without `IF NOT EXISTS`; it never adopts
an existing schema. Cleanup revalidates the fixture and drops only a schema that
the same fixture instance successfully created. Setup never drops public tables.
This preserves all REST/Flyway lifecycle assertions using the configurable schema;
the preceding 232-test checkpoint also verified the public-schema convention.
The fixture marker is an accidental-target safety guard, not authentication against
a malicious administrator who can reproduce both the database name and marker.
Only the fresh-container runner below may provision it; never mark an existing DB
just to make tests pass. Test keys
are generated in memory; a temporary self-signed HTTPS certificate with a matching
localhost/IP SAN is trusted only by the test JVM and restored afterward. Production
certificate and hostname verification are unchanged.

`NotificationRestRuntimeHttpTests` starts the actual application and Tomcat on
loopback ephemeral ports. Requests use Java HTTP client, not MockMvc. They cover:

- Empty unique test-owned schema: exactly one successful version 1 migration.
- Restart: one V1 history entry, existing notification/read state preserved.
- List and unread count return 200; valid/repeated mark-read returns 200 without
  changing the first read timestamp; count moves from one to zero.
- Invalid page/UUID returns 400; missing or invalid bearer returns 401 before
  validation; valid signed tokens with bad signature, kid, issuer, audience,
  type/use, temporal claims or canonical subject are rejected.
- Foreign-owned and missing IDs return identical 404 envelopes except timestamp.
  Spoofed owner parameters and `X-User-Id` cannot alter ownership.
- Storage failure returns a fixed 500 envelope without SQL/credentials/details.
- A real HTTPS JWKS 503 response fails closed with a generic 401.
- Unmanaged schema, tampered checksum, missing schema and invalid database
  credentials reject startup. Missing schema is not created.

Configuration tests cover the disabled state, each missing DB/Auth setting,
unsafe schemas, URL/TLS restrictions and redacted configuration errors. Existing
application tests verify all three IN_APP routes remain 404 when disabled and
health/info exposure stays unchanged. Existing 55 JWT/JWKS security cases and v1
regressions remain in the full suite.

Safe reproduction from the repository root:

```powershell
.\scripts\verify-disposable-postgres.ps1 -MavenCommand mvn
# Or supply the absolute installed mvn.cmd path and optional -MavenRepository.
```

The runner creates a new container with no shared volume, generates the database
name and independent identity, and provisions the marker via `docker exec` using
only the newly returned container ID. It accepts no existing container or database.
It passes port/database/identity properties to Maven and removes that exact container
in `finally`, including after test failures. Without the port property, PostgreSQL
suites explicitly skip; with a port but missing identity/database they fail before
mutation. Manually supplying only a port is no longer supported.

Prior runtime checkpoint Maven `verify`: **232 tests, 0 failures, 0 errors, 0 skipped**, executable
JAR packaging successful (Java 21 / Maven 3.9.16). This includes all 202 previous
tests, 23 new configuration cases and 7 real HTTP/database lifecycle cases.
The first focused run passed 27 cases before the last three failure cases were
added. The initial full run exposed a JVM-wide logging side effect from the new
test harness; isolating its Boot logging listener restored all existing v1 log
assertions without changing production logging or existing tests.

JAR inspection found exactly one V1 migration and PostgreSQL 42.7.13, HikariCP
7.0.2, Flyway core 12.4.0 and the matching PostgreSQL module. H2 is absent.
The migration and protected implementations match the baseline byte-for-byte;
diff whitespace checks passed. No private keys, real JWTs or deployment secrets
are included in the changes. Test-generated HTTPS key material stays temporary.

Current review artifacts are ignored build output:
`target/inapp-fixture-safety-reviewed.patch` (complete runtime checkpoint, tracked
and new files) and `target/inapp-fixture-safety-verify.log` (final verification).
The runtime was enabled only in disposable test processes and those processes
were closed afterward. No service was left deployed/running; the default remains
disabled. No commit, push, merge or deployment was performed.

## Exact change scope

- `pom.xml`: PostgreSQL runtime scope; Boot-managed HikariCP, Flyway core and
  PostgreSQL Flyway module.
- `src/main/resources/application.yml`: only the disabled-by-default IN_APP flag.
- `src/main/java/ir/vigo/notification/inapp/rest/NotificationRestRuntime.java`:
  settings checks, bounded pool, mandatory startup migrations, service and filter beans.
- `src/main/java/ir/vigo/notification/inapp/rest/NotificationRestController.java`:
  replace unconditional hard gate with the matching opt-in condition.
- `src/test/java/ir/vigo/notification/inapp/rest/NotificationRestRuntimeConfigurationTests.java`:
  configuration and disabled runtime checks.
- `src/test/java/ir/vigo/notification/inapp/rest/NotificationRestRuntimeHttpTests.java`:
  real HTTP/HTTPS/PostgreSQL/Flyway lifecycle and failure checks.
- `src/test/java/ir/vigo/notification/inapp/rest/DisposablePostgresFixture.java`,
  `DisposablePostgresFixtureTests.java`, `PostgresNotificationTests.java` and
  `scripts/verify-disposable-postgres.ps1`: guarded fixture provisioning, negative
  identity checks and schema-scoped setup/cleanup across both integration suites.
- `.env.example`, `README.md`, `docs/in-app-postgresql.example.yml`,
  `docs/in-app-jwt-integration.md`, `docs/in-app-rest-preparation.md`, and this report:
  configuration, current status and historical-report pointers.

## Smallest safe integration step

Review these uncommitted changes. Before any shared-environment activation, inspect
the actual dedicated database, selected schema, existing tables and Flyway history
read-only; confirm migration ownership/grants, TLS trust and exact Auth settings.
Do not baseline/repair an existing schema merely to force startup. The team may
then explicitly authorize a controlled development rollout and authenticated
smoke test. No shared database inspection or rollout was performed in this task.
IN_APP ingestion and Kafka v2 remain separate; these endpoints read existing rows.

The original rollout required verified TLS for PostgreSQL on non-loopback hosts.
The current runtime accepts `sslmode=disable` for Docker service names. Deployments
that require hostname and certificate verification can set `sslmode=verify-full`.

## Fixture hardening verification and commit readiness

The safe runner completed Maven `verify`: **240 tests, 0 failures, 0 errors,
0 skipped**, with executable JAR packaging successful. This includes seven guard
unit cases (absent/empty/incorrect marker, deployment-name rejection, database/role
mismatch, exact positive match), five PostgreSQL integration cases (including a
real wrong-identity setup rejection), and seven actual HTTP/Flyway lifecycle cases.
The wrong-identity integration case verifies no schema was created and cleanup
after rejected setup is a no-op. All previous JWT and v1 regressions pass.

Safety changes are limited to the shared test fixture, its negative tests, both
PostgreSQL suites, the fresh-container provisioning runner and documentation.
Production runtime, controller gate, dependencies and application configuration
match the preceding reviewed runtime patch. JWT/Flyway migration contents and
production security policy remain unchanged. The runner removed its container
after verification; no shared/deployed database was contacted.

The complete runtime checkpoint is ready for a review/commit decision with this
test-safety finding addressed. This is not shared-development activation approval:
Compose TLS, real schema/history, migration privileges and Auth configuration
remain rollout checks. HEAD is still `08925763e53e35cf3f9d4a6764a845e7cf61e9b7`;
no commit, push, merge or deployment has occurred.
