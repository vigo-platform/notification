package ir.vigo.notification.delivery;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import ir.vigo.notification.messaging.model.NotificationRequested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class SmtpEmailDeliveryTests {
    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");
    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final OtpEmailRenderer renderer = new OtpEmailRenderer();

    private NotificationRequested request(String purpose, String locale, Instant expiry) {
        return new NotificationRequested(UUID.randomUUID(), UUID.randomUUID(), NOW, purpose, locale,
                "recipient@example.invalid", "auth.login.otp.v1", "001234", expiry);
    }

    @ParameterizedTest
    @CsvSource({"LOGIN_OTP,en,sign in", "REGISTRATION_OTP,en,registration",
            "PASSWORD_CHANGE_OTP,en,password change", "PASSWORD_RESET_OTP,en,password reset",
            "LOGIN_OTP,fa,ورود", "REGISTRATION_OTP,fa,ثبت‌نام",
            "PASSWORD_CHANGE_OTP,fa,تغییر رمز عبور", "PASSWORD_RESET_OTP,fa,بازیابی رمز عبور"})
    void sendsLocalizedPlainText(String purpose, String locale, String phrase, CapturedOutput output) {
        var command = request(purpose, locale, NOW.plusSeconds(60));
        new SmtpEmailDelivery(sender, "sender@example.invalid", renderer, Clock.fixed(NOW, ZoneOffset.UTC))
                .deliver(command);
        var capture = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(capture.capture());
        var message = capture.getValue();
        assertThat(message.getFrom()).isEqualTo("sender@example.invalid");
        assertThat(message.getTo()).containsExactly(command.destination());
        assertThat(message.getSubject()).isEqualTo(locale.equals("fa") ? "ویگو — کد " + phrase
                : "Vigo — " + phrase + " code");
        assertThat(message.getText()).contains(phrase, "\n\n001234\n\n", command.expiresAt().toString(), "UTC",
                locale.equals("fa") ? "منقضی می‌شود" : "expires at");
        assertThat(output.getAll()).doesNotContain(command.destination(), command.otpCode(),
                message.getSubject(), message.getText());
        assertThat(renderer.render(command).toString()).isEqualTo("EmailContent[redacted]");
    }

    @Test
    void expiredAndExactlyAtExpiryNeverSend() {
        var delivery = new SmtpEmailDelivery(sender, "sender@example.invalid", renderer,
                Clock.fixed(NOW, ZoneOffset.UTC));
        delivery.deliver(request("LOGIN_OTP", "en", NOW.minusSeconds(1)));
        delivery.deliver(request("LOGIN_OTP", "en", NOW));
        verifyNoInteractions(sender);
    }

    @Test
    void checksClockAfterRenderingImmediatelyBeforeSend() {
        var command = request("LOGIN_OTP", "en", NOW.plusSeconds(1));
        var slowRenderer = mock(OtpEmailRenderer.class);
        var clock = mock(Clock.class);
        when(slowRenderer.render(command)).thenReturn(renderer.render(command));
        when(clock.instant()).thenReturn(command.expiresAt());
        new SmtpEmailDelivery(sender, "sender@example.invalid", slowRenderer, clock).deliver(command);
        var order = inOrder(slowRenderer, clock);
        order.verify(slowRenderer).render(command);
        order.verify(clock).instant();
        verifyNoInteractions(sender);
    }

    @Test
    void stripsProviderMessageCausesAndSuppressedExceptions(CapturedOutput output) {
        var command = request("LOGIN_OTP", "fa", NOW.plusSeconds(60));
        var failure = new MailSendException("recipient@example.invalid 001234 smtp-user smtp-password",
                new IllegalStateException("private provider response"));
        failure.addSuppressed(new IllegalStateException("suppressed private response"));
        doThrow(failure).when(sender).send(any(SimpleMailMessage.class));
        Throwable thrown = catchThrowable(() -> new SmtpEmailDelivery(sender, "sender@example.invalid",
                renderer, Clock.fixed(NOW, ZoneOffset.UTC)).deliver(command));
        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("SMTP email delivery failed")
                .hasNoCause();
        assertThat(thrown.getSuppressed()).isEmpty();
        var stack = new StringWriter();
        thrown.printStackTrace(new PrintWriter(stack));
        assertThat(stack + output.getAll()).doesNotContain(command.destination(), command.otpCode(),
                "smtp-user", "smtp-password", "private provider response", "suppressed private response");
        verify(sender, times(1)).send(any(SimpleMailMessage.class));
    }
}
