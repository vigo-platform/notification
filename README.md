# Vigo Notification Service

## APPROVED FOUNDATION

Independent operational service foundation using Java 21 LTS, Spring Boot 4.1.1,
Maven 3.9.16 and Maven Wrapper tooling 3.3.4 (script-only, matching the API Gateway).
Package root: `ir.vigo.notification`. Canonical repository: `vigo-platform/notification`.

The servlet/MVC HTTP stack serves management endpoints only.
Direct dependencies are `spring-boot-starter-mail`, `spring-boot-starter-kafka`, `spring-boot-starter-webmvc`, `spring-boot-starter-actuator`
and test-scoped `spring-boot-starter-webmvc-test`, all managed by Boot 4.1.1.
Boot 4.1.1 does not publish `spring-boot-starter-web-test`; `webmvc-test` is its
servlet testing starter. No WebFlux dependency is declared.

### Build, test and run

Prerequisite: JDK 21; Unix hosts also need unzip for the checksum-pinned ZIP
distribution. Maven is supplied by the Wrapper. Initial dependency and
Wrapper downloads require network access; default startup and tests require no broker,
database, mail server, credentials or other Vigo services.

Windows PowerShell:

```powershell
.\mvnw.cmd -B -ntp clean verify
$env:SERVER_PORT = '8080'
java -jar target/vigo-notification-service.jar
```

Linux/macOS:

```sh
chmod +x mvnw
./mvnw -B -ntp clean verify
SERVER_PORT=8080 java -jar target/vigo-notification-service.jar
```

`SERVER_PORT` defaults to `8080`. `.env.example` documents runtime settings. Spring
Boot does not automatically load `.env` files: set environment variables in your
shell, or pass a local `.env` to Docker with `--env-file`. Do not commit secrets.

### Management endpoints

- `GET /actuator/health`: 200 with `{"status":"UP"}`; details/components hidden.
- `GET /actuator/info`: 200 with `{}`; info contributors disabled.
- Other Actuator endpoints and `/actuator` discovery: unavailable.
- JMX is disabled and all Actuator JMX exposure is excluded.

Default endpoint access is denied; only health/info have read-only access and
HTTP exposure. No authentication or business endpoint is implemented. Health
reports the operational application status, not notification delivery capability.

The test suite starts the production configuration on a random port without a
test profile or infrastructure mocks. It checks startup, endpoint inventory,
health contents, info, sensitive routes, discovery and management mutations.

### Docker

Prerequisite: Docker with Linux containers. The multi-stage Dockerfile runs Maven
verification under a Java 21 JDK and copies the executable JAR into a Java 21 JRE
image. Both base images are pinned by digest; update those pins deliberately for
Java/security updates. Runtime UID/GID is `10001:10001`. No probe utilities or
Docker HEALTHCHECK are added; use external HTTP checks.

```sh
docker build -t vigo-notification-service:local .
docker run -d --name notification-local -e SERVER_PORT=18080 -p 127.0.0.1:18080:18080 vigo-notification-service:local
docker exec notification-local id
curl --fail http://127.0.0.1:18080/actuator/health
curl --fail http://127.0.0.1:18080/actuator/info
curl -i http://127.0.0.1:18080/actuator/env
docker stop notification-local
docker rm notification-local
```

The last request should return 404. In Windows PowerShell use `curl.exe` for
these commands. Changing the internal port also requires a matching Docker port
mapping; Dockerfile `EXPOSE 8080` describes only the default.

CI runs on pushes and pull requests with read-only contents permission. Pinned
GitHub first-party Node 24 actions configure Temurin 21 and Maven caching. CI runs
Maven clean verify, builds the image, checks non-root startup on an overridden
port, and always removes its temporary container. It does not publish or deploy.

## Kafka command consumer (SCRUM-113)

The first consumption slice is implemented: `notification.commands` -> strict
`NotificationRequested` v1 validation -> expiration check -> `EmailDelivery` boundary.
SMTP email transport is implemented by SCRUM-112 on this stacked branch.
Kafka requires the opt-in SMTP `EmailDelivery` adapter and valid configuration;
without it, startup fails before listeners start or records are consumed.
No successful fake or fallback implementation is registered.

