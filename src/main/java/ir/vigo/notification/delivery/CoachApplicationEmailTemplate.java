package ir.vigo.notification.delivery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.ResourceBundle;

import com.ibm.icu.text.SimpleDateFormat;
import com.ibm.icu.util.TimeZone;
import com.ibm.icu.util.ULocale;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.util.HtmlUtils;

public final class CoachApplicationEmailTemplate {
    private static final String HTML = loadLayout();
    private static final String LATIN_FONT = "'Avenir Next','Segoe UI',Arial,sans-serif";
    private static final String PERSIAN_FONT = "Vazirmatn,'Geeza Pro',Tahoma,'Segoe UI',Arial,sans-serif";
    private static final String VAZIRMATN = loadFont();
    private static final EmailContent.InlineImage LOGO = new EmailContent.InlineImage(
            "vigo-logo", new ClassPathResource("mail/vigo-logo.png"));
    private static final ResourceBundle.Control NO_LOCALE_FALLBACK =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    private CoachApplicationEmailTemplate() {}

    public static EmailContent render(String locale, Instant expiresAt, String registrationUrl, String rejectionReason) {
        boolean english = "en".equals(locale);
        boolean approved = expiresAt != null;
        var copy = ResourceBundle.getBundle("mail.coach-application",
                english ? Locale.ENGLISH : Locale.ROOT, NO_LOCALE_FALLBACK);
        String outcome = approved ? "approved" : "rejected";
        String subject = copy.getString(outcome + ".subject");
        String title = copy.getString(outcome + ".title");
        String body = copy.getString(outcome + ".body");
        String signature = copy.getString("signature");
        String text = title + "\n\n" + body;
        String details = "";
        if (approved) {
            String deadlineLabel = copy.getString("approved.deadline");
            String solar = deadline(expiresAt, english, "persian");
            String gregorian = deadline(expiresAt, english, "gregory");
            String linkLabel = copy.getString("approved.link");
            text += "\n\n" + deadlineLabel + "\n" + solar + " · " + gregorian + "\n\n" + linkLabel + "\n" + registrationUrl;
            details = approvalDetails(deadlineLabel, solar, gregorian, linkLabel, registrationUrl, english);
        } else if (!rejectionReason.isBlank()) {
            String reasonLabel = copy.getString("rejected.reason");
            String reason = rejectionReason.replace("\r\n", "\n").replace('\r', '\n');
            text += "\n\n" + reasonLabel + "\n" + reason;
            details = rejectionDetails(reasonLabel, reason);
        }

        String html = HTML.formatted(locale, english ? "ltr" : "rtl", english ? "left" : "right",
                escape(copy.getString("category")), escape(title), escape(body), english ? "" : VAZIRMATN,
                english ? LATIN_FONT : PERSIAN_FONT, details, escape(signature), LOGO.contentId(), english ? "right" : "left");
        return new EmailContent(locale, subject, text + "\n\n" + signature, html, LOGO);
    }

    private static String deadline(Instant expiresAt, boolean english, String calendar) {
        var locale = ULocale.forLanguageTag((english ? "en" : "fa-IR") + "-u-ca-" + calendar
                + "-nu-" + (english ? "latn" : "arabext"));
        var format = new SimpleDateFormat("d MMMM y", locale);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Tehran"));
        return format.format(Date.from(expiresAt));
    }

    private static String approvalDetails(String label, String solar, String gregorian, String linkLabel,
            String url, boolean english) {
        return """
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" bgcolor="#f4f7ef" style="background-color:#f4f7ef;border:1px solid #dde6d5;border-radius:10px;">
                  <tr><td class="date-pad" style="padding:13px 16px 14px;">
                    <p style="margin:0 0 4px;color:#53634a;font-size:13px;line-height:1.6;font-weight:600;">%s</p>
                    <p class="dates" style="margin:0;color:#24321e;font-size:18px;line-height:1.6;font-weight:700;"><span style="white-space:nowrap;">%s</span> <span aria-hidden="true" style="color:#829370;">·</span> <span style="white-space:nowrap;">%s</span></p>
                  </td></tr>
                </table>
                <table role="presentation" cellspacing="0" cellpadding="0" style="margin-top:20px;">
                  <tr><td align="center" bgcolor="#1f4436" style="border-radius:9px;">
                    <a href="%s" style="display:inline-block;padding:11px 22px;border-radius:9px;color:#ffffff;background-color:#1f4436;font-size:15px;line-height:1.6;font-weight:700;text-decoration:none;">%s&nbsp;&nbsp;<span aria-hidden="true" style="color:#cfef98;">%s</span></a>
                  </td></tr>
                </table>
                """.formatted(escape(label), escape(solar), escape(gregorian), escape(url), escape(linkLabel),
                        english ? "→" : "←");
    }

    private static String rejectionDetails(String label, String reason) {
        return """
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" bgcolor="#fbf5f1" style="width:100%%;table-layout:fixed;background-color:#fbf5f1;border:1px solid #e9d9cf;border-radius:10px;">
                  <tr><td style="padding:16px 18px;">
                    <p style="margin:0 0 6px;color:#804b39;font-size:13px;line-height:1.7;font-weight:700;">%s</p>
                    <p dir="auto" style="margin:0;color:#3b2d26;font-size:15px;line-height:1.9;overflow-wrap:anywhere;word-break:break-word;">%s</p>
                  </td></tr>
                </table>
                """.formatted(escape(label), escape(reason).replace("\n", "<br>"));
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value, StandardCharsets.UTF_8.name());
    }

    private static String loadLayout() {
        try {
            return new ClassPathResource("mail/coach-application.html").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Coach application email layout could not be loaded", e);
        }
    }

    private static String loadFont() {
        try {
            var font = new ClassPathResource("mail/fonts/vazirmatn-arabic-wght-normal.woff2");
            return """
                    @font-face {
                      font-family: Vazirmatn;
                      src: url('data:font/woff2;base64,%s') format('woff2');
                      font-weight: 100 900;
                      font-display: swap;
                    }
                    """.formatted(Base64.getEncoder().encodeToString(font.getContentAsByteArray()));
        } catch (IOException e) {
            throw new IllegalStateException("Coach application email font could not be loaded", e);
        }
    }
}
