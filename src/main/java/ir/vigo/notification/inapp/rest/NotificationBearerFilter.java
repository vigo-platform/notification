package ir.vigo.notification.inapp.rest;

import java.io.IOException;
import java.security.Principal;
import java.util.Collections;
import java.util.Objects;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/** Explicit registration only. Authentication failures use the existing controller's 401 envelope. */
public final class NotificationBearerFilter extends OncePerRequestFilter {
    private final JwtNotificationIdentity identity;

    public NotificationBearerFilter(JwtNotificationIdentity identity) { this.identity = Objects.requireNonNull(identity); }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String root = "/api/v1/notification/notifications";
        return !path.equals(root) && !path.startsWith(root + "/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Principal verified = null;
        var headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.size() == 1) {
            String header = headers.getFirst();
            if (header.length() <= 16391 && header.regionMatches(true, 0, "Bearer ", 0, 7)
                    && header.substring(7).matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) {
                try { verified = identity.verify(header.substring(7)); }
                catch (VerifiedNotificationIdentity.Unauthorized ignored) { /* Controller returns generic 401. */ }
            }
        }
        Principal principal = verified;
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public Principal getUserPrincipal() { return principal; }
            @Override public String getRemoteUser() { return principal == null ? null : principal.getName(); }
            @Override public boolean isUserInRole(String role) { return false; }
        }, response);
    }
}
