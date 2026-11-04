# Vigo Notification Service

## APPROVED FOUNDATION

Independent operational service foundation using Java 21 LTS, Spring Boot 4.1.1,
Maven 3.9.16 and Maven Wrapper tooling 3.3.4 (script-only, matching the API Gateway).
Package root: `ir.vigo.notification`. Intended repository: `vigo-platform/vigo-notification-service`.

The servlet/MVC HTTP stack serves management endpoints only. It does not define
future notification domain, persistence, messaging or channel implementations.
Direct dependencies are `spring-boot-starter-webmvc`, `spring-boot-starter-actuator`
and test-scoped `spring-boot-starter-webmvc-test`, all managed by Boot 4.1.1.
Boot 4.1.1 does not publish `spring-boot-starter-web-test`; `webmvc-test` is its
servlet testing starter. No WebFlux dependency is declared.

### Build, test and run

Prerequisite: JDK 21; Unix hosts also need unzip for the checksum-pinned ZIP
distribution. Maven is supplied by the Wrapper. Initial dependency and
Wrapper downloads require network access; startup and tests require no broker,
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

`SERVER_PORT` defaults to `8080`. `.env.example` documents that setting. Spring
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

## APPROVED PRODUCT DIRECTION

IN_APP and EMAIL are approved MVP product channels. PUSH is future work.
None of these channels is implemented in this foundation slice. Actual channel
selection remains contract-driven per notification/event.

Auth is the first known producer. The first future vertical slice is Auth OTP ->
Notification -> EMAIL; it is outside this foundation task (SCRUM-106).
Backend services will later validate JWT locally using Auth's public
`/.well-known/jwks.json` endpoint. The API Gateway does not own JWT authorization.
No Auth integration, JWT validation or JWKS client is implemented here.

Future selection policy and channel delivery should remain separable; provider
details should stay outside shared notification policy. These are design
boundaries, not pre-created interfaces, enums, packages or payload models.

## UNRESOLVED CONTRACTS

- Exact notification-producing events, event names and payload schemas; additional
  producers beyond Auth.
- Recipient rules and identifier semantics (user, athlete, coach or another identity).
- Notification history, persistence, read/unread or seen behavior, and retention.
- Real-time delivery and the frontend Notification REST API.
- Kafka topic names, partitions, partition keys and consumer groups.
- Retry behavior, dead-letter handling and idempotency rules.
- Email provider, sender address/domain, templates and HTML versus plain-text policy.
- Concrete channel selection rules and their representation.

IN_APP does not yet imply storage, polling or real-time delivery. These contracts
must be approved before their corresponding implementations are introduced.

## NOT IMPLEMENTED YET

No notification business endpoints, events, consumers/producers, channel enum,
dispatcher, sender or recipient interfaces, persistence entities/repositories,
templates, retry/DLQ/idempotency, or channel delivery exist. No Kafka, database/JPA,
Redis, SMTP/provider libraries, WebSocket/SSE, PUSH/Firebase, Security/JWT, service
discovery, Gateway/Auth/User/Workout integration or Docker Compose is included.
