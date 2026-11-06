package software.magizhchi.maillook.account;

import org.springframework.stereotype.Component;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Works out IMAP/SMTP settings from an email address (domain shortcuts, then MX lookup). */
@Component
public class ProviderDetector {

    public record Preset(String provider, String imapHost, int imapPort, String smtpHost, int smtpPort, String note) {}

    private final Map<String, Preset> cache = new ConcurrentHashMap<>();

    public Preset detect(String email) {
        String domain = domainOf(email);
        return cache.computeIfAbsent(domain, this::detectDomain);
    }

    private Preset detectDomain(String domain) {
        switch (domain) {
            case "gmail.com", "googlemail.com" -> { return google(); }
            case "yahoo.com", "yahoo.co.in", "ymail.com" -> { return yahoo(); }
            case "outlook.com", "hotmail.com", "live.com", "msn.com" -> { return microsoft(); }
            case "zoho.com", "zohomail.com" -> { return zoho("com"); }
            case "zohomail.in" -> { return zoho("in"); }
            default -> { }
        }
        String mx = String.join(" ", mxHosts(domain)).toLowerCase(Locale.ROOT);
        if (mx.contains("hostinger")) {
            return new Preset("hostinger", "imap.hostinger.com", 993, "smtp.hostinger.com", 465,
                    "Hostinger mailbox: use the mailbox password set in hPanel → Emails.");
        }
        if (mx.contains("titan.email")) {
            return new Preset("titan", "imap.titan.email", 993, "smtp.titan.email", 465, "Titan mailbox.");
        }
        if (mx.contains("google.com") || mx.contains("googlemail.com")) return google();
        if (mx.contains("zoho.in")) return zoho("in");
        if (mx.contains("zoho")) return zoho("com");
        if (mx.contains("yahoodns") || mx.contains("yahoo")) return yahoo();
        if (mx.contains("outlook.com") || mx.contains("protection.outlook")) return microsoft();
        return new Preset("other", "imap." + domain, 993, "smtp." + domain, 465,
                "We guessed these server names. Check them with your mail provider if sign-in fails.");
    }

    private static Preset google() {
        return new Preset("google", "imap.gmail.com", 993, "smtp.gmail.com", 465,
                "Gmail needs an App Password (Google Account → Security → App passwords), not your normal password.");
    }

    private static Preset yahoo() {
        return new Preset("yahoo", "imap.mail.yahoo.com", 993, "smtp.mail.yahoo.com", 465,
                "Yahoo needs an App Password from Account security.");
    }

    private static Preset microsoft() {
        return new Preset("microsoft", "outlook.office365.com", 993, "smtp.office365.com", 587,
                "Microsoft has turned off password sign-in for most Outlook accounts. Outlook sign-in (OAuth) is coming in a later version.");
    }

    private static Preset zoho(String tld) {
        return new Preset("zoho", "imap.zoho." + tld, 993, "smtp.zoho." + tld, 465,
                "Zoho: make sure IMAP access is on in Zoho Mail settings.");
    }

    static String domainOf(String email) {
        int at = email == null ? -1 : email.lastIndexOf('@');
        if (at < 0) throw new IllegalArgumentException("Not an email address");
        return email.substring(at + 1).trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> mxHosts(String domain) {
        List<String> out = new ArrayList<>();
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("com.sun.jndi.dns.timeout.initial", "3000");
            env.put("com.sun.jndi.dns.timeout.retries", "2");
            DirContext ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes(domain, new String[]{"MX"});
            Attribute mx = attrs.get("MX");
            if (mx != null) {
                for (int i = 0; i < mx.size(); i++) out.add(String.valueOf(mx.get(i)));
            }
            ctx.close();
        } catch (Exception ignored) {
            // No MX / DNS trouble: fall through to the guessed defaults.
        }
        return out;
    }
}
