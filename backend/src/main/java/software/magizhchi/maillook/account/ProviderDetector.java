package software.magizhchi.maillook.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Works out IMAP/SMTP settings from an email address, so any mailbox in the world can be added
 * with just its address and password. In order:
 * <ol>
 *   <li>well-known providers (Gmail, Outlook, Yahoo, iCloud, AOL, Zoho, GMX, Yandex, …);</li>
 *   <li>the domain's mail host (MX) for hosted mail such as Google Workspace, Microsoft 365, Hostinger, Titan;</li>
 *   <li>Thunderbird's public provider database (ISPDB), for the domain and then for its mail host;</li>
 *   <li>the domain's own published settings (autoconfig.domain), then DNS SRV records (RFC 6186);</li>
 *   <li>a guess of imap.domain / smtp.domain, said to be a guess.</li>
 * </ol>
 */
@Component
public class ProviderDetector {

    private static final Logger log = LoggerFactory.getLogger(ProviderDetector.class);

    public record Preset(String provider, String imapHost, int imapPort, String smtpHost, int smtpPort, String note) {}

    private final Map<String, Preset> cache = new ConcurrentHashMap<>();
    private final String ispdb;
    private final boolean lookups;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public ProviderDetector(@Value("${maillook.detect.ispdb-url:https://autoconfig.thunderbird.net/v1.1/}") String ispdb,
                            @Value("${maillook.detect.lookups:true}") boolean lookups) {
        this.ispdb = ispdb.endsWith("/") ? ispdb : ispdb + "/";
        this.lookups = lookups;
    }

    public Preset detect(String email) {
        String domain = domainOf(email);
        return cache.computeIfAbsent(domain, d -> detectDomain(d, email));
    }

    private Preset detectDomain(String domain, String email) {
        Preset known = known(domain);
        if (known != null) return known;

        List<String> mxList = mxHosts(domain);
        String mx = String.join(" ", mxList).toLowerCase(Locale.ROOT);
        if (mx.contains("hostinger")) {
            return new Preset("hostinger", "imap.hostinger.com", 993, "smtp.hostinger.com", 465,
                    "Hostinger mailbox: use the mailbox password set in hPanel → Emails.");
        }
        if (mx.contains("titan.email")) {
            return new Preset("titan", "imap.titan.email", 993, "smtp.titan.email", 465, "Titan mailbox: use the mailbox password.");
        }
        if (mx.contains("google.com") || mx.contains("googlemail.com")) return google(true);
        if (mx.contains("protection.outlook") || mx.contains("outlook.com")) return microsoft(true);
        if (mx.contains("zoho.in")) return zoho("in");
        if (mx.contains("zoho")) return zoho("com");
        if (mx.contains("yahoodns") || mx.contains("yahoo")) return yahoo();
        if (mx.contains("icloud.com") || mx.contains("me.com")) return icloud();

        if (lookups) {
            Preset p = ispdb(domain, domain, email);
            if (p != null) return p;
            p = autoconfig(domain, email);
            if (p != null) return p;
            for (String host : mxList) {
                String base = baseDomain(host);
                if (base != null && !base.equals(domain)) {
                    p = ispdb(base, domain, email);
                    if (p != null) return p;
                }
            }
            p = srv(domain);
            if (p != null) return p;
        }
        return new Preset("other", "imap." + domain, 993, "smtp." + domain, 465,
                "We couldn't look these settings up, so these server names are a guess. If sign-in fails, ask your mail provider "
                        + "for the IMAP and SMTP server names and enter them under Advanced.");
    }

