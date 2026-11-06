package ir.vigo.notification.messaging.model;

import java.time.Instant;
import java.util.UUID;

/** Validated EMAIL v1 command; sensitive fields must never appear in diagnostics. */
public record NotificationRequested(UUID eventId, UUID correlationId, Instant occurredAt,
        String purpose, String locale, String destination, String templateId,
        String otpCode, Instant expiresAt) {
    @Override
    public String toString() {
        return "NotificationRequested[redacted]";
    }
}
