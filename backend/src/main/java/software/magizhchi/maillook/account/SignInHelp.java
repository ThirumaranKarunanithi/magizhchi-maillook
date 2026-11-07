package software.magizhchi.maillook.account;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What to do before adding a mailbox from a provider that refuses normal passwords from mail apps. */
final class SignInHelp {

    private SignInHelp() {}

    static Map<String, Object> forProvider(String provider) {
        return switch (provider == null ? "" : provider) {
            case "google" -> appPassword("Gmail needs an App Password", "https://myaccount.google.com/apppasswords", List.of(
                    "Turn on 2-Step Verification in your Google Account (Security).",
                    "Open App passwords, type MailLook as the name and choose Create.",
                    "Paste the 16 letters below as the password. Spaces don't matter."));
            case "yahoo" -> appPassword("Yahoo needs an App Password", "https://login.yahoo.com/myaccount/security/app-password", List.of(
                    "Open Account security in your Yahoo account.",
                    "Choose Generate app password, name it MailLook.",
                    "Paste the password below."));
            case "aol" -> appPassword("AOL needs an App Password", "https://login.aol.com/account/security/app-passwords", List.of(
                    "Open Account security in your AOL account.",
                    "Choose Generate app password, name it MailLook.",
                    "Paste the password below."));
            case "icloud" -> appPassword("iCloud Mail needs an app-specific password", "https://account.apple.com/account/manage", List.of(
                    "Sign in at account.apple.com and open Sign-In and Security.",
                    "Choose App-Specific Passwords, add one named MailLook.",
                    "Paste it below, and use your @icloud.com address as the email."));
            case "yandex" -> appPassword("Yandex needs an app password", "https://id.yandex.com/security/app-passwords", List.of(
                    "Open Security → App passwords in your Yandex ID.",
                    "Create one for Mail, name it MailLook.",
                    "Paste it below."));
            case "mailru" -> appPassword("Mail.ru needs a password for external apps", null, List.of(
                    "In Mail.ru, open Settings → Security → Passwords for external applications.",
                    "Create one with access to IMAP/SMTP, name it MailLook.",
                    "Paste it below."));
            case "fastmail" -> appPassword("Fastmail needs an app password", null, List.of(
                    "In Fastmail, open Settings → Privacy & Security → App passwords.",
                    "Create one with access to IMAP and SMTP, name it MailLook.",
                    "Paste it below."));
            case "gmx", "webde" -> appPassword("Turn on IMAP first", null, List.of(
                    "In your mailbox settings, open POP3/IMAP.",
                    "Turn on access by other mail apps and save.",
                    "Enter your normal mailbox password below."));
            case "microsoft" -> Map.of("kind", "oauth_only", "title", "Outlook needs Sign in with Microsoft",
                    "steps", List.of("Microsoft has turned off password sign-in for Outlook, Hotmail, Live and Microsoft 365 mail, so passwords and App Passwords won't work here."));
            case "proton" -> Map.of("kind", "unsupported", "title", "Proton Mail can't be added",
                    "steps", List.of("Proton encrypts your mailbox so that only Proton's own apps, or the Proton Mail Bridge app on your computer, can read it."));
            default -> null;
        };
    }

    private static Map<String, Object> appPassword(String title, String url, List<String> steps) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "app_password");
        m.put("title", title);
        if (url != null) m.put("url", url);
        m.put("steps", steps);
        return m;
    }
}
