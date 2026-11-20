package ir.vigo.notification.messaging.v2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import tools.jackson.databind.json.JsonMapper;

import static ir.vigo.notification.messaging.v2.V2CommandParser.Rejection.*;
import static org.assertj.core.api.Assertions.*;

class V2CommandTests {
    private static final String KEY = "user-service:4a86376e-d876-45cf-9b19-9a204f151e53";
    private final V2CommandParser parser = new V2CommandParser();
    private final SyntheticV2Policy policy = new SyntheticV2Policy("12345,42");

    @Test void pinnedFixturesParseAndPhaseARejectsEveryCommandThatIncludesEmail() throws IOException {
        var schema = JsonMapper.builder().build().readTree(
                sample("/contracts/notification-requested.v2.schema.json"));
        assertThat(schema.at("/properties/event_version/const").asInt()).isEqualTo(2);
        assertThat(policy.authorize(parse(KEY, inApp())).body()).isEqualTo("درخواست مربیگری شما رد شد.");
        var email = parse(KEY.replaceFirst("53$", "52"), sample("/contracts/v2/coach-approved.email.en.json"));
        var combined = parse(KEY.replaceFirst("53$", "51"), sample("/contracts/v2/coach-approved.both.fa.json"));
        assertThat(email.recipients()).containsOnlyKeys(V2CommandParser.Channel.EMAIL);
        assertThat(combined.recipients()).containsOnlyKeys(V2CommandParser.Channel.EMAIL, V2CommandParser.Channel.IN_APP);
        assertThatThrownBy(() -> policy.authorize(email)).hasMessage(UNSUPPORTED_CHANNEL.name());
        assertThatThrownBy(() -> policy.authorize(combined)).hasMessage(UNSUPPORTED_CHANNEL.name());
    }

    @Test void fingerprintsIgnoreOrderAndNumericSpellingButKeepAllSemanticValues() throws IOException {
        String command = sample("/contracts/v2/coach-approved.both.fa.json");
        String key = KEY.replaceFirst("53$", "51");
        var mapper = JsonMapper.builder().build();
        var root = (tools.jackson.databind.node.ObjectNode) mapper.readTree(command);
        var original = parse(key, command);
        var data = (tools.jackson.databind.node.ObjectNode) root.get("data");
        var first = data.get("deliveries").get(0);
        var second = data.get("deliveries").get(1);
        data.putArray("deliveries").add(second).add(first);
        var occurred = root.remove("occurred_at");
        root.set("occurred_at", occurred);
        root.put("event_version", 2.0);
        assertThat(parse(key, mapper.writeValueAsString(root)).fingerprint()).isEqualTo(original.fingerprint());

        for (String changed : List.of(command.replace("08:00:00Z", "08:00:00+00:00"),
                command.replace("example.invalid", "EXAMPLE.invalid"),
                command.replace("39c08cbe", "39c08cbf"),
                command.replaceFirst("(?m)^.*expires_at.*\\R", ""))) {
            assertThat(parse(key, changed).fingerprint()).isNotEqualTo(original.fingerprint());
        }
    }

