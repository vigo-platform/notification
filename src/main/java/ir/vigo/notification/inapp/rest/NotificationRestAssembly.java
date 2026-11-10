package ir.vigo.notification.inapp.rest;

import java.time.Clock;
import javax.sql.DataSource;
import ir.vigo.notification.inapp.JdbcInAppNotifications;

/** Explicit assembly only; no configuration annotation, bean registration or auto-import. */
public final class NotificationRestAssembly {
    private NotificationRestAssembly() { }

    public static NotificationRestController prepare(DataSource dataSource, Clock clock,
            VerifiedNotificationIdentity verifiedIdentity) {
        return new NotificationRestController(new NotificationRestService(
                new JdbcInAppNotifications(dataSource, clock), verifiedIdentity), clock);
    }
}
