package ir.vigo.notification.inapp.rest;

import java.time.Clock;
import javax.sql.DataSource;
import ir.vigo.notification.inapp.JdbcInAppNotifications;

/** Explicit assembly only; no configuration annotation, bean registration or auto-import. */
public final class NotificationRestAssembly {
    private NotificationRestAssembly() { }

    /** Both members must be registered together when runtime prerequisites are approved. */
    public record SecuredRest(NotificationRestController controller, NotificationBearerFilter filter) { }

    public static SecuredRest prepareSecured(DataSource dataSource, Clock clock,
            JwtNotificationIdentity identity) {
        return new SecuredRest(prepare(dataSource, clock, identity), new NotificationBearerFilter(identity));
    }

    public static NotificationRestController prepare(DataSource dataSource, Clock clock,
            VerifiedNotificationIdentity verifiedIdentity) {
        return new NotificationRestController(new NotificationRestService(
                new JdbcInAppNotifications(dataSource, clock), verifiedIdentity), clock);
    }
}
