# Vigo Notification Service

## APPROVED FOUNDATION

Independent operational service foundation using Java 21 LTS, Spring Boot 4.1.1,
Maven 3.9.16 and Maven Wrapper tooling 3.3.4 (script-only, matching the API Gateway).
Package root: `ir.vigo.notification`. Canonical repository: `vigo-platform/notification`.

The servlet/MVC HTTP stack serves management endpoints only. Spring Boot manages
the Kafka, WebMVC and Actuator starters and the WebMVC test starter. Structured
JSON logging uses `logstash-logback-encoder` 8.0, matching Auth. The SMTP client
uses Jakarta Mail with the Angus provider.

### Build, test and run

Prerequisite: JDK 21; Unix hosts also need unzip for the checksum-pinned ZIP
distribution. Maven is supplied by the Wrapper. Initial dependency and
Wrapper downloads require network access; default startup and tests require no broker,
database, mail server, credentials or other Vigo services.

Windows PowerShell:

```powershell
.\mvnw.cmd -B -ntp clean verify
Copy-Item .env.example .env
java -jar target/vigo-notification-service.jar
```

Linux/macOS:

```sh
chmod +x mvnw
./mvnw -B -ntp clean verify
cp .env.example .env
java -jar target/vigo-notification-service.jar
```

Set `SERVER_PORT` in the process environment or in the `.env` file in the working
directory. The default is `8080`. Process environment variables take precedence
over file values. Docker accepts the same file through `--env-file`.

### Forwarded headers and logging

Like Auth, the HTTP server uses `forward-headers-strategy: framework`. Behind a
trusted proxy, have it replace client-provided forwarded headers with trusted
values. For direct access, set `SERVER_FORWARD_HEADERS_STRATEGY=none` in the
process environment.

The console uses Auth's Spring Boot log pattern with structured key/value fields
and the correlation slot. Outside Docker, Logback also sends JSON events with
`service_name=notification` to Vector at `LOG_VECTOR_DESTINATION` (`localhost:5044`
by default). Vector forwards them to Elasticsearch. Set `LOG_VECTOR_ENABLED=false`
for a console-only local run. Set `LOG_LEVEL` to adjust the root log level.

The Docker image defaults to console-only logging and includes `logging=true` and
`service=notification` labels so the existing Vector Docker source can collect it.
Leave `LOG_VECTOR_ENABLED` unset in a shared `.env` to keep the runtime defaults.

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

Prerequisite: Docker with Linux containers. The multi-stage Dockerfile packages
the executable JAR with Maven and Java 21, then runs it in a Java 21 JRE image as
UID/GID `10001:10001`. The packaging step skips tests, matching Auth.

```sh
docker build -t vigo-notification-service:local .
docker run -d --name notification-local --env-file .env -p 127.0.0.1:18080:8080 vigo-notification-service:local
docker exec notification-local id
curl --fail http://127.0.0.1:18080/actuator/health
curl --fail http://127.0.0.1:18080/actuator/info
curl -i http://127.0.0.1:18080/actuator/env
docker stop notification-local
docker rm notification-local
```

The last request should return 404. In Windows PowerShell use `curl.exe` for
these commands. If you change `SERVER_PORT` in `.env`, update the container port
in the Docker port mapping.

The **Docker Build & Push** workflow publishes `logtaha/vigo-notification-service`
on manual dispatch or a pushed `build-and-push` / `build-and-push-<version>` tag.
A supplied version is published alongside `latest`. An empty manual tag or a
plain `build-and-push` tag publishes `sha-<short-sha>`. The workflow uses the
`DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN` repository secrets.

## Kafka command consumer and email delivery

The service reads `NotificationRequested` v1 commands from `notification.commands`,
validates them, and submits an email through the configured SMTP relay. Auth sends
the OTP and the required command metadata, including the recipient, purpose,
template ID, locale and expiry. Notification inserts the OTP into the selected
template and sends UTF-8 HTML with a plain-text alternative.

