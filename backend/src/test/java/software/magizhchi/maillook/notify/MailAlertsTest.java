package software.magizhchi.maillook.notify;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailAlertsTest {

    @Test
    void oneTimeCodesAlwaysRing() {
        assertTrue(MailAlerts.isCode("Your OTP for login", null));
        assertTrue(MailAlerts.isCode("Verify your email", "Your verification code is 482913"));
        assertTrue(MailAlerts.isCode("482913 is your Instagram code", ""));
        assertTrue(MailAlerts.isCode("Sign-in code", "Use 7731 to sign in"));
        assertTrue(MailAlerts.isCode("HDFC Bank", "One-time password 5521 for txn of Rs 499"));
        assertTrue(MailAlerts.isCode("உங்கள் ஒரு முறை கடவுச்சொல்", null));

        assertFalse(MailAlerts.isCode("Weekly newsletter", "Top stories this week"));
        assertFalse(MailAlerts.isCode("Invoice #4411", "Amount due Rs 1,180"));
        assertFalse(MailAlerts.isCode("Hotpot dinner tonight?", "Shall we go at 8"));
    }
}
