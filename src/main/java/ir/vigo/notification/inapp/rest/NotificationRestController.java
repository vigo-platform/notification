package ir.vigo.notification.inapp.rest;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notification/notifications")
@Conditional(NotificationRestController.AuthContractPending.class)
public final class NotificationRestController {
    private final NotificationRestService service;
    private final Clock clock;

    public NotificationRestController(NotificationRestService service, Clock clock) {
        this.service = Objects.requireNonNull(service);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Hard gate: no environment variable/profile can enable an unverified HTTP API. */
    public static final class AuthContractPending implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return false;
        }
    }

    @GetMapping
    public ResponseEntity<NotificationResponses.Envelope> list(HttpServletRequest request) {
        var data = service.list(request.getUserPrincipal(), parameter(request, "page", "0"),
                parameter(request, "size", "20"));
        return response(200, "NOTIFICATIONS_LISTED", "Notifications listed", "اعلان‌ها دریافت شدند", data);
    }

    @GetMapping("/unread-count")
    public ResponseEntity<NotificationResponses.Envelope> count(HttpServletRequest request) {
        return response(200, "UNREAD_COUNT_RETURNED", "Unread count returned", "تعداد اعلان‌های خوانده‌نشده دریافت شد",
                service.count(request.getUserPrincipal()));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<NotificationResponses.Envelope> markRead(HttpServletRequest request, @PathVariable String id) {
        if (!service.markRead(request.getUserPrincipal(), id)) {
            return response(404, "NOTIFICATION_NOT_FOUND", "Notification not found", "اعلان یافت نشد", null);
        }
        return response(200, "NOTIFICATION_READ", "Notification marked read", "اعلان خوانده شد", null);
    }

    @ExceptionHandler(VerifiedNotificationIdentity.Unauthorized.class)
    public ResponseEntity<NotificationResponses.Envelope> unauthorized() {
        return ResponseEntity.status(401).header("Cache-Control", "no-store")
                .header("WWW-Authenticate", "Bearer")
                .body(envelope(401, "UNAUTHORIZED", "Authentication required", "احراز هویت لازم است", null));
    }

    @ExceptionHandler(NotificationRestService.InvalidField.class)
    public ResponseEntity<NotificationResponses.Envelope> invalid(NotificationRestService.InvalidField failure) {
        var error = new NotificationResponses.FieldError(failure.field, "INVALID_VALUE",
                "Invalid value", "مقدار نامعتبر است");
        return response(400, "VALIDATION_FAILED", "Request validation failed", "درخواست نامعتبر است", List.of(error));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<NotificationResponses.Envelope> failed() {
        return response(500, "INTERNAL_ERROR", "Unable to process notification request", "پردازش درخواست امکان‌پذیر نیست", null);
    }

    private static String parameter(HttpServletRequest request, String name, String fallback) {
        String[] values = request.getParameterValues(name);
        if (values == null) return fallback;
        // Let the service authenticate before rejecting duplicated or empty parameters.
        return values.length == 1 ? values[0] : "";
    }

    private ResponseEntity<NotificationResponses.Envelope> response(int status, String code,
            String message, String messageFa, Object data) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(envelope(status, code, message, messageFa, data));
    }

    private NotificationResponses.Envelope envelope(int status, String code,
            String message, String messageFa, Object data) {
        return new NotificationResponses.Envelope(status < 400, code, message, messageFa, data, clock.instant());
    }
}
