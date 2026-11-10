package ir.vigo.notification.inapp.rest;

import java.security.Principal;
import java.util.Objects;
import java.util.UUID;
import ir.vigo.notification.inapp.JdbcInAppNotifications;

/** Authenticate before validation or database access. No owner argument in the REST API. */
public final class NotificationRestService {
    private final JdbcInAppNotifications store;
    private final VerifiedNotificationIdentity identity;

    public NotificationRestService(JdbcInAppNotifications store, VerifiedNotificationIdentity identity) {
        this.store = Objects.requireNonNull(store);
        this.identity = Objects.requireNonNull(identity);
    }

    public NotificationResponses.Page list(Principal principal, String pageText, String sizeText) {
        String owner = owner(principal);
        int page = number(pageText, 0, Integer.MAX_VALUE, "page");
        int size = number(sizeText, 1, 100, "size");
        return new NotificationResponses.Page(store.list(owner, page, size).stream()
                .map(NotificationResponses.Item::from).toList(), page, size);
    }

    public NotificationResponses.UnreadCount count(Principal principal) {
        return new NotificationResponses.UnreadCount(store.unreadCount(owner(principal)));
    }

    public boolean markRead(Principal principal, String idText) {
        String owner = owner(principal);
        if (idText == null || !idText.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new InvalidField("id");
        }
        return store.markRead(owner, UUID.fromString(idText));
    }

    private String owner(Principal principal) {
        if (principal == null) throw new VerifiedNotificationIdentity.Unauthorized();
        String owner = identity.requireOwner(principal);
        if (owner == null || owner.isBlank() || owner.length() > 255) {
            throw new VerifiedNotificationIdentity.Unauthorized();
        }
        return owner;
    }

    private static int number(String text, int min, int max, String field) {
        if (text == null || text.length() > 10 || !text.matches("[0-9]+")) throw new InvalidField(field);
        try {
            int value = Integer.parseInt(text);
            if (value < min || value > max) throw new InvalidField(field);
            return value;
        } catch (NumberFormatException invalid) {
            throw new InvalidField(field);
        }
    }

    static final class InvalidField extends RuntimeException {
        final String field;
        InvalidField(String field) {
            super("Invalid request field", null, false, false);
            this.field = field;
        }
    }
}
