package ir.vigo.notification.delivery;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("notification.smtp")
record SmtpProperties(String host, int port, Security security, boolean auth, String username, String password,
        String fromAddress, String fromName, int connectTimeoutMs, int readTimeoutMs, int writeTimeoutMs) {

    enum Security { STARTTLS, TLS, NONE }

    SmtpProperties {
        if (host == null || host.isBlank() || host.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("SMTP_HOST must contain the SMTP relay hostname or IP address");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("SMTP_PORT must be between 1 and 65535");
        }
        if (security == null) {
            throw new IllegalArgumentException("SMTP_SECURITY must be STARTTLS, TLS or NONE");
        }
        if (auth && security == Security.NONE) {
            throw new IllegalArgumentException("SMTP_AUTH must be false when SMTP_SECURITY is NONE");
        }
        if (auth && (username == null || username.isBlank() || password == null || password.isBlank())) {
            throw new IllegalArgumentException("SMTP_USERNAME and SMTP_PASSWORD are required when SMTP_AUTH is true");
        }
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0 || writeTimeoutMs <= 0) {
            throw new IllegalArgumentException("SMTP timeouts must be positive milliseconds");
        }
        validateFromAddress(fromAddress);
        if (fromName != null && fromName.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("SMTP_FROM_NAME must not contain control characters");
        }
    }

    private static void validateFromAddress(String value) {
        try {
            if (value != null && !value.isBlank()) {
                var address = new InternetAddress(value, true);
                address.validate();
                if (!address.isGroup() && value.equals(address.getAddress())) {
                    return;
                }
            }
        } catch (AddressException ignored) {
            // AddressException includes the original input.
        }
        throw new IllegalArgumentException("SMTP_FROM_ADDRESS must be a single email address");
    }

    @Override
    public String toString() {
        return "SmtpProperties[redacted]";
    }
}
