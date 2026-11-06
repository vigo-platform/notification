package ir.vigo.notification.delivery;

import ir.vigo.notification.messaging.model.NotificationRequested;

@FunctionalInterface
public interface EmailDelivery {
    /** Returns true on relay acceptance, false if the command expired before submission. */
    boolean deliver(NotificationRequested request);
}
