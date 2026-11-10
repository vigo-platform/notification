package ir.vigo.notification.inapp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Internal stored content, not a REST DTO. Render title/body as plain text. */
public record InAppNotification(UUID id, String owner, String title, String body,
        Instant createdAt, Instant readAt) {
    public InAppNotification {
        Objects.requireNonNull(id, "id");
        requireText(owner, 255, "owner");
        requireText(title, 200, "title");
        requireText(body, 4000, "body");
        Objects.requireNonNull(createdAt, "createdAt");
        if (readAt != null && readAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("readAt precedes createdAt");
        }
    }

    static void requireText(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException("Invalid " + field);
        }
    }

    @Override
    public String toString() {
        return "InAppNotification[redacted]";
    }
}
