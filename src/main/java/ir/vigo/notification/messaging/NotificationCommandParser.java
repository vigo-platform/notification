package ir.vigo.notification.messaging;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import ir.vigo.notification.messaging.model.NotificationRequested;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicit v1 validation against api-contracts 7e3a792. No coercion or open objects. */
final class NotificationCommandParser {
    private static final Map<String, String> TEMPLATES = Map.of(
            "LOGIN_OTP", "auth.login_otp.email.v1",
            "REGISTRATION_OTP", "auth.registration_otp.email.v1",
            "PASSWORD_CHANGE_OTP", "auth.password_change_otp.email.v1",
            "PASSWORD_RESET_OTP", "auth.password_reset_otp.email.v1");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern TIMESTAMP = Pattern.compile(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-5][0-9](\\.[0-9]+)?([Zz]|\\+00:00)");
    private static final Pattern MAILBOX = Pattern.compile(
            "(?=.{1,64}@)[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*"
            + "@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
            + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*");
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    NotificationRequested parse(String key, String payload) {
        if (payload == null) throw new CommandRejected("MALFORMED_JSON");
        JsonNode root;
        try {
            root = json.readTree(payload);
        } catch (JacksonException e) {
            throw new CommandRejected("MALFORMED_JSON");
        }
        object(root, Set.of("event_id", "event_type", "event_version", "occurred_at",
                "producer", "correlation_id", "data"));
        String eventId = text(root, "event_id");
        UUID id = uuid(eventId);
        if (!eventId.equals(key)) throw new CommandRejected("KEY_MISMATCH");
        equal(text(root, "event_type"), "NotificationRequested", "EVENT_TYPE");
        JsonNode version = root.get("event_version");
        if (!version.isNumber() || version.decimalValue().compareTo(java.math.BigDecimal.ONE) != 0)
            throw new CommandRejected("VERSION");
        equal(text(root, "producer"), "auth-service", "PRODUCER");
        UUID correlationId = uuid(text(root, "correlation_id"));
        Instant occurredAt = timestamp(text(root, "occurred_at"));
        JsonNode data = root.get("data");
        object(data, Set.of("channel", "purpose", "locale", "recipient", "template_id",
                "template_parameters", "expires_at"));
        equal(text(data, "channel"), "EMAIL", "CHANNEL");
        String purpose = text(data, "purpose");
        if (!TEMPLATES.containsKey(purpose)) throw new CommandRejected("PURPOSE");
        String locale = text(data, "locale");
        if (!Set.of("fa", "en").contains(locale)) throw new CommandRejected("LOCALE");
        String template = text(data, "template_id");
        equal(template, TEMPLATES.get(purpose), "TEMPLATE_MISMATCH");
        JsonNode recipient = data.get("recipient");
        object(recipient, Set.of("destination"));
        String destination = text(recipient, "destination");
        if (destination.length() > 254 || !MAILBOX.matcher(destination).matches())
            throw new CommandRejected("DESTINATION");
        JsonNode parameters = data.get("template_parameters");
        object(parameters, Set.of("otp_code"));
        String otp = text(parameters, "otp_code");
        if (!otp.matches("[0-9]{6}")) throw new CommandRejected("OTP_FORMAT");
        return new NotificationRequested(id, correlationId, occurredAt, purpose, locale,
                destination, template, otp, timestamp(text(data, "expires_at")));
    }

    private static void object(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || !node.propertyNames().equals(fields))
            throw new CommandRejected("OBJECT_FIELDS");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) throw new CommandRejected("FIELD_TYPE");
        return value.stringValue();
    }

    private static void equal(String value, String expected, String reason) {
        if (!expected.equals(value)) throw new CommandRejected(reason);
    }

    private static UUID uuid(String value) {
        if (!UUID_PATTERN.matcher(value).matches()) throw new CommandRejected("UUID_FORMAT");
        return UUID.fromString(value);
    }

    private static Instant timestamp(String value) {
        if (!TIMESTAMP.matcher(value).matches() || value.startsWith("0000-"))
            throw new CommandRejected("TIMESTAMP_FORMAT");
        // The contract permits arbitrary fractional precision. Truncate beyond nanoseconds:
        // this is conservative for expiry and does not extend validity.
        String normalized = value.replace('t', 'T').replace('z', 'Z')
                .replaceFirst("(\\.[0-9]{9})[0-9]+", "$1");
        try {
            return OffsetDateTime.parse(normalized).toInstant();
        } catch (DateTimeParseException e) {
            throw new CommandRejected("TIMESTAMP_FORMAT");
        }
    }
}