    @Test void validatesTheWholeClosedWireShapeWithoutCoercion() throws IOException {
        String event = inApp();
        for (String payload : List.of(event + " {}", event.replace("\"fa\"", "\"fa\", \"locale\": \"en\""),
                event.replace("\"12345\"", "12345"), event.replace("12345", "012345"),
                event.replace("12345", "0"), event.replace("12345", "9223372036854775808"),
                event.replace("4a86376e", "4A86376E"), event.replace("\"event_version\": 2", "\"event_version\": \"2\""),
                event.replace("\"event_version\": 2", "\"event_version\": 1"), event.replace("\"fa\"", "\"fr\""),
                event.replace("2026-11-18", "2026-02-30"), event.replace("08:00:00Z", "08:00:60Z"),
                event.replace("08:00:00Z", "24:00:00Z"), event.replace("08:00:00Z", "08:00:00-00:00"),
                event.replace("\"IN_APP\"", "\"PUSH\""), event.replace("\"12345\"", "\"12345\", \"read\": true"),
                event.replace("\"template_parameters\": {}", "\"template_parameters\": {\"otp_code\":\"123456\"}"),
                event.replace("\"template_parameters\": {}", "\"template_parameters\": {\"message\":\"<b>\"}"),
                event.replace("\"template_parameters\": {}", "\"template_parameters\": {\"message\":[]}"))) {
            assertThatThrownBy(() -> parse(KEY, payload)).isInstanceOf(V2CommandParser.Rejected.class);
        }
        String duplicates = event.replace("    ]", "      ,{\"channel\":\"IN_APP\",\"recipient\":{\"user_id\":\"42\"}}\n    ]");
        assertThatThrownBy(() -> parse(KEY, duplicates)).hasMessage(CHANNEL.name());
        assertThatThrownBy(() -> parse("wrong", event)).hasMessage(V2CommandParser.Rejection.KEY.name());
        assertThatThrownBy(() -> parser.parse(bytes(KEY), new byte[] {(byte) 0xc0})).hasMessage(JSON.name());
        assertThat(parse(KEY, event.replace("12345", "9223372036854775807")).recipients())
                .containsValue("9223372036854775807");
    }

    @Test void validatesExactSubNanosecondExpirationOrdering() throws IOException {
        String event = inApp().replace("08:00:00Z", "08:00:00.0000000001z");
        String later = event.replace("\"locale\"", "\"expires_at\":\"2026-11-18t08:00:00.0000000002+00:00\", \"locale\"");
        assertThat(parse(KEY, later).expiresAt()).isNotNull();
        assertThatThrownBy(() -> parse(KEY, later.replace("0000000002", "0000000001"))).hasMessage(EXPIRY_ORDER.name());
    }

    @Test void syntacticValidityDoesNotGrantProducerTemplateOrRecipientAuthorization() throws IOException {
        String event = inApp();
        assertThatThrownBy(() -> policy.authorize(parse(KEY.replace("user-service", "order-service"),
                event.replace("user-service", "order-service")))).hasMessage(PRODUCER_NOT_REGISTERED.name());
        assertThatThrownBy(() -> policy.authorize(parse(KEY, event.replace("rejected.v1", "rejected.v2"))))
                .hasMessage(TEMPLATE_NOT_REGISTERED.name());
        assertThatThrownBy(() -> policy.authorize(parse(KEY, event.replace("12345", "12346"))))
                .hasMessage(RECIPIENT_NOT_AUTHORIZED.name());
        assertThatThrownBy(() -> policy.authorize(parse(KEY, event.replace("\"template_parameters\": {}",
                "\"template_parameters\": {\"message\":\"synthetic-secret-value\"}")))).hasMessage(PARAMETERS.name());
    }

    @Test void startupIsOptInAndRequiresBothDurabilityAndIsolatedMode() {
        var context = new ApplicationContextRunner().withUserConfiguration(V2KafkaConfiguration.class);
        context.run(app -> assertThat(app).doesNotHaveBean(KafkaMessageListenerContainer.class));
        context.withPropertyValues("notification.kafka.v2.enabled=true").run(app ->
                assertThat(app.getStartupFailure()).hasRootCauseMessage("Kafka v2 requires the durable IN_APP runtime"));
        context.withPropertyValues("notification.kafka.v2.enabled=true", "notification.in-app.enabled=true").run(app ->
                assertThat(app.getStartupFailure()).hasRootCauseMessage("Kafka v2 supports only the isolated-synthetic mode"));
    }

    private V2CommandParser.Command parse(String key, String event) { return parser.parse(bytes(key), bytes(event)); }
    static String inApp() throws IOException { return sample("/contracts/v2/coach-rejected.in-app.fa.json"); }
    private static String sample(String resource) throws IOException {
        try (var input = V2CommandTests.class.getResourceAsStream(resource)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
