# Kafka v2 IN_APP ingestion

The consumer implements the [pinned NotificationRequested v2 proposal](https://github.com/vigo-platform/api-contracts/blob/ef8451690372b948c500d26642201bf57db4d1fa/messaging/notification/README.v2.md).
The schema and three valid examples under `src/test/resources/contracts` are copies
from that commit. The Java parser checks the wire structure and format assertions.
The isolated registration then checks the producer claim, template, channel, and
exact synthetic recipient against its allowlist. The two proposed Coach Application
templates use fixed Persian and English copy and accept an empty parameters object.
EMAIL and combined commands fail authorization before any inbox or history write.

## Isolated verification

Run the fixture workflow with JDK 21, Docker, and Maven:

```powershell
.\scripts\verify-disposable-postgres.ps1 -WithKafka
```

The script provisions a fresh loopback PostgreSQL database and Kafka broker and
destroys both containers when the run ends. The tests check the generated database
and cluster identities before writing fixtures. The HTTP test signs its own JWTs
and runs local HTTPS JWKS. Its v1 email sink only counts deliveries.

For a manually provisioned disposable environment, configure the
[IN_APP runtime](in-app-runtime.md) and its test JWT/JWKS settings, then set:

| Setting | Isolated value |
| --- | --- |
| `NOTIFICATION_IN_APP_ENABLED` | `true` |
| `NOTIFICATION_KAFKA_V2_ENABLED` | `true` |
| `NOTIFICATION_KAFKA_V2_MODE` | `isolated-synthetic` |
| `NOTIFICATION_KAFKA_V2_BOOTSTRAP_SERVERS` | A single `127.0.0.1:<port>` endpoint, also advertised by the disposable broker |
| `NOTIFICATION_KAFKA_V2_GROUP` | `vigo-notification-service-v2` or a distinct fixture suffix |
| `NOTIFICATION_KAFKA_V2_SYNTHETIC_USER_IDS` | Explicit comma-separated canonical synthetic IDs, such as `42,12345` |
| `NOTIFICATION_DB_URL` | `jdbc:postgresql://127.0.0.1:<port>/notification_disposable_<32 lowercase hex digits>?sslmode=disable` |

Precreate `notification.commands.v2` and publish synthetic IN_APP commands
with the key `producer:event_id`. Keep the disposable topic and database paired.
Within this fixture, partition and offset identify a rejected record.
The v2 switch defaults to false. Flyway applies V2 whenever the existing PostgreSQL
REST runtime is enabled. This migration adds only the inbox, delivery, and rejection
tables. Review it as part of any future rollout of the executable with REST enabled.

## Acceptance, delivery, and recovery

- The Kafka listener returns after PostgreSQL commits the command and pending
  delivery together, or after PostgreSQL records a terminal rejection by offset and
  reason code. Spring Kafka then commits the record offset synchronously. Unexpected
  processing failures stop only the v2 container. Restore infrastructure and
  restart to consume from the last committed offset.
- The primary keys enforce command and channel uniqueness across instances.
  Accepted payloads store a SHA-256 fingerprint of canonical JSON. Member and
  delivery order are ignored. Timestamp strings, optional-field presence and
  other semantic values are preserved. Conflicting payloads cannot overwrite
  previously accepted work.
- A scheduled worker claims due pending rows with `FOR UPDATE SKIP LOCKED`.
  History creation and channel success commit in the same transaction. If the
  transaction rolls back, another run can claim the pending row. Replay leaves
  attempts, history and read state intact. The delivery row remains as a tombstone
  if the associated history is deleted.
- The worker checks authorization and expiry before inserting history.
  History insert errors that allow a savepoint rollback use four total attempts,
  exponential backoff and jitter. These numbers apply only to the synthetic
  runtime. If the whole transaction is unavailable, the worker logs a redacted
  failure and resumes polling the previously committed pending state.
  Successful, expired, exhausted, and revoked deliveries are terminal.

## Shared Development

Keep v2 disabled for shared Development and keep external publishers on their
existing contracts. The payload's `producer` is a claim. Topic ACLs alone cannot
bind it to the publisher of an individual record. The fixture recipient allowlist
does not verify User accounts or ownership.

Before activation, approve the wire proposal, template copy and mappings, and
implement authenticated principal binding plus per-producer template, channel and
recipient authorization. Provision the v2 topic, ACLs, retention, retry and alerting
policies, and a service-owned PostgreSQL schema with the permissions required by
Flyway and the workers. Shared Development currently uses non-loopback PostgreSQL
with TLS disabled. That configuration is incompatible with the existing REST
policy. Resolve it in a separate Development integration change. Production
continues to require `sslmode=verify-full` for non-loopback PostgreSQL.
Repeat broker, database, and authenticated REST verification through the approved
publisher boundary before allowing external publishes.

Phase B requires an approved EMAIL recipient and ownership policy, a versioned
email renderer, independently recoverable channel state and bounded SMTP retries.
An accepted SMTP message can be delivered even if Notification crashes before
recording success, so SMTP delivery can be duplicated after an ambiguous failure.
