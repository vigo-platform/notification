package ir.vigo.notification.delivery;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Properties;

import ir.vigo.notification.messaging.model.NotificationRequested;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;

final class SmtpEmailDelivery implements EmailDelivery {
    private static final String UTF_8 = StandardCharsets.UTF_8.name();
    private final SmtpProperties settings;
    private final Clock clock;
    private final Session session;

    SmtpEmailDelivery(SmtpProperties settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
        var properties = new Properties();
        boolean startTls = settings.security() == SmtpProperties.Security.STARTTLS;
        properties.setProperty("mail.smtp.starttls.enable", Boolean.toString(startTls));
        properties.setProperty("mail.smtp.starttls.required", Boolean.toString(startTls));
        properties.setProperty("mail.smtp.ssl.enable", Boolean.toString(settings.security() == SmtpProperties.Security.TLS));
        properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        properties.setProperty("mail.smtp.auth", Boolean.toString(settings.auth()));
        properties.setProperty("mail.smtp.connectiontimeout", Integer.toString(settings.connectTimeoutMs()));
        properties.setProperty("mail.smtp.timeout", Integer.toString(settings.readTimeoutMs()));
        properties.setProperty("mail.smtp.writetimeout", Integer.toString(settings.writeTimeoutMs()));
        properties.setProperty("mail.smtp.quitwait", "false");
        session = Session.getInstance(properties);
    }

    @Override
    public boolean deliver(NotificationRequested request) {
        if (expired(request)) {
            return false;
        }
        try {
            MimeMessage email = createEmail(request);
            var transport = session.getTransport("smtp");
            try {
                connect(transport);
                if (expired(request)) {
                    return false;
                }
                try {
                    transport.sendMessage(email, email.getAllRecipients());
                    return true;
                } catch (MessagingException e) {
                    throw submissionFailure(e);
                }
            } finally {
                try {
                    transport.close();
                } catch (MessagingException ignored) {
                    // A QUIT failure cannot undo acceptance or replace a send failure.
                }
            }
        } catch (MessagingException | UnsupportedEncodingException ignored) {
            throw new IllegalStateException("SMTP email delivery failed");
        }
    }

    private void connect(Transport transport) {
        try {
            transport.connect(settings.host(), settings.port(), settings.auth() ? settings.username() : null,
                    settings.auth() ? settings.password() : null);
        } catch (AuthenticationFailedException e) {
            throw new SmtpFailure(SmtpFailure.Reason.AUTH_FAILED);
        } catch (MessagingException e) {
            throw new SmtpFailure(SmtpFailure.Reason.CONNECTION_FAILED);
        }
    }

    private static SmtpFailure submissionFailure(MessagingException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            int code;
            if (cause instanceof SMTPSendFailedException send) {
                code = send.getReturnCode();
            } else if (cause instanceof SMTPAddressFailedException recipient) {
                code = recipient.getReturnCode();
            } else {
                continue;
            }
            if (code >= 400 && code < 500) {
                return new SmtpFailure(SmtpFailure.Reason.TEMPORARY_REJECTION);
            }
            if (code >= 500 && code < 600) {
                return new SmtpFailure(SmtpFailure.Reason.PERMANENT_REJECTION);
            }
        }
        // The relay may have accepted DATA before the connection was lost.
        return new SmtpFailure(SmtpFailure.Reason.ACCEPTANCE_UNKNOWN);
    }

    private MimeMessage createEmail(NotificationRequested request) throws MessagingException, UnsupportedEncodingException {
        var template = OtpEmailTemplate.render(request.templateId(), request.locale(), request.otpCode());
        var plainText = new MimeBodyPart();
        plainText.setText(template.text(), UTF_8);
        var html = new MimeBodyPart();
        html.setText(template.html(), UTF_8, "html");
        var body = new MimeMultipart("alternative");
        body.addBodyPart(plainText);
        body.addBodyPart(html);

        var email = new MimeMessage(session);
        email.setFrom(new InternetAddress(settings.fromAddress(), settings.fromName(), UTF_8));
        email.setRecipient(Message.RecipientType.TO, new InternetAddress(request.destination(), true));
        email.setSubject(template.subject(), UTF_8);
        email.setContent(body);
        email.setHeader("Content-Language", template.language());
        email.saveChanges();
        return email;
    }

    private boolean expired(NotificationRequested request) {
        return !clock.instant().isBefore(request.expiresAt());
    }
}
