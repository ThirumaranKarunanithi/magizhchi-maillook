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

    public enum Role { SENT, ARCHIVE, TRASH }

    private final SecretBox secrets;
    /** accountId -> role -> full folder name */
    private final Map<Long, Map<Role, String>> folderCache = new ConcurrentHashMap<>();

    public ImapClient(SecretBox secrets) {
        this.secrets = secrets;
    }

    public String password(Account a) {
        String pw = secrets.decrypt(a.passwordEnc());
        if (pw == null) {
            throw new ApiException(HttpStatus.CONFLICT, "The saved password for " + a.email() + " can't be read any more. Enter it again under Manage accounts.");
        }
        return pw;
    }

    public Store connect(Account a) throws MessagingException {
        return connect(a.imapHost(), a.imapPort(), a.email(), password(a));
    }

    public Store connect(String host, int port, String email, String password) throws MessagingException {
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

    public ApiException friendly(Exception e, String host) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof AuthenticationFailedException) {
                return new ApiException(HttpStatus.UNAUTHORIZED, "Wrong email or password (or IMAP access is turned off for this mailbox).");
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

    public Session smtpSession(Account a) {
        Properties p = new Properties();
        p.put("mail.smtp.host", a.smtpHost());
        p.put("mail.smtp.port", String.valueOf(a.smtpPort()));
        p.put("mail.smtp.auth", "true");
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
        };
        String[] names = switch (role) {
            case SENT -> new String[]{"sent", "inbox.sent", "sent items", "sent messages", "[gmail]/sent mail", "inbox/sent", "sent mail"};
            case ARCHIVE -> new String[]{"archive", "inbox.archive", "archives", "inbox/archive", "[gmail]/all mail"};
            case TRASH -> new String[]{"trash", "inbox.trash", "deleted items", "deleted messages", "[gmail]/trash", "[gmail]/bin", "inbox/trash", "bin"};
        };
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
