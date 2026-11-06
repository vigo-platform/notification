package ir.vigo.notification.messaging;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import ir.vigo.notification.messaging.model.NotificationRequested;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class NotificationCommandConsumerTests {
    static final String KEY = "1949b943-e3d8-4af5-9f39-07b890b91aaa";
    static final String DESTINATION = "recipient@example.invalid";
    static final String OTP = "012345";
    static final String VALID = """
            {"event_id":"1949b943-e3d8-4af5-9f39-07b890b91aaa",
             "event_type":"NotificationRequested","event_version":1,
             "occurred_at":"2026-01-01T12:00:00Z","producer":"auth-service",
             "correlation_id":"cebc9fe4-8b76-4bba-9cf5-c6319d3dbbbb",
             "data":{"channel":"EMAIL","purpose":"LOGIN_OTP","locale":"fa",
             "recipient":{"destination":"recipient@example.invalid"},
             "template_id":"auth.login_otp.email.v1",
             "template_parameters":{"otp_code":"012345"},"expires_at":"2026-01-01T12:03:00Z"}}
            """;
    private final List<NotificationRequested> delivered = new ArrayList<>();
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T12:01:00Z"), ZoneOffset.UTC);
    private final NotificationCommandConsumer consumer = new NotificationCommandConsumer(delivered::add, clock);

    @Test
    void validLoginInvokesBoundaryExactlyOnceAndPreservesOtp(CapturedOutput output) {
        consumer.consume(record(KEY, VALID));
        assertThat(delivered).hasSize(1);
        var request = delivered.getFirst();
        assertThat(request.otpCode()).isEqualTo(OTP);
        assertThat(request.destination()).isEqualTo(DESTINATION);
        assertThat(request.eventId().toString()).isEqualTo(KEY);
        assertThat(request.toString()).doesNotContain(OTP, DESTINATION);
        assertThat(output.getAll()).contains("BOUNDARY_ACCEPTED").doesNotContain(OTP, DESTINATION);
    }

    @ParameterizedTest
    @MethodSource("supported")
    void acceptsEveryPurposeAndLocale(String purpose, String locale) {
        String payload = VALID.replace("LOGIN_OTP", purpose)
                .replace("auth.login_otp.email.v1", "auth." + purpose.toLowerCase(java.util.Locale.ROOT) + ".email.v1")
                .replace("\"fa\"", "\"" + locale + "\"");
        consumer.consume(record(KEY, payload));
        assertThat(delivered).hasSize(1);
    }

    static Stream<Arguments> supported() {
        return Stream.of("LOGIN_OTP", "REGISTRATION_OTP", "PASSWORD_CHANGE_OTP", "PASSWORD_RESET_OTP")
                .flatMap(p -> Stream.of("fa", "en").map(l -> Arguments.of(p, l)));
    }

    @ParameterizedTest(name = "rejects {0} = {1}")
    @MethodSource("invalidFields")
    void invalidNeverInvokesBoundaryOrLeaksValues(String path, String value, CapturedOutput output) {
        consumer.consume(record(KEY, changed(path, value)));
        assertThat(delivered).isEmpty();
        assertThat(output.getAll()).contains("REJECTED").doesNotContain(OTP, DESTINATION);
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(
                Arguments.of("event_type", "\"NotificationSent\""),
                Arguments.of("event_version", "2"), Arguments.of("event_version", "\"1\""),
                Arguments.of("event_version", "true"), Arguments.of("event_version", "1.00000000000000001"),
                Arguments.of("producer", "\"other-service\""),
                Arguments.of("event_id", "\"not-a-uuid\""),
                Arguments.of("correlation_id", "\"not-a-uuid\""),
                Arguments.of("data.channel", "\"IN_APP\""),
                Arguments.of("data.purpose", "\"PASSWORD_RESET_NOTICE\""),
                Arguments.of("data.locale", "\"de\""), Arguments.of("data.locale", "null"),
                Arguments.of("data.template_id", "\"auth.registration_otp.email.v1\""),
                Arguments.of("data.template_parameters.otp_code", "123456"),
                Arguments.of("data.template_parameters.otp_code", "\"12345\""),
                Arguments.of("data.template_parameters.otp_code", "\"1234567\""),
                Arguments.of("data.template_parameters.otp_code", "\"12345a\""),
                Arguments.of("data.template_parameters.otp_code", "\"۱۲۳۴۵۶\""),
                Arguments.of("data.template_parameters.otp_code", "\"012345\\n\""),
                Arguments.of("data.expires_at", "\"2026-01-01T12:01:00Z\""),
                Arguments.of("data.expires_at", "\"2026-01-01T12:00:59Z\""),
                Arguments.of("data.expires_at", "\"invalid\""),
                Arguments.of("data.expires_at", "\"2026-02-30T12:00:00Z\""),
                Arguments.of("data.expires_at", "\"2026-01-01T12:03:60Z\""),
                Arguments.of("data.expires_at", "\"2026-01-01T12:03:00+03:30\""),
                Arguments.of("data.expires_at", "\"2026-01-01T12:03:00-00:00\""),
                Arguments.of("occurred_at", "\"invalid\""),
                Arguments.of("occurred_at", "\"0000-01-01T12:00:00Z\""),
                Arguments.of("data.recipient.destination", "\"Person <user@example.invalid>\""),
                Arguments.of("data.recipient.destination", "\"a..b@example.invalid\""),
                Arguments.of("data.recipient.destination", "\"a@-example.invalid\""),
                Arguments.of("data.recipient.destination", "\"a@[192.0.2.1]\""),
                Arguments.of("data.recipient.destination", "\"a@example.invalid\\n\""),
                Arguments.of("data.recipient.destination", "\"" + "a".repeat(65) + "@example.invalid\""),
                Arguments.of("data.recipient.destination", "\"a@" + "b".repeat(64) + ".invalid\""),
                Arguments.of("data.recipient", "[]"),
                Arguments.of("data.template_parameters", "{}"),
                Arguments.of("data", "null"),
                Arguments.of("extra", "\"disallowed\""),
                Arguments.of("data.html", "\"disallowed\""),
                Arguments.of("data.recipient.cc", "\"disallowed\""),
                Arguments.of("data.template_parameters.extra", "\"disallowed\""));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "{", "[]", "null", "{}"})
    void malformedAndTombstonesAreRejected(String payload, CapturedOutput output) {
        consumer.consume(record(KEY, payload));
        assertThat(delivered).isEmpty();
        assertThat(output.getAll()).contains("REJECTED").doesNotContain(OTP, DESTINATION);
    }

    @Test
    void rejectsDuplicateFieldsTrailingJsonAndSensitiveMalformedInput(CapturedOutput output) {
        for (String payload : List.of(VALID + "{}", VALID.replace("\"event_version\":1", "\"event_version\":1,\"event_version\":1"),
                VALID.substring(0, VALID.length() - 5))) {
            consumer.consume(record(KEY, payload));
        }
        assertThat(delivered).isEmpty();
        assertThat(output.getAll()).doesNotContain(OTP, DESTINATION);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "wrong-key", "recipient@example.invalid"})
    void rejectsMismatchedOrMissingKeys(String key, CapturedOutput output) {
        consumer.consume(record(key, VALID));
        assertThat(delivered).isEmpty();
        assertThat(output.getAll()).contains("KEY_MISMATCH").doesNotContain(OTP, DESTINATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-01-01t12:03:00z", "2026-01-01T12:03:00.123+00:00",
            "2026-01-01T12:03:00.123456789123Z"})
    void acceptsContractUtcRepresentations(String timestamp) {
        consumer.consume(record(KEY, changed("data.expires_at", "\"" + timestamp + "\"")));
        assertThat(delivered).hasSize(1);
    }

    @Test
    void reportsExpiryDuringDeliveryWithoutReportingAcceptance(CapturedOutput output) {
        var consumer = new NotificationCommandConsumer(request -> false, clock);
        consumer.consume(record(KEY, VALID));
        assertThat(output.getAll()).contains("REJECTED", "EXPIRED").doesNotContain("BOUNDARY_ACCEPTED");
    }

    @Test
    void boundaryFailureIsSurfacedWithoutSensitiveExceptionCause(CapturedOutput output) {
        var failing = new NotificationCommandConsumer(request -> {
            throw new IllegalStateException(OTP + " " + DESTINATION);
        }, clock);
        assertThatThrownBy(() -> failing.consume(record(KEY, VALID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Notification processing failed; consumer must stop").hasNoCause();
        assertThat(output.getAll()).contains("PROCESSING_FAILURE")
                .doesNotContain("BOUNDARY_ACCEPTED", OTP, DESTINATION);
    }

    @Test
    void duplicatesAreNotFalselySuppressed() {
        consumer.consume(record(KEY, VALID));
        consumer.consume(record(KEY, VALID));
        assertThat(delivered).hasSize(2);
    }

    @Test
    void everyContractFieldIsRequired() {
        var json = new JsonMapper();
        for (String path : List.of("event_id", "event_type", "event_version", "occurred_at", "producer",
                "correlation_id", "data", "data.channel", "data.purpose", "data.locale", "data.recipient",
                "data.recipient.destination", "data.template_id", "data.template_parameters",
                "data.template_parameters.otp_code", "data.expires_at")) {
            ObjectNode root = (ObjectNode) json.readTree(VALID);
            ObjectNode current = root;
            String[] parts = path.split("\\.");
            for (int i = 0; i < parts.length - 1; i++) current = (ObjectNode) current.get(parts[i]);
            current.remove(parts[parts.length - 1]);
            consumer.consume(record(KEY, json.writeValueAsString(root)));
        }
        assertThat(delivered).isEmpty();
    }

    private static String changed(String path, String value) {
        var json = JsonMapper.builder()
                .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
        ObjectNode root = (ObjectNode) json.readTree(VALID);
        ObjectNode current = root;
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) current = (ObjectNode) current.get(parts[i]);
        current.set(parts[parts.length - 1], json.readTree(value));
        return json.writeValueAsString(root);
    }

    static ConsumerRecord<String, String> record(String key, String payload) {
        return new ConsumerRecord<>("notification.commands", 0, 0, key, payload);
    }
}
