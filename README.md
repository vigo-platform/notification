# Vigo Notification Service

## APPROVED FOUNDATION

Independent operational service foundation using Java 21 LTS, Spring Boot 4.1.1,
Maven 3.9.16 and Maven Wrapper tooling 3.3.4 (script-only, matching the API Gateway).
Package root: `ir.vigo.notification`. Canonical repository: `vigo-platform/notification`.

The servlet/MVC HTTP stack serves management endpoints only.
Direct dependencies are `spring-boot-starter-kafka`, `spring-boot-starter-webmvc`, `spring-boot-starter-actuator`
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
Real email transport is **not implemented**. Production code defines only the
`EmailDelivery` interface, with no successful fake or fallback implementation.
Until SCRUM-112 provides real delivery, enabling Kafka in the normal production
application intentionally fails startup before listeners start or records are
consumed. Tests inject their own delivery mocks/implementations.

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
`now >= expires_at` is rejected immediately before the synchronous boundary call.
A future provider must recheck expiry at actual handoff if it adds work or queuing.

### Runtime configuration

- `NOTIFICATION_KAFKA_ENABLED=false` is the safe default. No listener is registered
  and no broker is needed for the management-only operational foundation.
- Enabling `NOTIFICATION_KAFKA_ENABLED=true` requires an `EmailDelivery` bean;
  without it, startup fails with an explicit SCRUM-112 dependency message.
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

No database/JPA, Redis, SMTP/provider, real email, notification history/read-unread,
In-App, WebSocket/SSE, REST business endpoints, JWT/security, durable retry, DLQ,
service discovery, credentials, Auth producer changes or Gateway changes are included.
The Kafka starter transitively brings Boot transaction/persistence infrastructure
and `spring-tx`; this does not configure a database or implement persistence.
