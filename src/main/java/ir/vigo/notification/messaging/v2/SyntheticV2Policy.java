package ir.vigo.notification.messaging.v2;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static ir.vigo.notification.messaging.v2.V2CommandParser.Channel.IN_APP;
import static ir.vigo.notification.messaging.v2.V2CommandParser.Rejection.*;

/** This registration is only for disposable local fixtures. A Kafka producer claim carries no principal. */
final class SyntheticV2Policy {
    private static final String PUBLISHER = "user-service";
    private static final Map<String, Map<String, Copy>> CATALOG = Map.of(
            "notification.coach_application.approved.v1", Map.of(
                    "en", new Copy("Coach application approved", "Your coach application was approved."),
                    "fa", new Copy("تأیید درخواست مربیگری", "درخواست مربیگری شما تأیید شد.")),
            "notification.coach_application.rejected.v1", Map.of(
                    "en", new Copy("Coach application rejected", "Your coach application was rejected."),
                    "fa", new Copy("رد درخواست مربیگری", "درخواست مربیگری شما رد شد.")));
    private final Set<String> syntheticUsers;

    SyntheticV2Policy(String users) {
        syntheticUsers = Arrays.stream(users.split(",", -1)).collect(Collectors.toUnmodifiableSet());
        if (syntheticUsers.stream().anyMatch(user -> !V2CommandParser.isUserId(user))) {
            throw new IllegalStateException("Kafka v2 requires explicit canonical synthetic user IDs");
        }
    }

    Copy authorize(V2CommandParser.Command command) {
        if (!PUBLISHER.equals(command.producer())) throw new V2CommandParser.Rejected(PRODUCER_NOT_REGISTERED);
        var template = CATALOG.get(command.template());
        if (template == null) throw new V2CommandParser.Rejected(TEMPLATE_NOT_REGISTERED);
        if (command.hasParameters()) throw new V2CommandParser.Rejected(PARAMETERS);
        if (!command.recipients().keySet().equals(Set.of(IN_APP))) {
            throw new V2CommandParser.Rejected(UNSUPPORTED_CHANNEL);
        }
        if (!allowsRecipient(command.recipients().get(IN_APP))) {
            throw new V2CommandParser.Rejected(RECIPIENT_NOT_AUTHORIZED);
        }
        return template.get(command.locale());
    }

    boolean allowsRecipient(String owner) {
        return syntheticUsers.contains(owner);
    }

    record Copy(String title, String body) {}
}
