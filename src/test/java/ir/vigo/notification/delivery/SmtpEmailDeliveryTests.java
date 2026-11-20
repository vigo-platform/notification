package ir.vigo.notification.delivery;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import ir.vigo.notification.messaging.model.NotificationRequested;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SmtpEmailDeliveryTests {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String RECIPIENT = "otp@example.invalid";
    private static final String OTP = "012345";

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void reportsActualRelayAcceptanceOrSanitizedFailure(boolean accepted) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var relay = loopbackRelay()) {
            var received = executor.submit(() -> exchange(relay, false,
                    accepted ? "250 accepted" : "550 rejected " + RECIPIENT + " " + OTP));
            var delivery = delivery(relay, Clock.fixed(NOW, ZoneOffset.UTC));
            if (accepted) {
                assertThat(delivery.deliver(request())).isTrue();
            } else {
                assertThatThrownBy(() -> delivery.deliver(request()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("SMTP email delivery failed").hasNoCause();
            }

            var wireMessage = received.get(5, TimeUnit.SECONDS);
            var message = new MimeMessage(Session.getInstance(new Properties()),
                    new ByteArrayInputStream(wireMessage.getBytes(StandardCharsets.US_ASCII)));
            assertThat(message.getHeader("Content-Language", null)).isEqualTo("fa");
            assertThat(message.getAllRecipients()).extracting(Object::toString).containsExactly(RECIPIENT);
            assertThat(message.getSubject()).isEqualTo("کد ورود به ویگو");
            assertThat(message.isMimeType("multipart/alternative")).isTrue();
            var content = (MimeMultipart) message.getContent();
            assertThat(content.getBodyPart(0).getContent().toString()).contains(OTP);
            assertThat(content.getBodyPart(1).getContent().toString()).contains("dir=\"rtl\"", OTP);
        }
    }

    @Test
    void expiryAfterConnectingDoesNotSubmitMail() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var relay = loopbackRelay()) {
            var received = executor.submit(() -> exchange(relay, true, ""));
            Clock clock = mock(Clock.class);
            when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(1));
            assertThat(delivery(relay, clock).deliver(request())).isFalse();
            assertThat(received.get(5, TimeUnit.SECONDS)).isEmpty();
        }
    }

    private static ServerSocket loopbackRelay() throws Exception {
        var relay = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        relay.setSoTimeout(5000);
        return relay;
    }

    private static SmtpEmailDelivery delivery(ServerSocket relay, Clock clock) {
        var settings = new SmtpProperties("127.0.0.1", relay.getLocalPort(), SmtpProperties.Security.NONE,
                false, null, null, "sender@example.invalid", "Vigo", 5000, 5000, 5000);
        return new SmtpEmailDelivery(settings, clock);
    }

    private static NotificationRequested request() {
        return new NotificationRequested(UUID.randomUUID(), UUID.randomUUID(), NOW,
                "LOGIN_OTP", "fa", RECIPIENT, "auth.login_otp.email.v1", OTP, NOW.plusSeconds(1));
    }

    private static String exchange(ServerSocket relay, boolean expires, String result) throws Exception {
        try (var socket = relay.accept()) {
            socket.setSoTimeout(5000);
            var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            var output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
            reply(output, "220 localhost");
            assertThat(input.readLine()).startsWith("EHLO ");
            reply(output, "250 localhost");
            if (expires) {
                assertThat(input.readLine()).isEqualTo("QUIT");
                return "";
            }
            assertThat(input.readLine()).startsWith("MAIL FROM:");
            reply(output, "250 sender accepted");
            assertThat(input.readLine()).startsWith("RCPT TO:<" + RECIPIENT + ">");
            reply(output, "250 recipient accepted");
            assertThat(input.readLine()).isEqualTo("DATA");
            reply(output, "354 send message");
            var message = new StringBuilder();
            for (String line = input.readLine(); line != null; line = input.readLine()) {
                if (line.equals(".")) {
                    reply(output, result);
                    return message.toString();
                }
                message.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
            }
            throw new IllegalStateException("SMTP client disconnected before submitting the message");
        }
    }

    private static void reply(BufferedWriter output, String response) throws Exception {
        output.write(response);
        output.write("\r\n");
        output.flush();
    }
}
