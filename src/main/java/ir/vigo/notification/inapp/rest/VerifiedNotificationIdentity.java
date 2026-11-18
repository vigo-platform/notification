package ir.vigo.notification.inapp.rest;

import java.security.Principal;

/**
 * Integration contract implemented by JwtNotificationIdentity; runtime REST remains gated.
 * An Auth-approved adapter must accept only principals produced by service-owned
 * token verification and return the agreed canonical owner. It must reject every
 * other principal with Unauthorized. Never derive identity from request fields.
 */
public interface VerifiedNotificationIdentity {
    String requireOwner(Principal principal);

    final class Unauthorized extends RuntimeException {
        public Unauthorized() {
            super("Authentication required", null, false, false);
        }
    }
}
