# IN_APP REST preparation — 2026-11-10

Historical preparation report. Current activation/configuration is documented in
[IN_APP runtime](in-app-runtime.md); the original hard gate below is no longer current.

Continues the existing local persistence changes on
`feature/notification-email-smtp-v1` at
`742668bea66816981a19f301afee6af353aadae5`. No commit, push, merge or deployment.
Only Notification repository files were changed. No production database or
production configuration was changed.

## Prepared components and exposure boundary

- `NotificationResponses`: explicit snake_case DTOs in the Vigo envelope, with
  UTC timestamps and no owner identity in output.
- `NotificationRestService`: authentication boundary before validation/database
  access; canonical UUID syntax and bounded integer pagination. It delegates to
  the existing owner-scoped JDBC operations.
- `VerifiedNotificationIdentity`: interface only. A future service-owned Auth
  adapter must validate the principal type/provenance and map the approved claim.
  No implementation assumes `Principal.getName()`, JWT claims or forwarded headers.
- `NotificationRestController`: three approved paths, no-store responses, fixed
  sanitized errors. Its `AuthContractPending` condition always returns false.
  **No configuration value, profile, principal or bearer header can expose it in
  the current application.** Default application HTTP responses remain 404.
- `NotificationRestAssembly`: explicit constructor assembly using a `DataSource`,
  `Clock` and verified-identity adapter; not a Spring configuration or bean source.
  Only tests call it. MockMvc directly instantiates controllers in the test JVM.
- Prepared PostgreSQL V1 migration and an inert configuration example. Neither is
  automatically loaded. Both database drivers remain test-scoped.

This is REST preparation, not implemented JWT authentication. The interface and
controller gate must not be treated as substitutes for an authenticated security
filter chain. Unknown principals are rejected by the test adapter; the eventual
production adapter must provide that guarantee for actual verified tokens.

## Finalized proposal and examples

Paths, pagination and ownership/idempotency semantics were approved previously.
The following precise DTOs/error names are the finalized proposal implemented in
tests; shared contract publication and team review are still pending.

All examples below describe the prepared controller **in tests or after approved
Auth integration**. They do not describe currently exposed production endpoints.
No endpoint takes an owner/user ID. Unknown query/header fields and unused request
bodies are ignored; they cannot influence ownership. PATCH requires no body.

`GET /api/v1/notification/notifications?page=0&size=20`

```json
{
  "success": true,
  "code": "NOTIFICATIONS_LISTED",
  "message": "Notifications listed",
  "message_fa": "اعلان‌ها دریافت شدند",
  "data": {
    "items": [{
      "id": "00000000-0000-0000-0000-000000000001",
      "title": "Test title",
      "body": "Test body",
      "created_at": "2026-11-10T08:00:00Z",
      "read_at": null
    }],
    "page": 0,
    "size": 20
  },
  "timestamp": "2026-11-10T08:00:00Z"
}
```

Page is zero-based, defaults to 0, maximum 2147483647. Size defaults to 20, range
1–100. Omitted parameters use defaults; empty, repeated, signed, noninteger,
whitespace-padded and overflowing parameters return 400. Leading zeroes are
accepted. Sorting is `created_at_ms DESC, id DESC`; empty pages return `items: []`.
There is no total count or snapshot guarantee. Concurrent inserts can shift offsets.

`GET /api/v1/notification/notifications/unread-count`

```json
{
  "success": true,
  "code": "UNREAD_COUNT_RETURNED",
  "message": "Unread count returned",
  "message_fa": "تعداد اعلان‌های خوانده‌نشده دریافت شد",
  "data": {"unread_count": 1},
  "timestamp": "2026-11-10T08:00:00Z"
}
```

`PATCH /api/v1/notification/notifications/00000000-0000-0000-0000-000000000001/read`

```json
{
  "success": true,
  "code": "NOTIFICATION_READ",
  "message": "Notification marked read",
  "message_fa": "اعلان خوانده شد",
  "data": null,
  "timestamp": "2026-11-10T08:00:00Z"
}
```

Returns 200 on every successful owned mark, including repeats. The first
successful read timestamp is preserved. IDs use full hyphenated UUID syntax;
upper/lower-case hex is accepted and normalized by UUID parsing.

## Error and authorization behavior

All prepared controller responses set `Cache-Control: no-store`.

| Status | Code | Data and behavior |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | Flat field-error array; no rejected values echoed |
| 401 | `UNAUTHORIZED` | `null`; `WWW-Authenticate: Bearer`; missing/unverified principal |
| 404 | `NOTIFICATION_NOT_FOUND` | `null`; identical missing/foreign notification response |
| 500 | `INTERNAL_ERROR` | `null`; fixed message, no SQL, driver details or exception cause |

Example validation `data`:

```json
[{"field":"size","code":"INVALID_VALUE","message":"Invalid value","message_fa":"مقدار نامعتبر است"}]
```