Contract source: [api-contracts PR #1](https://github.com/vigo-platform/api-contracts/pull/1),
**merged**, final merge commit `705d6d9c05ba43b756963edb47d4a4c584da09d5`,
`messaging/notification/notification-requested.v1.schema.json` and its README.
The final schema is byte-for-byte identical to the schema used by the Kafka implementation.

The Kafka string key must exactly match `event_id`. Only `auth-service`, `EMAIL`,
the four OTP purposes (login, registration, password change/reset), and `fa`/`en`
are accepted. Purpose/template combinations, six ASCII OTP digits, UUIDs, closed
objects, mailbox syntax/lengths and UTC timestamps are validated without coercion.
Duplicate JSON properties and trailing JSON are rejected. Fractions beyond Java's
nanosecond precision are truncated conservatively, never extending expiry.
`now >= expires_at` is rejected immediately before the synchronous boundary call.
The SMTP adapter rechecks expiry after rendering immediately before the send call.

### Runtime configuration

- `NOTIFICATION_KAFKA_ENABLED=false` is the safe default. No listener is registered
  and no broker is needed for the management-only operational foundation.
- Enabling `NOTIFICATION_KAFKA_ENABLED=true` requires an `EmailDelivery` bean;
  without it, startup fails with an explicit email configuration message.
  `KAFKA_BOOTSTRAP_SERVERS` must
  then contain the environment's broker list; a missing/blank value fails startup.
  There is no default address or committed credential.
- `KAFKA_CONSUMER_GROUP` defaults to `vigo-notification-service-v1`, a stable,
  non-secret application subscriber identity. Replicas share it; changing it creates
  a separate subscription and may replay commands. Isolate environments at the
  broker/group level. Values are supplied at runtime without rebuilding.
- Provision `notification.commands` externally; topic auto-creation is disabled.
- The explicitly configured offset reset is `earliest` for a group without offsets.
  Existing group offsets take precedence; historical expired commands are rejected.

### Acknowledgment, failures and privacy

Auto-commit is disabled and record acknowledgment is explicit configuration.
Normal returns (delivery boundary accepted, invalid, malformed, unsupported or expired) allow
the container to commit that record's offset. Rejections are deliberately discarded;
there is no rejection store, retry or DLQ. A crash before commit can replay a record.

Unexpected processing/delivery failures are logged with a fixed reason, stripped of
their original message/cause, and propagated. `CommonContainerStoppingErrorHandler`
replaces Spring Kafka's default retry/recovery handler: it stops the listener
abnormally instead of retrying or skipping the failed record. Already processed
record offsets may be committed; the failed record remains for replay after operator
intervention/restart (subject to broker retention). HTTP management can remain UP
while consumption is stopped; monitor consumer/container state and failure logs.
There is no automatic restart, retry policy, transaction or DLQ in this slice.
See [Spring Kafka error handling](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html).

Boot supplies the consumer factory and listener factory. Single-record listeners,
concurrency one, synchronous commits and Kafka client networking/reconnect behavior
remain framework defaults. Broker reconnect attempts are transport behavior, not a
delivery retry policy. A configured but unreachable broker does not imply immediate
application startup failure or healthy consumption.

Application logs contain fixed outcome/reason codes only. Payloads, keys, headers,
OTP, destination, template parameters and original exceptions are never logged by
the adapter; the command model's `toString()` is redacted. Keep Kafka/framework
payload-level DEBUG/TRACE logging disabled in operation.

At-least-once consumption permits duplicates. Logical identity is `(producer,
event_id)`; no in-memory guard is used because it would give misleading reliability
across restarts/replicas. Durable duplicate suppression is deferred. There is no
exactly-once email guarantee, cancellation, supersession or per-recipient ordering.

### Tests and scope

Fast tests exercise validation, all purposes/locales, expiry, boundary invocation,
duplicates, privacy and enabled/disabled wiring without an external or embedded
Kafka broker. Existing management endpoint checks remain applicable.

No database/JPA, Redis, provider SDK, notification history/read-unread,
In-App, WebSocket/SSE, REST business endpoints, JWT/security, durable retry, DLQ,
service discovery, credentials, Auth producer changes or Gateway changes are included.
The Kafka starter transitively brings Boot transaction/persistence infrastructure
and `spring-tx`; this does not configure a database or implement persistence.

## SMTP email delivery (SCRUM-112)

This branch implements synchronous `EmailDelivery` with Boot 4.1.1's managed
`spring-boot-starter-mail` and `JavaMailSender`. See the
[official Boot mail configuration](https://docs.spring.io/spring-boot/reference/io/email.html).
It is stacked on `feature/notification-kafka-consumer-v1`; neither branch is merged here.

### Email configuration

Email delivery is opt-in: `NOTIFICATION_EMAIL_ENABLED=false` by default. With both
email and Kafka disabled, the application starts without infrastructure or credentials.
`EmailDelivery` exists only when email is explicitly enabled. Enabling Kafka while
email is disabled still fails fast. Enabling both with valid SMTP configuration
satisfies the delivery dependency; Kafka also needs its broker configuration.

All deployment values come from environment variables (see `.env.example`). No real
`.env` is committed. Boot maps SMTP settings through `spring.mail`; no custom mail
sender replaces its auto-configuration.

| Variable | Requirement / default |
| --- | --- |
| `NOTIFICATION_EMAIL_ENABLED` | `false`; set `true` to register the adapter |
| `SMTP_HOST` | Required when enabled; no default |
| `SMTP_PORT` | Required when enabled; integer 1–65535, no default |
| `SMTP_FROM_ADDRESS` | Required valid sender mailbox; no default |
| `SMTP_USERNAME`, `SMTP_PASSWORD` | Required when auth is enabled; no defaults |
| `SMTP_AUTH` | `true`; disable explicitly for an unauthenticated relay |
| `SMTP_STARTTLS_ENABLED` | `true`; enables and requires STARTTLS |
| `SMTP_CONNECTION_TIMEOUT_MS` | `5000`; positive connection timeout |
| `SMTP_READ_TIMEOUT_MS` | `5000`; positive read timeout |
| `SMTP_WRITE_TIMEOUT_MS` | `5000`; positive write timeout |

Separate timeout controls allow deployment tuning; all three must remain positive.
Missing required settings fail startup using variable names without printing values.
There is no startup test email or connectivity test (`spring.mail.test-connection=false`).
The mail health contributor is disabled so operational health requests do not connect
to SMTP or log provider errors. Configuration validation does not prove reachability.

### Templates, handoff and failures

Notification Service owns eight internal UTF-8 plain-text templates: `LOGIN_OTP`,
`REGISTRATION_OTP`, `PASSWORD_CHANGE_OTP`, and `PASSWORD_RESET_OTP`, each in `fa` and
`en`. They contain a purpose-specific explanation, the unchanged six-digit OTP
(including leading zeros), and an absolute expiry timestamp explicitly marked UTC.
Auth cannot supply a rendered subject/body. The recipient is the validated command
destination; the sender comes from configuration.

After rendering and constructing the message, the adapter checks `Clock.systemUTC()`
immediately before `JavaMailSender.send`. If `now >= expires_at`, it returns without
sending. This normal return permits Kafka acknowledgment of an expired command.
SMTP itself takes time; this check does not promise inbox arrival before expiry.

Send success returns normally. Any runtime delivery failure is replaced by a fixed
sanitized runtime exception without the provider message, cause or suppressed errors.
It propagates to the existing Kafka stop/preserve-replay error handler. There is no
internal retry, queue, scheduler, DLQ, persistence or durable duplicate suppression.
SMTP outcomes can be ambiguous (including partial acceptance before an error);
replay can duplicate email. There is no exactly-once delivery guarantee. Durable
duplicate suppression remains future work.

Adapter logs contain fixed outcome/reason codes only. Never log the OTP, recipient,
SMTP username/password, subject/body, full command or authentication/session data.
Mail protocol debug is disabled. Keep framework/provider DEBUG/TRACE and protocol
logging disabled in deployment as well.

### Verification

Unit/component tests use mocked `JavaMailSender`, fixed/controlled clocks and Boot
context runners, with no real SMTP credentials, email delivery or broker connection.
They cover all eight templates, configured sender and recipient, leading zeroes,
expiry at the boundary and after rendering, sanitized provider failures, credential
privacy, default/required configuration, bounded timeouts and Kafka adapter wiring.
The full application tests also verify default management startup and endpoints.

```powershell
.\mvnw.cmd -B -ntp clean verify
.\mvnw.cmd -B -ntp dependency:tree
docker build -t vigo-notification-service:email-smtp .
docker run --rm -e NOTIFICATION_EMAIL_ENABLED=false -e NOTIFICATION_KAFKA_ENABLED=false vigo-notification-service:email-smtp
# Expected nonzero exit with a missing SMTP_HOST configuration message:
docker run --rm -e NOTIFICATION_EMAIL_ENABLED=true -e NOTIFICATION_KAFKA_ENABLED=false vigo-notification-service:email-smtp
```
