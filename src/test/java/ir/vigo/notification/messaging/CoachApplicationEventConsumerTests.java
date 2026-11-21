package ir.vigo.notification.messaging;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import ir.vigo.notification.delivery.EmailContent;
import ir.vigo.notification.delivery.SmtpFailure;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class CoachApplicationEventConsumerTests {
    private static final String APPLICATION = "c874fda7-4713-4614-b3fa-fb4b12ff0af4";
    private static final String REGISTRATION = "https://vigoapp.ir/register";
    private static final String APPROVED = """
            {
              "event_id": "6b320ea9-ad81-4a7f-9593-cf1a50feddec",
              "event_type": "CoachApplicationApproved",
              "event_version": 2,
              "producer": "user-service",
              "aggregate_id": "%s",
              "data": {
                "application_id": "%s",
                "full_name": "علی رضایی",
                "status": "APPROVED",
                "locale": "fa",
                "email": "coach@example.invalid",
                "invitation_token": "confidential-invitation-token",
                "invitation_expires_at": "2026-12-20T12:00:00Z"
              }
            }
            """.formatted(APPLICATION, APPLICATION);
    private static final String REJECTED = APPROVED
            .replace("6b320ea9-ad81-4a7f-9593-cf1a50feddec", "085a208b-e403-4ce2-8aa7-10f2bb446deb")
            .replace("CoachApplicationApproved", "CoachApplicationRejected")
            .replace("APPROVED", "REJECTED")
            .replace("\"fa\"", "\"en\"")
            .replace("\"invitation_token\": \"confidential-invitation-token\",",
                    "\"reason\": \"<b>confidential-rejection-reason</b>\\r\\nSecond line\"")
            .replace("\"invitation_expires_at\": \"2026-12-20T12:00:00Z\"", "");

    private JdbcDataSource database;

    @BeforeEach
    void createInbox() throws Exception {
        database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (var connection = database.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE coach_application_email (event_id UUID PRIMARY KEY)");
        }
    }

    @Test
    void sendsLocalizedResultsAndDeduplicatesAcrossConsumerInstances(CapturedOutput output) {
        List<EmailContent> sent = new ArrayList<>();
        BiConsumer<String, EmailContent> relay = (recipient, content) -> {
            assertThat(recipient).isEqualTo("coach@example.invalid");
            sent.add(content);
        };
        consumer(relay).onMessage(record(APPLICATION, APPROVED));
        consumer(relay).onMessage(record(APPLICATION, APPROVED));
        consumer(relay).onMessage(record(APPLICATION, REJECTED));

        assertThat(sent).satisfiesExactly(approved -> {
            assertThat(approved.language()).isEqualTo("fa");
            assertThat(approved.text()).contains("تأیید شد", "همان نشانی ایمیل", "۲۹ آذر ۱۴۰۵ · ۲۰ دسامبر ۲۰۲۶",
                    REGISTRATION).doesNotContain("confidential-invitation-token", "12:00", "UTC");
            assertThat(approved.html()).contains("dir=\"rtl\"", "href=\"" + REGISTRATION + "\"",
                    "مهلت ثبت‌نام", "Vazirmatn", "۲۹ آذر ۱۴۰۵", "۲۰ دسامبر ۲۰۲۶", "cid:vigo-logo");
        }, rejected -> {
            assertThat(rejected.language()).isEqualTo("en");
            assertThat(rejected.text()).contains("Your application wasn’t approved", "Reason for the decision",
                    "<b>confidential-rejection-reason</b>\nSecond line").doesNotContain(REGISTRATION);
            assertThat(rejected.html()).contains("dir=\"ltr\"",
                    "&lt;b&gt;confidential-rejection-reason&lt;/b&gt;<br>Second line")
                    .doesNotContain("<b>confidential-rejection-reason</b>", REGISTRATION);
        });
        assertThat(output.getAll()).doesNotContain("confidential-invitation-token", "confidential-rejection-reason",
                "coach@example.invalid", "علی رضایی");
    }

    @Test
    void usesTheSameTehranDayForBothCalendarsNearMidnight() {
        var consumer = consumer((recipient, content) -> {
            assertThat(content.text()).contains("30 Azar 1405 · 21 December 2026").doesNotContain("21:00", "UTC");
            assertThat(content.html()).contains("30 Azar 1405", "21 December 2026");
        });
        consumer.onMessage(record(APPLICATION, APPROVED.replace("\"fa\"", "\"en\"")
                .replace("2026-12-20T12:00:00Z", "2026-12-20T21:00:00Z")));
    }

    @Test
    void ignoresOtherEventsAndRejectsMalformedMatchingEvents(CapturedOutput output) {
        var consumer = consumer((recipient, content) -> { throw new AssertionError("Unexpected delivery"); });
        consumer.onMessage(record(APPLICATION, APPROVED.replace("CoachApplicationApproved", "CoachApplicationSubmitted")
                .replace("\"event_version\": 2", "\"event_version\": 1")));
        consumer.onMessage(record(APPLICATION, APPROVED.replace("\"event_version\": 2", "\"event_version\": 1")));
        consumer.onMessage(record(APPLICATION, APPROVED.replace("user-service", "other-service")));
        consumer.onMessage(record(UUID.randomUUID().toString(), APPROVED));
        consumer.onMessage(record(APPLICATION, APPROVED.replace("\"status\": \"APPROVED\"", "\"status\": \"REJECTED\"")));
        consumer.onMessage(record(APPLICATION, APPROVED + " confidential-trailing-input"));
        consumer.onMessage(new ConsumerRecord<>("user.events", 0, 0, utf8(APPLICATION), new byte[] {(byte) 0xff}));
        assertThat(output.getAll()).contains("KEY_MISMATCH", "STATUS_MISMATCH", "MALFORMED_JSON")
                .doesNotContain("confidential-invitation-token", "confidential-trailing-input");
    }

    @Test
    void releasesClaimForKnownTransientFailureAndKeepsItForUnknownAcceptance() {
        var attempts = new AtomicInteger();
        var consumer = consumer((recipient, content) -> {
            if (attempts.incrementAndGet() == 1) {
                throw new SmtpFailure(SmtpFailure.Reason.TEMPORARY_REJECTION);
            }
            throw new SmtpFailure(SmtpFailure.Reason.ACCEPTANCE_UNKNOWN);
        });
        var message = record(APPLICATION, APPROVED);
        assertThatThrownBy(() -> consumer.onMessage(message)).isInstanceOf(SmtpFailure.class);
        consumer.onMessage(message);
        consumer.onMessage(message);
        assertThat(attempts).hasValue(2);
    }

    @Test
    void concurrentRedeliveryClaimsTheEventOnce() throws Exception {
        var submissions = new AtomicInteger();
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var message = record(APPLICATION, APPROVED);
            BiConsumer<String, EmailContent> relay = (recipient, content) -> submissions.incrementAndGet();
            var first = tasks.submit(() -> consumer(relay).onMessage(message));
            var duplicate = tasks.submit(() -> consumer(relay).onMessage(message));
            first.get();
            duplicate.get();
        }
        assertThat(submissions).hasValue(1);
    }

    private CoachApplicationEventConsumer consumer(BiConsumer<String, EmailContent> relay) {
        return new CoachApplicationEventConsumer(new CoachApplicationEmailInbox(database), relay, REGISTRATION);
    }

    private static ConsumerRecord<byte[], byte[]> record(String key, String body) {
        return new ConsumerRecord<>("user.events", 0, 0, utf8(key), utf8(body));
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
