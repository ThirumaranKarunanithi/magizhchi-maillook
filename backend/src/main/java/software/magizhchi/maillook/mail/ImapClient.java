package software.magizhchi.maillook.mail;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import software.magizhchi.maillook.account.Account;
import software.magizhchi.maillook.account.OAuthTokens;
import software.magizhchi.maillook.common.ApiException;
import software.magizhchi.maillook.common.SecretBox;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/** Opens IMAP stores and SMTP sessions for a mailbox, and finds its special folders. */
@Component
public class ImapClient {

    public enum Role { SENT, ARCHIVE, TRASH, SPAM }

    private final SecretBox secrets;
    private final OAuthTokens oauth;
    /** accountId -> role -> full folder name */
    private final Map<Long, Map<Role, String>> folderCache = new ConcurrentHashMap<>();

    public ImapClient(SecretBox secrets, OAuthTokens oauth) {
        this.secrets = secrets;
        this.oauth = oauth;
    }

    /** What to sign in with: the saved password, or a fresh access token for Google / Microsoft sign-in. */
    public String secret(Account a) {
        return a.oauth() ? oauth.accessToken(a) : password(a);
    }

    public String password(Account a) {
        String pw = secrets.decrypt(a.passwordEnc());
        if (pw == null) {
            throw new ApiException(HttpStatus.CONFLICT, "The saved password for " + a.email() + " can't be read any more. Enter it again under Manage accounts.");
        }
        return pw;
    }

    public Store connect(Account a) throws MessagingException {
        try {
            return connect(a.imapHost(), a.imapPort(), a.email(), secret(a), a.oauth());
        } catch (AuthenticationFailedException e) {
            // A token the provider no longer takes: drop it so the next try refreshes.
            if (a.oauth()) oauth.forget(a.id());
            throw e;
        }
    }

    public Store connect(String host, int port, String email, String password) throws MessagingException {
        return connect(host, port, email, password, false);
    }

    /** {@code xoauth2}: {@code password} is an OAuth access token (Sign in with Google / Microsoft). */
    public Store connect(String host, int port, String email, String password, boolean xoauth2) throws MessagingException {
        boolean ssl = port == 993;
        String proto = ssl ? "imaps" : "imap";
        Properties p = new Properties();
        p.put("mail.store.protocol", proto);
        p.put("mail." + proto + ".host", host);
        p.put("mail." + proto + ".port", String.valueOf(port));
        p.put("mail." + proto + ".connectiontimeout", "15000");
        p.put("mail." + proto + ".timeout", "60000");
        p.put("mail." + proto + ".peek", "true");
        p.put("mail." + proto + ".partialfetch", "true");
        p.put("mail." + proto + ".fetchsize", "1048576");
        if (!ssl) {
            p.put("mail.imap.starttls.enable", "true");
            p.put("mail.imap.starttls.required", String.valueOf(!isLocal(host)));
        }
        if (xoauth2) p.put("mail." + proto + ".auth.mechanisms", "XOAUTH2");
        Session session = Session.getInstance(p);
        Store store = session.getStore(proto);
        store.connect(host, port, email, password);
        return store;
    }

    /** Checks the credentials; throws an ApiException with a friendly message if they don't work. */
    public void verify(String host, int port, String email, String password) {
        Store s = null;
        try {
            s = connect(host, port, email, password);
        } catch (MessagingException e) {
            throw friendly(e, host);
        } finally {
            closeQuietly(s);
        }
    }

