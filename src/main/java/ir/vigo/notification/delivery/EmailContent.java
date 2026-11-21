package ir.vigo.notification.delivery;

import org.springframework.core.io.ClassPathResource;

public record EmailContent(String language, String subject, String text, String html, InlineImage inlineImage) {
    public EmailContent(String language, String subject, String text, String html) {
        this(language, subject, text, html, null);
    }

    public record InlineImage(String contentId, ClassPathResource resource) {}

    @Override
    public String toString() {
        return "EmailContent[redacted]";
    }
}