    /** Well-known consumer providers, by the address's own domain. */
    static Preset known(String d) {
        if (d.equals("gmail.com") || d.equals("googlemail.com")) return google(false);
        if (d.equals("msn.com") || d.startsWith("outlook.") || d.startsWith("hotmail.") || d.startsWith("live.") || d.equals("passport.com")) {
            return microsoft(false);
        }
        if (d.startsWith("yahoo.") || d.equals("ymail.com") || d.equals("rocketmail.com")) return yahoo();
        if (d.equals("aol.com") || d.equals("aim.com") || d.startsWith("aol.")) {
            return new Preset("aol", "imap.aol.com", 993, "smtp.aol.com", 465, "AOL needs an App Password, not your normal password.");
        }
        if (d.equals("icloud.com") || d.equals("me.com") || d.equals("mac.com")) return icloud();
        if (d.equals("zoho.com") || d.equals("zohomail.com")) return zoho("com");
        if (d.equals("zohomail.in") || d.equals("zoho.in")) return zoho("in");
        if (d.equals("gmx.com") || d.equals("gmx.us") || d.equals("gmx.co.uk")) {
            return new Preset("gmx", "imap.gmx.com", 993, "mail.gmx.com", 465, "GMX: turn on POP3/IMAP access in your GMX settings first.");
        }
        if (d.equals("gmx.net") || d.equals("gmx.de") || d.equals("gmx.at") || d.equals("gmx.ch")) {
            return new Preset("gmx", "imap.gmx.net", 993, "mail.gmx.net", 465, "GMX: turn on POP3/IMAP access in your GMX settings first.");
        }
        if (d.equals("web.de")) {
            return new Preset("webde", "imap.web.de", 993, "smtp.web.de", 587, "WEB.DE: turn on POP3/IMAP access in your settings first.");
        }
        if (d.startsWith("yandex.") || d.equals("ya.ru")) {
            return new Preset("yandex", "imap.yandex.com", 993, "smtp.yandex.com", 465, "Yandex needs an app password for mail.");
        }
        if (d.equals("mail.ru") || d.equals("inbox.ru") || d.equals("list.ru") || d.equals("bk.ru") || d.equals("internet.ru")) {
            return new Preset("mailru", "imap.mail.ru", 993, "smtp.mail.ru", 465, "Mail.ru needs a password for external apps.");
        }
        if (d.equals("fastmail.com") || d.equals("fastmail.fm")) {
            return new Preset("fastmail", "imap.fastmail.com", 993, "smtp.fastmail.com", 465, "Fastmail needs an app password.");
        }
        if (d.equals("rediffmail.com")) {
            return new Preset("rediff", "imap.rediffmail.com", 993, "smtp.rediffmail.com", 465, "Rediffmail: use your mailbox password.");
        }
        if (d.equals("proton.me") || d.equals("protonmail.com") || d.equals("pm.me") || d.equals("protonmail.ch")) {
            return new Preset("proton", "127.0.0.1", 1143, "127.0.0.1", 1025,
                    "Proton Mail encrypts your mailbox, so other apps can't read it directly. It only works through the Proton Mail Bridge app on your own computer, which MailLook can't use.");
        }
        return null;
    }

    private static Preset google(boolean workspace) {
        return new Preset("google", "imap.gmail.com", 993, "smtp.gmail.com", 465, workspace
                ? "Google Workspace: use Sign in with Google, or an App Password if your admin allows them."
                : "Gmail: use Sign in with Google, or an App Password (not your normal password).");
    }

    private static Preset microsoft(boolean work) {
        return new Preset("microsoft", "outlook.office365.com", 993, "smtp.office365.com", 587, work
                ? "Microsoft 365: use Sign in with Microsoft. Password sign-in is turned off for most work and school mailboxes."
                : "Outlook, Hotmail and Live: use Sign in with Microsoft. Microsoft has turned off password sign-in for these mailboxes.");
    }

    private static Preset yahoo() {
        return new Preset("yahoo", "imap.mail.yahoo.com", 993, "smtp.mail.yahoo.com", 465, "Yahoo needs an App Password, not your normal password.");
    }

    private static Preset icloud() {
        return new Preset("icloud", "imap.mail.me.com", 993, "smtp.mail.me.com", 587,
                "iCloud Mail needs an app-specific password. For the address, use your full @icloud.com address.");
    }

    private static Preset zoho(String tld) {
        return new Preset("zoho", "imap.zoho." + tld, 993, "smtp.zoho." + tld, 465,
                "Zoho: make sure IMAP access is on in Zoho Mail settings.");
    }

    // ── lookups ───────────────────────────────────────────────────

    private Preset ispdb(String lookupDomain, String domain, String email) {
        String xml = fetch(ispdb + lookupDomain);
        Preset p = xml == null ? null : parseConfig(xml, domain, email);
        if (p != null) log.info("Mail settings for {} from Thunderbird's provider list ({})", domain, lookupDomain);
        return p;
    }

    private Preset autoconfig(String domain, String email) {
        String q = "?emailaddress=" + URLEncoder.encode(email, StandardCharsets.UTF_8);
        for (String url : List.of("https://autoconfig." + domain + "/mail/config-v1.1.xml" + q,
                "https://" + domain + "/.well-known/autoconfig/mail/config-v1.1.xml" + q)) {
            String xml = fetch(url);
            Preset p = xml == null ? null : parseConfig(xml, domain, email);
            if (p != null) {
                log.info("Mail settings for {} from the domain's own autoconfig", domain);
                return p;
            }
        }
        return null;
    }

    private String fetch(String url) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/xml, text/xml").GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && r.body() != null && r.body().contains("clientConfig") ? r.body() : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private record Server(String host, int port, String socket, String username) {}