    /** Checks that a Google / Microsoft sign-in really opens the mailbox. */
    public void verifyToken(String host, int port, String email, String accessToken, String providerLabel) {
        Store s = null;
        try {
            s = connect(host, port, email, accessToken, true);
        } catch (AuthenticationFailedException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, providerLabel + " signed you in, but the mailbox " + email
                    + " didn't accept it. Check that IMAP is turned on for this mailbox" + said(e) + ".");
        } catch (MessagingException e) {
            throw friendly(e, host);
        } finally {
            closeQuietly(s);
        }
    }

    public ApiException friendly(Exception e, String host) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof AuthenticationFailedException af) {
                return new ApiException(HttpStatus.UNAUTHORIZED, loginRefused(host, af.getMessage()));
            }
            if (t instanceof UnknownHostException) {
                return new ApiException(HttpStatus.BAD_REQUEST, "Can't find the mail server " + host + ". Check the server name.");
            }
            if (t instanceof ConnectException || t instanceof SocketTimeoutException) {
                return new ApiException(HttpStatus.BAD_GATEWAY, "Can't reach the mail server " + host + " right now.");
            }
            t = t.getCause();
        }
        return new ApiException(HttpStatus.BAD_GATEWAY, "The mail server said: " + e.getMessage());
    }

    /**
     * Why a password sign-in was refused, in words that say what to do next. Big providers refuse
     * normal passwords from mail apps, so for them the answer is an App Password or Sign in with ….
     */
    static String loginRefused(String host, String serverSaid) {
        String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
        String m = serverSaid == null ? "" : serverSaid.toLowerCase(Locale.ROOT);
        if (m.contains("web browser") || m.contains("weblogin") || m.contains("web login")) {
            return "Your provider wants you to confirm this sign-in in a web browser first. Sign in to your mail on the web, "
                    + "approve the new sign-in if it asks, then try again here.";
        }
        if (h.contains("gmail") || h.contains("googlemail") || m.contains("application-specific password")) {
            return "Google refused this password. Gmail only accepts an App Password from mail apps: turn on 2-Step Verification, "
                    + "make one at myaccount.google.com/apppasswords and paste its 16 letters here. Or use Sign in with Google.";
        }
        if (h.contains("office365") || h.contains("outlook") || h.contains("hotmail")) {
            return "Microsoft has turned off password sign-in for Outlook, Hotmail and Microsoft 365 mail. Use Sign in with Microsoft instead.";
        }
        if (h.contains("yahoo")) {
            return "Yahoo refused this password. Yahoo only accepts an App Password from mail apps: make one under Account security → "
                    + "Generate app password, and paste it here.";
        }
        if (h.contains("aol.com")) {
            return "AOL refused this password. AOL only accepts an App Password from mail apps: make one under Account security → "
                    + "Generate app password, and paste it here.";
        }
        if (h.contains("mail.me.com")) {
            return "Apple refused this password. iCloud Mail only accepts an app-specific password: make one at account.apple.com → "
                    + "Sign-In and Security → App-Specific Passwords, and paste it here.";
        }
        if (h.contains("yandex")) {
            return "Yandex refused this password. Make an app password for mail at id.yandex.com → Security → App passwords, and paste it here.";
        }
        if (h.contains("mail.ru")) {
            return "Mail.ru refused this password. Make a password for external apps in your Mail.ru security settings, and paste it here.";
        }
        if (h.contains("gmx") || h.contains("web.de")) {
            return "Wrong email or password, or IMAP is turned off. In your mailbox settings, turn on \"POP3/IMAP access\" and try again.";
        }
        if (h.contains("fastmail")) {
            return "Fastmail refused this password. Make an app password under Settings → Privacy & Security → App passwords, and paste it here.";
        }
        if (h.contains("zoho")) {
            return "Zoho refused this password. Turn on IMAP access in Zoho Mail settings; if you use two-factor sign-in, make an App Password and paste it here.";
        }
        return "Wrong email or password, or IMAP access is turned off for this mailbox" + said(serverSaid) + ".";
    }

    private static String said(Exception e) {
        return said(e.getMessage());
    }

    private static String said(String serverSaid) {
        if (serverSaid == null || serverSaid.isBlank()) return "";
        String t = serverSaid.replaceAll("\\s+", " ").trim();
        if (t.equalsIgnoreCase("authentication failed") || t.equalsIgnoreCase("[AUTHENTICATIONFAILED] Invalid credentials (Failure)")) return "";
        return " (the server said: " + (t.length() > 160 ? t.substring(0, 160) + "…" : t) + ")";
    }

    public Session smtpSession(Account a) {
        Properties p = new Properties();
        p.put("mail.smtp.host", a.smtpHost());
        p.put("mail.smtp.port", String.valueOf(a.smtpPort()));
        p.put("mail.smtp.auth", "true");
        if (a.oauth()) p.put("mail.smtp.auth.mechanisms", "XOAUTH2");
        p.put("mail.smtp.connectiontimeout", "15000");
        p.put("mail.smtp.timeout", "60000");
        if (a.smtpPort() == 465) {
            p.put("mail.smtp.ssl.enable", "true");
        } else {
            p.put("mail.smtp.starttls.enable", "true");
            p.put("mail.smtp.starttls.required", String.valueOf(!isLocal(a.smtpHost())));
        }
        return Session.getInstance(p);
    }

    /** Plain-text IMAP/SMTP is only allowed to this machine (local test servers). */
    private static boolean isLocal(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
    }

    /** Finds (and, for Archive, creates if missing) a special folder. Returns null if there is none. */
    public IMAPFolder folder(Store store, Account a, Role role, boolean create) throws MessagingException {
        Map<Role, String> known = folderCache.computeIfAbsent(a.id(), k -> new ConcurrentHashMap<>());
        String name = known.get(role);
        if (name == null) {
            name = find(store, role);
            if (name != null) known.put(role, name);
        }
        if (name == null && create) {
            Folder[] ns = store.getPersonalNamespaces();
            Folder base = ns.length > 0 && !ns[0].getFullName().isEmpty() ? ns[0] : store.getDefaultFolder();
            Folder f = base.getFolder(role == Role.ARCHIVE ? "Archive" : role == Role.TRASH ? "Trash" : "Sent");
            if (!f.exists()) f.create(Folder.HOLDS_MESSAGES);
            name = f.getFullName();
            known.put(role, name);
        }
        return name == null ? null : (IMAPFolder) store.getFolder(name);
    }

    public void forget(long accountId) {
        folderCache.remove(accountId);
    }

    private static String find(Store store, Role role) throws MessagingException {
        String attr = switch (role) {
            case SENT -> "\\sent";
            case ARCHIVE -> "\\archive";
            case TRASH -> "\\trash";
            case SPAM -> "\\junk";
        };
        String[] names = names(role);
        Folder[] all = store.getDefaultFolder().list("*");
        for (Folder f : all) {
            if (f instanceof IMAPFolder imf) {
                for (String at : imf.getAttributes()) {
                    if (attr.equalsIgnoreCase(at)) return f.getFullName();
                }
            }
        }
        for (String n : names) {
            for (Folder f : all) {
                if (f.getFullName().toLowerCase(Locale.ROOT).equals(n)) return f.getFullName();
            }
        }
        return null;
    }

    private static String[] names(Role role) {
        return switch (role) {
            case SENT -> new String[]{"sent", "inbox.sent", "sent items", "sent messages", "[gmail]/sent mail", "inbox/sent", "sent mail"};
            case ARCHIVE -> new String[]{"archive", "inbox.archive", "archives", "inbox/archive", "[gmail]/all mail"};
            case TRASH -> new String[]{"trash", "inbox.trash", "deleted items", "deleted messages", "[gmail]/trash", "[gmail]/bin", "inbox/trash", "bin"};
            case SPAM -> new String[]{"spam", "junk", "junk e-mail", "junk email", "bulk mail", "inbox.spam", "inbox.junk", "[gmail]/spam", "inbox/spam", "inbox/junk"};
        };
    }

    private static final java.util.Set<String> SPECIAL_ATTRS = java.util.Set.of(
            "\\noselect", "\\nonexistent", "\\sent", "\\trash", "\\junk", "\\drafts", "\\all", "\\archive", "\\flagged", "\\important");
    private static final java.util.Set<String> SPECIAL_NAMES = new java.util.HashSet<>(java.util.List.of(
            "inbox", "drafts", "draft", "inbox.drafts", "inbox/drafts", "[gmail]/drafts", "[gmail]/starred", "[gmail]/important",
            "[gmail]/all mail", "outbox", "templates", "notes", "inbox.templates"));
    static {
        for (Role r : Role.values()) SPECIAL_NAMES.addAll(java.util.List.of(names(r)));
    }

    /**
     * The mailbox's own folders (and Gmail's labels, which IMAP shows as folders): everything that holds
     * mail except Inbox and the special folders MailLook already shows by name.
     */
    public java.util.List<String> ownFolders(Store store) throws MessagingException {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Folder f : store.getDefaultFolder().list("*")) {
            String name = f.getFullName();
            if (name.isEmpty() || name.length() > 280 || SPECIAL_NAMES.contains(name.toLowerCase(Locale.ROOT))) continue;
            boolean special = false;
            if (f instanceof IMAPFolder imf) {
                String[] attrs = imf.getAttributes();
                if (attrs != null) for (String at : attrs) if (SPECIAL_ATTRS.contains(at.toLowerCase(Locale.ROOT))) special = true;
            }
            if (!special && (f.getType() & Folder.HOLDS_MESSAGES) != 0) out.add(name);
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** Gmail's "archive" is All Mail: every message again, so it isn't synced as a folder of its own. */
    public static boolean isAllMail(IMAPFolder f) {
        if (f.getFullName().equalsIgnoreCase("[gmail]/all mail")) return true;
        try {
            String[] attrs = f.getAttributes();
            if (attrs != null) for (String at : attrs) if (at.equalsIgnoreCase("\\all")) return true;
        } catch (MessagingException ignored) {
        }
        return false;
    }

    public static void closeQuietly(Store s) {
        if (s == null) return;
        try {
            s.close();
        } catch (Exception ignored) {
        }
    }

    public static void closeQuietly(Folder f, boolean expunge) {
        if (f == null || !f.isOpen()) return;
        try {
            f.close(expunge);
        } catch (Exception ignored) {
        }
    }
}
