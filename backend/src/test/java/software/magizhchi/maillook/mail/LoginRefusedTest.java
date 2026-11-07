package software.magizhchi.maillook.mail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A refused password says what to do next for that provider, not just "wrong password". */
class LoginRefusedTest {

    @Test
    void namesTheFixForEachProvider() {
        assertThat(ImapClient.loginRefused("imap.gmail.com", "[ALERT] Application-specific password required: https://support.google.com/accounts/answer/185833 (Failure)"))
                .contains("App Password").contains("myaccount.google.com/apppasswords").contains("Sign in with Google");
        assertThat(ImapClient.loginRefused("outlook.office365.com", "AUTHENTICATE failed."))
                .contains("Sign in with Microsoft");
        assertThat(ImapClient.loginRefused("imap.mail.yahoo.com", null)).contains("Yahoo").contains("App Password");
        assertThat(ImapClient.loginRefused("imap.mail.me.com", null)).contains("app-specific password");
        assertThat(ImapClient.loginRefused("imap.gmail.com", "[ALERT] Please log in via your web browser")).contains("web browser");
        assertThat(ImapClient.loginRefused("imap.gmx.net", null)).contains("POP3/IMAP");
    }

    @Test
    void otherServersSayWhatTheyWereTold() {
        assertThat(ImapClient.loginRefused("imap.hostinger.com", "authentication failed"))
                .isEqualTo("Wrong email or password, or IMAP access is turned off for this mailbox.");
        assertThat(ImapClient.loginRefused("mail.example.org", "Account suspended for unpaid invoice"))
                .endsWith("(the server said: Account suspended for unpaid invoice).");
    }
}
