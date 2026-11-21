package ir.vigo.notification.messaging;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.UUID;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class CoachApplicationEventParser {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    Result parse(byte[] key, byte[] payload) {
        JsonNode event = readEvent(payload);
        String type = event.path("event_type").asString("");
        boolean approved = "CoachApplicationApproved".equals(type);
        var version = event.path("event_version");
        if ((!approved && !"CoachApplicationRejected".equals(type))
                || !"user-service".equals(event.path("producer").asString(""))
                || !version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 2) {
            return null;
        }

        UUID eventId = uuid(text(event, "event_id"));
        String applicationId = text(event, "aggregate_id");
        uuid(applicationId);
        JsonNode data = event.path("data");
        if (!applicationId.equals(text(data, "application_id"))
                || !Arrays.equals(key, applicationId.getBytes(StandardCharsets.US_ASCII))) {
            throw new CommandRejected("KEY_MISMATCH");
        }
        if (!(approved ? "APPROVED" : "REJECTED").equals(text(data, "status"))) {
            throw new CommandRejected("STATUS_MISMATCH");
        }
        String locale = text(data, "locale");
        if (!"fa".equals(locale) && !"en".equals(locale)) {
            throw new CommandRejected("LOCALE");
        }
        String email = text(data, "email");
        validateMailbox(email);
        Instant expiresAt = approved ? deadline(text(data, "invitation_expires_at")) : null;
        var reason = data.path("reason");
        String rejectionReason = !approved && reason.isString() ? reason.stringValue().strip() : "";
        return new Result(eventId, email, locale, expiresAt, rejectionReason);
    }

    private static JsonNode readEvent(byte[] payload) {
        if (payload == null) {
            throw new CommandRejected("MALFORMED_JSON");
        }
        try {
            String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(payload)).toString();
            JsonNode event = JSON.readTree(json);
            if (event == null || !event.isObject()) {
                throw new CommandRejected("MALFORMED_JSON");
            }
            return event;
        } catch (CharacterCodingException | JacksonException ignored) {
            throw new CommandRejected("MALFORMED_JSON");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString()) {
            throw new CommandRejected("FIELD_TYPE");
        }
        return value.stringValue();
    }

    private static UUID uuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (id.toString().equalsIgnoreCase(value)) {
                return id;
            }
        } catch (IllegalArgumentException ignored) {
            // Do not retain untrusted input or exception messages.
        }
        throw new CommandRejected("UUID_FORMAT");
    }

    private static void validateMailbox(String value) {
        if (value.length() <= 254 && value.chars().noneMatch(Character::isISOControl)) {
            try {
                var address = new InternetAddress(value, true);
                address.validate();
                if (!address.isGroup() && value.equals(address.getAddress())) {
                    return;
                }
            } catch (AddressException ignored) {
                // AddressException contains the input address.
            }
        }
        throw new CommandRejected("DESTINATION");
    }

    private static Instant deadline(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            throw new CommandRejected("INVITATION_DEADLINE");
        }
    }

    record Result(UUID eventId, String email, String locale, Instant expiresAt, String rejectionReason) {
        @Override
        public String toString() {
            return "CoachApplicationResult[redacted]";
        }
    }
}
