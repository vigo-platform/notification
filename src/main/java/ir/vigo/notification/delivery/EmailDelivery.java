package ir.vigo.notification.delivery;

import ir.vigo.notification.messaging.model.NotificationRequested;

/** Receives a validated, unexpired command. Future providers must recheck expiry at handoff. */
@FunctionalInterface
public interface EmailDelivery {
    void deliver(NotificationRequested request);
}
