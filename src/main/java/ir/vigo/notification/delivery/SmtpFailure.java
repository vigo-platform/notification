package ir.vigo.notification.delivery;

/** Contains only a fixed reason. Relay replies can include the recipient or OTP. */
public final class SmtpFailure extends RuntimeException {
    public enum Reason {
        CONNECTION_FAILED, AUTH_FAILED, TEMPORARY_REJECTION, PERMANENT_REJECTION, ACCEPTANCE_UNKNOWN
    }

    private final Reason reason;

    public SmtpFailure(Reason reason) {
        super(reason.name(), null, false, false);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public boolean retryable() {
        return reason == Reason.CONNECTION_FAILED || reason == Reason.TEMPORARY_REJECTION;
    }
}
