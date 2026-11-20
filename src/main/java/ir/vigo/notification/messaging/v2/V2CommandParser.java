package ir.vigo.notification.messaging.v2;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.node.ObjectNode;

/** Hand validation of the pinned Draft 2020-12 contract at ef8451690372b948c500d26642201bf57db4d1fa. */
final class V2CommandParser {
    private static final Set<String> ENVELOPE = Set.of(
            "event_id", "event_type", "event_version", "occurred_at", "producer", "correlation_id", "data");
    private static final Set<String> DATA = Set.of("locale", "template_id", "template_parameters", "deliveries");
    private static final Set<String> DISALLOWED_PARAMETERS = Set.of("password", "plaintext_password", "token",
            "access_token", "refresh_token", "credential", "credentials", "secret", "api_key", "otp", "otp_code");
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern TIMESTAMP = Pattern.compile(
            "([0-9]{4}-[0-9]{2}-[0-9]{2})[Tt]([0-9]{2}:[0-9]{2}:[0-5][0-9])(\\.[0-9]+)?(?:[Zz]|\\+00:00)");
    private static final String ATOM = "[A-Za-z0-9!#$%&'*+/=?^_\u0060{|}~-]+";
    private static final String LABEL = "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?";
    private static final Pattern MAILBOX = Pattern.compile(
            "(?=.{1,64}@)" + ATOM + "(?:\\." + ATOM + ")*@" + LABEL + "(?:\\." + LABEL + ")*");
    private static final Pattern PARAMETER_VALUE = Pattern.compile("[^<>\\x00-\\x1F\\x7F]+");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED).build();

    enum Channel { EMAIL, IN_APP }
    enum Rejection {
        JSON, FIELDS, EVENT_TYPE, VERSION, UUID_FORMAT, KEY, PRODUCER_FORMAT, TIMESTAMP,
        EXPIRY_ORDER, LOCALE, TEMPLATE_FORMAT, PARAMETERS, CHANNEL, RECIPIENT,
        PRODUCER_NOT_REGISTERED, TEMPLATE_NOT_REGISTERED, UNSUPPORTED_CHANNEL, RECIPIENT_NOT_AUTHORIZED,
        IDENTITY_CONFLICT
    }

    static final class Rejected extends RuntimeException {
        final Rejection reason;

        Rejected(Rejection reason) {
            super(reason.name(), null, false, false);
            this.reason = reason;
        }
    }

    record Command(String producer, UUID eventId, String locale, String template, boolean hasParameters,
            Map<Channel, String> recipients, BigDecimal expiresAt, String fingerprint) {
        @Override public String toString() { return "V2Command[redacted]"; }
    }

    Command parse(byte[] key, byte[] payload) {
        if (payload == null) throw new Rejected(Rejection.JSON);
        JsonNode root;
        try {
            root = JSON.readTree(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(payload)).toString());
        } catch (JacksonException | CharacterCodingException malformed) {
            throw new Rejected(Rejection.JSON);
        }
        object(root, ENVELOPE, Set.of());
        UUID event = uuid(string(root, "event_id"));
        uuid(string(root, "correlation_id"));
        if (!"NotificationRequested".equals(string(root, "event_type"))) throw new Rejected(Rejection.EVENT_TYPE);
        JsonNode version = root.get("event_version");
        if (!version.isNumber() || version.decimalValue().compareTo(BigDecimal.valueOf(2)) != 0)
            throw new Rejected(Rejection.VERSION);
        String producer = string(root, "producer");
        if (producer.length() > 64 || !producer.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*-service"))
            throw new Rejected(Rejection.PRODUCER_FORMAT);
        if (!MessageDigest.isEqual((producer + ":" + event).getBytes(StandardCharsets.US_ASCII),
                key == null ? new byte[0] : key)) throw new Rejected(Rejection.KEY);
        BigDecimal occurredAt = timestamp(string(root, "occurred_at"));

        JsonNode data = root.get("data");
        object(data, DATA, Set.of("expires_at"));
        BigDecimal expiry = data.has("expires_at") ? timestamp(string(data, "expires_at")) : null;
        if (expiry != null && expiry.compareTo(occurredAt) <= 0) throw new Rejected(Rejection.EXPIRY_ORDER);
        String locale = string(data, "locale");
        if (!Set.of("fa", "en").contains(locale)) throw new Rejected(Rejection.LOCALE);
        String template = string(data, "template_id");
        if (template.length() > 128 || !template.matches("notification\\.[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)*\\.v[1-9][0-9]*"))
            throw new Rejected(Rejection.TEMPLATE_FORMAT);
        JsonNode parameters = data.get("template_parameters");
        validateParameters(parameters);

        JsonNode deliveries = data.get("deliveries");
        if (!deliveries.isArray() || deliveries.size() < 1 || deliveries.size() > 2)
            throw new Rejected(Rejection.CHANNEL);
        Map<Channel, String> recipients = new EnumMap<>(Channel.class);
        var sorted = new TreeMap<String, JsonNode>();
        for (JsonNode delivery : deliveries) {
            object(delivery, Set.of("channel", "recipient"), Set.of());
            String name = string(delivery, "channel");
            Channel channel = switch (name) {
                case "EMAIL" -> Channel.EMAIL;
                case "IN_APP" -> Channel.IN_APP;
                default -> throw new Rejected(Rejection.CHANNEL);
            };
            if (sorted.putIfAbsent(name, delivery) != null) throw new Rejected(Rejection.CHANNEL);
            recipients.put(channel, recipient(channel, delivery.get("recipient")));
        }

        // Normalize only the mathematical version number and ordering. Preserve all text and optional fields.
        ((ObjectNode) root).put("event_version", 2);
        var canonicalDeliveries = ((ObjectNode) data).putArray("deliveries");
        sorted.values().forEach(canonicalDeliveries::add);
        return new Command(producer, event, locale, template, !parameters.isEmpty(),
                Map.copyOf(recipients), expiry, fingerprint(root));
    }

    private static void validateParameters(JsonNode parameters) {
        if (!parameters.isObject() || parameters.size() > 16) throw new Rejected(Rejection.PARAMETERS);
        for (String name : parameters.propertyNames()) {
            if (name.length() > 64 || !name.matches("[a-z][a-z0-9_]*") || DISALLOWED_PARAMETERS.contains(name))
                throw new Rejected(Rejection.PARAMETERS);
            String value = string(parameters, name);
            if (value.codePointCount(0, value.length()) > 256 || !PARAMETER_VALUE.matcher(value).matches())
                throw new Rejected(Rejection.PARAMETERS);
        }
    }

    private static String recipient(Channel channel, JsonNode recipient) {
        if (channel == Channel.IN_APP) {
            object(recipient, Set.of("user_id"), Set.of());
            String user = string(recipient, "user_id");
            if (!isUserId(user)) throw new Rejected(Rejection.RECIPIENT);
            return user;
        }
        object(recipient, Set.of("destination"), Set.of());
        String address = string(recipient, "destination");
        if (address.length() > 254 || !MAILBOX.matcher(address).matches()) throw new Rejected(Rejection.RECIPIENT);
        return address;
    }

    static boolean isUserId(String value) {
        return value.matches("[1-9][0-9]{0,18}")
                && (value.length() < 19 || value.compareTo("9223372036854775807") <= 0);
    }

    private static UUID uuid(String value) {
        if (!UUID_TEXT.matcher(value).matches()) throw new Rejected(Rejection.UUID_FORMAT);
        return UUID.fromString(value);
    }

    private static BigDecimal timestamp(String value) {
        var parts = TIMESTAMP.matcher(value);
        if (!parts.matches() || value.startsWith("0000-")) throw new Rejected(Rejection.TIMESTAMP);
        try {
            long wholeSeconds = LocalDateTime.parse(parts.group(1) + "T" + parts.group(2)).toEpochSecond(ZoneOffset.UTC);
            return parts.group(3) == null ? BigDecimal.valueOf(wholeSeconds)
                    : BigDecimal.valueOf(wholeSeconds).add(new BigDecimal(parts.group(3)));
        } catch (DateTimeParseException invalid) {
            throw new Rejected(Rejection.TIMESTAMP);
        }
    }

    static BigDecimal epoch(Instant instant) {
        return BigDecimal.valueOf(instant.getEpochSecond()).add(BigDecimal.valueOf(instant.getNano(), 9));
    }

    private static String fingerprint(JsonNode root) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(root)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static void object(JsonNode node, Set<String> required, Set<String> optional) {
        if (node == null || !node.isObject() || !node.propertyNames().containsAll(required)
                || node.propertyNames().stream().anyMatch(name -> !required.contains(name) && !optional.contains(name)))
            throw new Rejected(Rejection.FIELDS);
    }

    private static String string(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) throw new Rejected(Rejection.FIELDS);
        return value.stringValue();
    }
}
