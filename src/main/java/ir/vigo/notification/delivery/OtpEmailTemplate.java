package ir.vigo.notification.delivery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.ResourceBundle;

import org.springframework.core.io.ClassPathResource;
import org.springframework.web.util.HtmlUtils;

final class OtpEmailTemplate {
    private static final String HTML = loadLayout();
    private static final ResourceBundle.Control NO_LOCALE_FALLBACK =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    static Rendered render(String templateId, String locale, String otpCode) {
        boolean english = "en".equals(locale);
        String language = english ? "en" : "fa";
        String direction = english ? "ltr" : "rtl";
        String alignment = english ? "left" : "right";
        var copy = ResourceBundle.getBundle("mail.otp", english ? Locale.ENGLISH : Locale.ROOT, NO_LOCALE_FALLBACK);

        String brand = copy.getString("brand");
        String subtitle = copy.getString("subtitle");
        String subject = copy.getString(templateId + ".subject");
        String title = copy.getString(templateId + ".title");
        String intro = copy.getString(templateId + ".intro");
        String codeLabel = copy.getString("code-label");
        String expiry = copy.getString("expiry");
        String safety = copy.getString("safety");
        String footer = copy.getString("footer");

        String text = String.join("\n\n", title, intro, codeLabel + "\n" + otpCode, expiry, safety, footer);
        String html = HTML.formatted(language, direction, alignment, escape(brand), escape(subtitle), escape(title),
                escape(intro), escape(codeLabel), escape(otpCode), escape(expiry), escape(safety), escape(footer));
        return new Rendered(language, subject, text, html);
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value, StandardCharsets.UTF_8.name());
    }

    private static String loadLayout() {
        try {
            var layout = new ClassPathResource("mail/otp.html").getContentAsString(StandardCharsets.UTF_8);
            var font = new ClassPathResource("mail/fonts/vazirmatn-arabic-wght-normal.woff2");
            return layout.replace("{{vazirmatn}}", Base64.getEncoder().encodeToString(font.getContentAsByteArray()));
        } catch (IOException e) {
            throw new IllegalStateException("OTP email layout could not be loaded", e);
        }
    }

    private OtpEmailTemplate() {}

    record Rendered(String language, String subject, String text, String html) {
        @Override
        public String toString() {
            return "RenderedOtpEmail[redacted]";
        }
    }
}