    /**
     * Reads a Thunderbird autoconfig file (clientConfig): the best IMAP server (SSL first) and the
     * best SMTP server (465 SSL, then 587 STARTTLS). Null when it has no usable IMAP + SMTP pair.
     */
    static Preset parseConfig(String xml, String domain, String email) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            var builder = f.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler()); // a bad file is just "not found", not a console error
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            List<Server> imap = servers(doc, "incomingServer", "imap", domain, email);
            List<Server> smtp = servers(doc, "outgoingServer", "smtp", domain, email);
            imap.removeIf(s -> !"SSL".equals(s.socket()) && !"STARTTLS".equals(s.socket()));
            smtp.removeIf(s -> !"SSL".equals(s.socket()) && !"STARTTLS".equals(s.socket()));
            if (imap.isEmpty() || smtp.isEmpty()) return null;
            // ImapClient: port 993 is SSL, any other port STARTTLS. SMTP: 465 is SSL, others STARTTLS.
            Server in = imap.stream().filter(s -> s.port() == 993 && "SSL".equals(s.socket())).findFirst()
                    .orElse(imap.stream().filter(s -> s.port() != 993 && "STARTTLS".equals(s.socket())).findFirst().orElse(null));
            Server out = smtp.stream().filter(s -> s.port() == 465 && "SSL".equals(s.socket())).findFirst()
                    .orElse(smtp.stream().filter(s -> s.port() != 465 && "STARTTLS".equals(s.socket()))
                            .min(Comparator.comparingInt(s -> s.port() == 587 ? 0 : 1)).orElse(null));
            if (in == null || out == null) return null;
            String note = "Found these settings in the public mail-provider list.";
            if (in.username() != null && !in.username().equalsIgnoreCase(email)) {
                note += " This provider signs in with \"" + in.username() + "\" rather than the full address.";
            }
            return new Preset("other", in.host(), in.port(), out.host(), out.port(), note);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Server> servers(Document doc, String tag, String type, String domain, String email) {
        List<Server> out = new ArrayList<>();
        NodeList nodes = doc.getElementsByTagName(tag);
        String local = email == null || !email.contains("@") ? "" : email.substring(0, email.indexOf('@'));
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            if (!type.equalsIgnoreCase(e.getAttribute("type"))) continue;
            String host = text(e, "hostname");
            String port = text(e, "port");
            if (host == null || port == null) continue;
            host = host.replace("%EMAILDOMAIN%", domain).trim().toLowerCase(Locale.ROOT);
            String user = text(e, "username");
            if (user != null) {
                user = user.replace("%EMAILADDRESS%", email == null ? "" : email).replace("%EMAILLOCALPART%", local)
                        .replace("%EMAILDOMAIN%", domain).trim();
            }
            try {
                out.add(new Server(host, Integer.parseInt(port.trim()), String.valueOf(text(e, "socketType")).trim().toUpperCase(Locale.ROOT), user));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static String text(Element parent, String tag) {
        NodeList n = parent.getElementsByTagName(tag);
        return n.getLength() == 0 ? null : n.item(0).getTextContent();
    }

    /** RFC 6186 SRV records: _imaps._tcp and _submissions._tcp / _submission._tcp. */
    private static Preset srv(String domain) {
        Srv in = firstSrv("_imaps._tcp." + domain);
        if (in == null || in.port() != 993) return null;
        Srv out = firstSrv("_submissions._tcp." + domain);
        if (out == null) out = firstSrv("_submission._tcp." + domain);
        if (out == null) return null;
        return new Preset("other", in.host(), 993, out.host(), out.port(),
                "Found these settings in your domain's DNS records.");
    }

    private record Srv(String host, int port) {}

    private static Srv firstSrv(String name) {
        for (String rec : dns(name, "SRV")) {
            String[] p = rec.trim().split("\\s+");
            if (p.length == 4 && !".".equals(p[3])) {
                try {
                    return new Srv(p[3].replaceAll("\\.$", "").toLowerCase(Locale.ROOT), Integer.parseInt(p[2]));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    /** "mx1.mail.ovh.net" → "ovh.net". Good enough for provider lookups; two-part TLDs handled for the common ones. */
    static String baseDomain(String host) {
        String h = host.trim().toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
        String[] mxParts = h.split("\\s+");
        h = mxParts[mxParts.length - 1].replaceAll("\\.$", "");
        String[] p = h.split("\\.");
        if (p.length < 2) return null;
        String two = p[p.length - 2] + "." + p[p.length - 1];
        if (p.length >= 3 && List.of("co.uk", "co.in", "com.au", "co.jp", "com.br", "co.nz", "co.za", "org.uk", "net.in", "com.sg").contains(two)) {
            return p[p.length - 3] + "." + two;
        }
        return two;
    }

    static String domainOf(String email) {
        int at = email == null ? -1 : email.lastIndexOf('@');
        if (at < 0) throw new IllegalArgumentException("Not an email address");
        return email.substring(at + 1).trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> mxHosts(String domain) {
        return dns(domain, "MX");
    }

    private static List<String> dns(String name, String type) {
        List<String> out = new ArrayList<>();
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("com.sun.jndi.dns.timeout.initial", "3000");
            env.put("com.sun.jndi.dns.timeout.retries", "2");
            DirContext ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes(name, new String[]{type});
            Attribute a = attrs.get(type);
            if (a != null) {
                for (int i = 0; i < a.size(); i++) out.add(String.valueOf(a.get(i)));
            }
            ctx.close();
        } catch (Exception ignored) {
            // No record / DNS trouble: the caller falls through to the next way of finding settings.
        }
        return out;
    }
}
