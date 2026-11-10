# IN_APP MVP implementation status

## Baseline and scope

Inspected 2026-11-10. Local branch `feature/notification-email-smtp-v1`, HEAD
`742668bea66816981a19f301afee6af353aadae5`. Remote notification main is
`0fdfecaa5fce849ba1a98c7dd580067af5204b40`. The remote branch inventory contained
only main, the Kafka feature branch and the EMAIL feature branch; no open PRs.
No existing IN_APP implementation was found in this inventory or checkout.

Shared contract reference:
[`api-contracts` at 705d6d9](https://github.com/vigo-platform/api-contracts/tree/705d6d9c05ba43b756963edb47d4a4c584da09d5).
Its README establishes the standard response envelope; `rest/auth.yaml` describes
RS256 access tokens and `/.well-known/jwks.json`. Neither inspected document
defines the full consumer verification agreement. Other service repositories were
not inspected or changed.

## Approved REST behavior; endpoints remain unavailable

The user approved the following proposal during this task:

| Method | Path | Behavior |
| --- | --- | --- |
| GET | `/api/v1/notification/notifications` | Owner-scoped list; zero-based `page=0`, `size=20`, maximum size 100; newest first, ID tie-break |
| GET | `/api/v1/notification/notifications/unread-count` | Owner-scoped unread count |
| PATCH | `/api/v1/notification/notifications/{id}/read` | Idempotent; 200 even when already read; identical 404 for absent and other-owner IDs |

Use the standard Vigo envelope (`success`, `code`, `message`, `message_fa`, `data`,
`timestamp`). Never accept a client-submitted owner ID. The user explicitly
instructed that authenticated REST endpoints stay disabled pending verification
of the Auth agreement. A prepared controller now has a hard false registration
condition, and its assembly has no bean/configuration annotations. No runtime
feature flag can enable it. All three routes return 404 in the production-
configuration integration test. See [REST preparation](in-app-rest-preparation.md)
for the current DTOs, examples, PostgreSQL verification and integration boundary.

The following response details are implemented in the isolated REST preparation
as the finalized proposal. They have not been published as a shared wire contract:

- List `data`: `{ "items": [...], "page": 0, "size": 20 }`. Each item contains
  `id`, `title`, `body`, `created_at`, nullable `read_at`; never `owner_id`.
- Count `data`: `{ "unread_count": 0 }`; successful mark-read `data`: `null`.
- Codes: `NOTIFICATIONS_LISTED`, `UNREAD_COUNT_RETURNED`, `NOTIFICATION_READ`,
  `NOTIFICATION_NOT_FOUND`, `VALIDATION_FAILED`, `UNAUTHORIZED`, `INTERNAL_ERROR`.
- Reject malformed IDs/pagination with 400; invalid/missing credentials with 401
  and `WWW-Authenticate: Bearer`. Private responses should use `Cache-Control: no-store`.
- Ordering is `created_at DESC, id DESC`. Offset pagination is deterministic for a
  fixed dataset, but concurrent inserts can shift later pages; no snapshot guarantee.

## Implemented and independently testable

`JdbcInAppNotifications` is an internal, unregistered component using JDK JDBC and
an injected `DataSource` and `Clock`. It persists unread user-addressed content,
lists bounded pages, counts unread rows, and atomically marks an owned row read.
Every read/update SQL predicate includes the owner. Repeated and concurrent marks
preserve the first successful read timestamp. Missing and foreign IDs return the
same boolean result, including when the foreign notification is already read.

The owner is an opaque internal string, **not a selected JWT claim**. The caller
must supply a verified identity for queries and an authorized internal recipient
for creation. This component does not authenticate callers. No HTTP creation
endpoint, Kafka IN_APP ingestion or EMAIL-to-IN_APP mapping is introduced.

Internal storage uses generated UUID IDs, required title/body (200/4000 Java string
characters), owner up to 255 characters, and UTC epoch-millisecond timestamps.
These limits are local storage bounds, not an approved public schema. Content is
plain text; future UI code must escape it. No links, HTML rendering or OTP copies
are included. Model diagnostics are redacted and SQL failures omit original
driver messages/causes. Parameters are bound, never interpolated into SQL.

`src/main/resources/inapp/schema.sql` defines the table and owner/list/unread
indexes. It is deliberately outside auto-initialization locations and is applied
explicitly only by tests. H2 is test-scoped; no production database, driver,
credentials or startup dependency is configured. A separate PostgreSQL migration
is prepared under `inapp/postgresql/`, outside automatic migration locations.
Connections must be
dedicated auto-commit connections; caller-managed transactions are not supported.
Creation has no replay/deduplication guarantee and must not be wired to an
at-least-once producer without an agreed ingestion identity/idempotency contract.

## Exact blockers and next decisions

1. Auth agreement: exact owner claim and type/canonicalization; issuer; audience
   rules; access-token versus other-token discriminator and required claims;
   expiry/not-before requirements and allowed clock skew; environment JWKS URL,
   trust/transport requirements, key selection/rotation/cache/failure policy.
   RS256 is documented, but these missing rules must not be inferred from it.
   Verification belongs in this service, not in trusted client/gateway headers.
2. Runtime persistence: supported database/driver/version, connection provisioning,
   schema migration ownership and rollout. The JDBC slice is tested against H2
   on disk, not an approved production database. Do not auto-apply this schema.
3. Creation integration: authorized source of recipient/content and any replay
   identity. Existing `NotificationRequested` v1 remains EMAIL/OTP-only.
4. REST wire schema details above, including field/error names, still need review
   when the security contract is available. Paths/pagination/idempotency and
   ownership behavior have already been approved and need not be re-approved.

## Verification

The original 114 tests passed before the change. The new file-backed H2 tests
exercise persistence after all connections close and a new data source reopens
the database, state/count transitions, idempotency and concurrent marks, both
cross-user update paths, empty/large/invalid pages, deterministic ordering, bound
SQL parameters, Unicode/plain text, limits, clock rollback and sanitized failures.
The application test also checks that IN_APP remains unexposed.

Previous persistence-slice Maven `verify`: **125 tests, 0 failures, 0 errors, 0 skipped** (114 existing,
10 persistence tests, 1 route-isolation test); executable JAR packaged successfully.
JAR inventory confirms H2 is absent. Reviewed all eight added/modified files;
`git diff --check` passed for tracked changes. No EMAIL/OTP implementation or
runtime configuration changes. New source, SQL and test files were reviewed
directly because they remain untracked until a future authorized commit workflow.

Run Maven `verify` under Java 21. The Windows wrapper failed in this environment
at `(Get-Item $MAVEN_M2_PATH).Target[0]`; verification used the already-installed
Maven 3.9.16 distribution directly with an explicit local repository. The wrapper
was not changed as part of this feature.

Unavailable end-to-end verification: Auth/JWKS and authenticated HTTP behavior,
production database/migrations, actual IN_APP ingestion, live Kafka/SMTP and a
deployed/containerized application. Passing isolated tests does not establish a
deployable notification center. No commits, pushes, merges or deployment.
