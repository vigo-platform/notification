package ir.vigo.notification.messaging;

/** Only application-owned reason codes; never retain parser exceptions or input values. */
final class CommandRejected extends RuntimeException {
    CommandRejected(String reason) {
        super(reason, null, false, false);
    }
}
