package ir.vigo.notification.delivery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class OtpEmailTemplateTests {
    @ParameterizedTest
    @CsvSource(textBlock = """
            login_otp, fa, rtl, ورود به حساب ویگو
            registration_otp, fa, rtl, تأیید ثبت‌نام در ویگو
            password_change_otp, fa, rtl, تأیید تغییر رمز عبور
            password_reset_otp, fa, rtl, بازیابی رمز عبور
            login_otp, en, ltr, Sign in to Vigo
            registration_otp, en, ltr, Confirm your Vigo registration
            password_change_otp, en, ltr, Confirm your password change
            password_reset_otp, en, ltr, Reset your password
            """)
    void rendersLocalizedTextAndHtmlWithTheOriginalCode(String purpose, String locale, String direction, String title) {
        var email = OtpEmailTemplate.render("auth." + purpose + ".email.v1", locale, "012345");

        assertThat(email.language()).isEqualTo(locale);
        assertThat(email.subject()).isNotBlank().doesNotContain("012345");
        assertThat(email.text()).startsWith(title).contains("\n012345\n");
        assertThat(email.html()).contains("<html lang=\"" + locale + "\" dir=\"" + direction + "\"", title, ">012345</p>");
        assertThat(email.toString()).doesNotContain("012345");
    }

    @Test
    void usesPersianByDefault() {
        var email = OtpEmailTemplate.render("auth.login_otp.email.v1", null, "012345");

        assertThat(email.language()).isEqualTo("fa");
        assertThat(email.subject()).isEqualTo("کد ورود به ویگو");
    }
}
