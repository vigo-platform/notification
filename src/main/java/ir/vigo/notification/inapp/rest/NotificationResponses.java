package ir.vigo.notification.inapp.rest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;
import ir.vigo.notification.inapp.InAppNotification;

/** Proposed v1 wire types. Internal owner and persistence details never serialize. */
public final class NotificationResponses {
    private NotificationResponses() { }

    public record Envelope(boolean success, String code, String message,
            @JsonProperty("message_fa") String messageFa, Object data, Instant timestamp) { }

    public record Item(UUID id, String title, String body,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("read_at") Instant readAt) {
        static Item from(InAppNotification value) {
            return new Item(value.id(), value.title(), value.body(), value.createdAt(), value.readAt());
        }
        @Override public String toString() { return "NotificationItem[redacted]"; }
    }

    public record Page(List<Item> items, int page, int size) {
        public Page { items = List.copyOf(items); }
    }

    public record UnreadCount(@JsonProperty("unread_count") long unreadCount) { }

    public record FieldError(String field, String code, String message,
            @JsonProperty("message_fa") String messageFa) { }
}
