package ir.vigo.notification.delivery;

import ir.vigo.notification.messaging.model.NotificationRequested;

/** Internal plain-text templates. Inputs have already passed command validation. */
final class OtpEmailRenderer {
    Content render(NotificationRequested request) {
        boolean persian = switch (request.locale()) {
            case "fa" -> true;
            case "en" -> false;
            default -> throw new IllegalArgumentException("Unsupported email locale");
        };
        String purpose = switch (request.purpose()) {
            case "LOGIN_OTP" -> persian ? "ورود" : "sign in";
            case "REGISTRATION_OTP" -> persian ? "ثبت‌نام" : "registration";
            case "PASSWORD_CHANGE_OTP" -> persian ? "تغییر رمز عبور" : "password change";
            case "PASSWORD_RESET_OTP" -> persian ? "بازیابی رمز عبور" : "password reset";
            default -> throw new IllegalArgumentException("Unsupported email purpose");
        };
        if (persian) {
            return new Content("ویگو — کد " + purpose,
                    "برای " + purpose + " در ویگو از کد زیر استفاده کنید:\n\n" + request.otpCode()
                    + "\n\nاین کد در " + request.expiresAt() + " (UTC) منقضی می‌شود."
                    + "\nاگر این درخواست را نداده‌اید، این ایمیل را نادیده بگیرید.");
        }
        return new Content("Vigo — " + purpose + " code",
                "Use this code for your Vigo " + purpose + ":\n\n" + request.otpCode()
                + "\n\nThis code expires at " + request.expiresAt() + " (UTC)."
                + "\nIf you did not request this, ignore this email.");
    }

    record Content(String subject, String body) {
        @Override
        public String toString() {
            return "EmailContent[redacted]";
        }
    }
}