The four OTP purposes have distinct subjects and text. The default template
language is Persian with a right-to-left layout. Set the required v1 `data.locale`
to `fa` for Persian or `en` for English. The copy lives in
[`mail/otp.properties`](src/main/resources/mail/otp.properties) and
[`mail/otp_en.properties`](src/main/resources/mail/otp_en.properties). Both use
[`mail/otp.html`](src/main/resources/mail/otp.html) for the email layout. The
Persian layout embeds Vazirmatn under the [SIL Open Font License](src/main/resources/mail/fonts/OFL-Vazirmatn.txt)
and uses system fonts when the mail client omits embedded fonts.

Contract source: [api-contracts PR #1](https://github.com/vigo-platform/api-contracts/pull/1),
head `7e3a792d97863c75df4a338570ccd81161f6b3a4`,
`messaging/notification/notification-requested.v1.schema.json` and its README.
**Do not merge this implementation until the contract PR is approved/merged and
compatibility with the eventual main contract is rechecked.**

The Kafka string key must exactly match `event_id`. Only `auth-service`, `EMAIL`,
the four OTP purposes (login, registration, password change/reset), and `fa`/`en`
are accepted. Purpose/template combinations, six ASCII OTP digits, UUIDs, closed
objects, mailbox syntax/lengths and UTC timestamps are validated without coercion.
Duplicate JSON properties and trailing JSON are rejected. Fractions beyond Java's
nanosecond precision are truncated conservatively, never extending expiry.
`now >= expires_at` is rejected before opening a connection and again after the
SMTP handshake, immediately before submission to the relay.

### Runtime configuration

- `NOTIFICATION_KAFKA_ENABLED=false` is the safe default. No listener is registered
  and no broker is needed for the management-only operational foundation.
- When `NOTIFICATION_KAFKA_ENABLED=true`, set `KAFKA_BOOTSTRAP_SERVERS` and the
  SMTP relay settings before startup. A missing broker address or invalid SMTP
  configuration fails startup. The first valid command opens the SMTP connection.
- `KAFKA_CONSUMER_GROUP` defaults to `vigo-notification-service-v1`, a stable,
  non-secret application subscriber identity. Replicas share it; changing it creates
  a separate subscription and may replay commands. Isolate environments at the
  broker/group level. Values are supplied at runtime without rebuilding.
- Provision `notification.commands` externally; topic auto-creation is disabled.
- The explicitly configured offset reset is `earliest` for a group without offsets.
  Existing group offsets take precedence; historical expired commands are rejected.

### External SMTP relay

Set these values in the local `.env` or in the process environment. Docker can
load them with `--env-file .env`.

| Setting | Value |
| --- | --- |
| `SMTP_HOST`, `SMTP_PORT` | Relay hostname and port. The example uses `465` with `TLS`. |
| `SMTP_SECURITY` | `STARTTLS` requires a TLS upgrade, normally on port `587`. `TLS` connects over TLS immediately, normally on port `465`. `NONE` supports a trusted plaintext relay with authentication disabled. |
| `SMTP_AUTH` | Defaults to `true`. Set to `false` for a relay that accepts submissions without credentials. |
| `SMTP_USERNAME`, `SMTP_PASSWORD` | Relay credentials, required when `SMTP_AUTH=true`. |
| `SMTP_FROM_ADDRESS`, `SMTP_FROM_NAME` | Sender mailbox authorized by the relay and its display name. The example display name is `Vigo`. |
| `SMTP_CONNECT_TIMEOUT_MS`, `SMTP_READ_TIMEOUT_MS`, `SMTP_WRITE_TIMEOUT_MS` | Positive timeouts in milliseconds. Each defaults to `5000`. |

TLS validates the relay certificate against the JVM trust store and verifies its
hostname. The service records success when the relay accepts the message. The relay
handles onward delivery to the mailbox.

### Acknowledgment, failures and privacy

Auto-commit is disabled and record acknowledgment is explicit configuration.
Normal returns (SMTP relay accepted, invalid, malformed, unsupported or expired) allow
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

Command logs use fixed event, outcome and reason fields. Payloads, keys, headers,
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

No database/JPA, Redis, notification history/read-unread,
In-App, WebSocket/SSE, REST business endpoints, JWT/security, durable retry, DLQ,
service discovery, credentials, Auth producer changes or Gateway changes are included.
The Kafka starter transitively brings Boot transaction/persistence infrastructure
and `spring-tx`; this does not configure a database or implement persistence.