Authentication precedes pagination/ID validation and database access. Anonymous
requests with malformed input get 401 in isolated controller tests. The current
application returns 404 because controllers are not registered. There is no admin
or cross-user bypass. No 403 distinction reveals whether another user's row exists.
HTTP routing/content-negotiation errors outside the handler remain framework
behavior; the eventual security filter's 401/error serialization must be aligned
with this envelope before enabling the routes.

## JDBC review and PostgreSQL preparation

Reviewed every existing JDBC statement: all list/count/read predicates include
owner; bound parameters prevent SQL interpolation; no unscoped ID fetch exists.
The update's `read_at_ms IS NULL` predicate and follow-up owned existence query
preserve idempotency under concurrent calls. Offset multiplication uses long.
Read timestamps clamp to creation time on clock rollback. Content diagnostics and
driver exceptions are sanitized. No EMAIL/Kafka/SMTP path was changed.

`src/main/resources/inapp/postgresql/V1__create_in_app_notification.sql` retains
the existing storage shape and adds nonempty constraints and explicit `C`
collation for IDs/owners. This avoids case-insensitive owner equality and gives a
defined lexical tie-break. Indexes cover owner/order and owner/unread queries.
Timestamps remain UTC epoch milliseconds, avoiding JDBC timezone conversions.
This is an initial schema migration, not an upgrade for an existing deployed table.

`docs/in-app-postgresql.example.yml` has no local address, credentials or insecure
transport defaults. It is not imported, and this application has no production
DataSource or migration auto-wiring. The future pool must use auto-commit; runtime
DB permissions should be limited to SELECT/INSERT/UPDATE. Migrations use a separate
authorized process. Schema, pool/TLS settings and migration ownership need review.
Future production wiring must supply the approved PostgreSQL driver/pool dependency;
the current PostgreSQL dependency is deliberately test-only.

Remaining persistence limitations: no creation replay key, retention policy or
ingestion source. List and count are separate snapshots. Large offset pages can be
expensive. No unapproved scheduling/retry/ingestion workflows were added.

## Verification and reproducibility

Focused controller tests use explicit test-only principals and a mocked JDBC store.
PostgreSQL tests apply the real migration in a randomly named test schema, exercise
real JDBC operations and controller assembly, then drop only that schema. The
database/username/password are fixed test fixtures and the host is always loopback;
only the disposable port is configurable. Without the property these three tests
are explicitly skipped, so ordinary regression runs need no database.

Example disposable setup (never use production credentials):

```powershell
.\scripts\verify-disposable-postgres.ps1 -MavenCommand mvn
# Creates a new container and random test database, provisions an external
# fixture identity, runs verification, and removes only that container.
# Port-only invocation is no longer supported; see in-app-runtime.md.
```

The installed Maven 3.9.16 executable was used directly in this environment because
of the previously documented Windows wrapper failure.

Verified results:

- Source: Java 21 compilation and executable JAR packaging passed; reviewed all
  18 changed/new files including the preserved persistence slice. Tracked diff
  whitespace checks passed. No EMAIL/Kafka/SMTP implementation or application.yml
  changes. H2 and PostgreSQL drivers are absent from the executable JAR.
- Focused: 18 controller tests passed.
- Full Maven `verify`: **146 tests, 0 failures, 0 errors, 0 skipped**, including
  all 125 previous tests, 18 controller tests and 3 PostgreSQL tests.
- Database: H2 file-backed tests passed again; the prepared PostgreSQL migration
  and JDBC/controller tests passed on disposable PostgreSQL **16.11**. The test
  container was stopped and automatically removed after verification.
- Security review: no owner field controls identity; arbitrary principals and
  unverified bearer/forwarded identity headers cannot authenticate in tests;
  missing/foreign IDs share the same result; SQL is parameterized; private handler
  responses use no-store; production routes remain absent. No JWT security claim
  is made by these tests. Auth filter verification is still a release blocker.

Unavailable: real Auth token/JWKS validation, signature/issuer/audience/token-kind
and rotation failures, deployed Gateway routing, production pool/TLS/permissions,
production migration rollout, actual IN_APP ingestion, live Kafka/SMTP delivery.
Mock principals verify REST ownership behavior, not token authenticity.

## Smallest next step after Auth confirmation

Implement the service-owned JWT verifier and `VerifiedNotificationIdentity` adapter
against the confirmed owner claim/type/canonicalization, issuer, audience, token
kind, required temporal claims/clock skew and JWKS URL/key rotation/failure rules.
Add negative token tests and prove authentication executes before every route.
Then explicitly wire the approved DataSource/service/controller and remove the hard
gate in the same reviewed change. Keep it until those checks pass. No feature flag
alone should enable the current preparation. Configure Gateway routing only after
direct-service authentication tests pass. Creation-source agreement remains a
separate prerequisite for a complete notification center.
