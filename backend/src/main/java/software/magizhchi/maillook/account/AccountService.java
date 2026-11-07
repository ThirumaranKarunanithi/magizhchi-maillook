package software.magizhchi.maillook.account;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import software.magizhchi.maillook.common.ApiException;
import software.magizhchi.maillook.common.SecretBox;
import software.magizhchi.maillook.mail.ImapClient;
import software.magizhchi.maillook.mail.MessageRepo;
import software.magizhchi.maillook.mail.SyncService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

@Service
public class AccountService {

    public static final List<String> COLORS = List.of(
            "#0e7c86", "#B45309", "#7c3aed", "#be185d", "#15803d", "#1d4ed8", "#c2410c", "#0f766e", "#a16207", "#4338ca");
    private static final Pattern APP_PASSWORD = Pattern.compile("^[A-Za-z]{4}( [A-Za-z]{4}){3}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public record NewAccount(String email, String password, String displayName,
                             String imapHost, Integer imapPort, String smtpHost, Integer smtpPort) {}

    private final AccountRepo repo;
    private final MessageRepo messages;
    private final ProviderDetector detector;
    private final ImapClient imap;
    private final SecretBox secrets;
    private final SyncService sync;
    private final OAuthTokens oauth;
    private final ExecutorService bulkPool = Executors.newFixedThreadPool(5);

    public AccountService(AccountRepo repo, MessageRepo messages, ProviderDetector detector, ImapClient imap,
                          SecretBox secrets, SyncService sync, OAuthTokens oauth) {
        this.repo = repo;
        this.messages = messages;
        this.detector = detector;
        this.imap = imap;
        this.secrets = secrets;
        this.sync = sync;
        this.oauth = oauth;
    }

    public static String normalize(String email) {
        String e = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(e).matches()) throw ApiException.bad("\"" + (email == null ? "" : email.trim()) + "\" isn't an email address.");
        return e;
    }

    public ProviderDetector.Preset preset(NewAccount n) {
        ProviderDetector.Preset d = detector.detect(n.email());
        return new ProviderDetector.Preset(d.provider(),
                blank(n.imapHost()) ? d.imapHost() : n.imapHost().trim(),
                n.imapPort() == null ? d.imapPort() : n.imapPort(),
                blank(n.smtpHost()) ? d.smtpHost() : n.smtpHost().trim(),
                n.smtpPort() == null ? d.smtpPort() : n.smtpPort(),
                d.note());
    }

    /** Verifies the mailbox login, stores it (password encrypted) and starts the first sync. */
    public Account add(long userId, NewAccount n) {
        String email = normalize(n.email());
        if (n.password() == null || n.password().isEmpty()) throw ApiException.bad("Enter the password for " + email + ".");
        if (repo.findByEmail(userId, email).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, email + " is already in Magizhchi MailLook.");
        }
        // Google shows App Passwords as "abcd efgh ijkl mnop"; the spaces aren't part of it.
        String password = APP_PASSWORD.matcher(n.password().trim()).matches() ? n.password().replace(" ", "") : n.password();
        NewAccount clean = new NewAccount(email, password, n.displayName(), n.imapHost(), n.imapPort(), n.smtpHost(), n.smtpPort());
        ProviderDetector.Preset p = preset(clean);
        if ("proton".equals(p.provider()) && blank(n.imapHost())) throw ApiException.bad(p.note());
        imap.verify(p.imapHost(), p.imapPort(), email, password);
        int existing = repo.forUser(userId).size();
        String name = blank(n.displayName()) ? defaultName(email) : n.displayName().trim();
        long id;
        try {
            id = repo.insert(userId, email, name, COLORS.get(existing % COLORS.size()), p, secrets.encrypt(password));
        } catch (org.springframework.dao.DuplicateKeyException race) {
            throw new ApiException(HttpStatus.CONFLICT, email + " is already in Magizhchi MailLook.");
        }
        sync.request(id);
        return repo.find(id).orElseThrow();
    }

    /**
     * Adds (or switches over) a mailbox signed in with Google / Microsoft: checks the token opens
     * the mailbox, keeps the refresh token encrypted, and starts syncing.
     */
    public Account connectOAuth(long userId, OAuthTokens.Provider p, OAuthTokens.Grant g) {
        ProviderDetector.Preset servers = p.servers();
        imap.verifyToken(servers.imapHost(), servers.imapPort(), g.email(), g.accessToken(), p.label());
        String enc = secrets.encrypt(g.refreshToken());
        long id;
        var existing = repo.findByEmail(userId, g.email());
        if (existing.isPresent()) {
            id = existing.get().id();
            repo.updateSignIn(id, "oauth", servers, enc);
            imap.forget(id);
        } else {
            int count = repo.forUser(userId).size();
            try {
                id = repo.insert(userId, g.email(), defaultName(g.email()), COLORS.get(count % COLORS.size()), servers, enc, "oauth");
            } catch (org.springframework.dao.DuplicateKeyException race) {
                throw new ApiException(HttpStatus.CONFLICT, g.email() + " is already in Magizhchi MailLook.");
            }
        }
        oauth.remember(id, g);
        sync.request(id);
        return repo.find(id).orElseThrow();
    }

    public List<Map<String, Object>> addMany(long userId, List<NewAccount> list) {
        List<CompletableFuture<Map<String, Object>>> jobs = new ArrayList<>();
        for (NewAccount n : list) {
            jobs.add(CompletableFuture.supplyAsync(() -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("email", n.email() == null ? "" : n.email().trim());
                try {
                    Account a = add(userId, n);
                    r.put("ok", true);
                    r.put("account", view(a, 0));
                } catch (ApiException e) {
                    r.put("ok", false);
                    r.put("error", e.getMessage());
                } catch (Exception e) {
                    r.put("ok", false);
                    r.put("error", "Couldn't add this mailbox.");
                }
                return r;
            }, bulkPool));
        }
        return jobs.stream().map(CompletableFuture::join).toList();
    }

    public Account owned(long userId, long id) {
        return repo.find(id).filter(a -> a.userId() == userId).orElseThrow(() -> ApiException.notFound("Account"));
    }

    public Account update(long userId, long id, String displayName, String color, String password) {
        Account a = owned(userId, id);
        if (color != null && !color.matches("^#[0-9a-fA-F]{6}$")) throw ApiException.bad("Colour must look like #0e7c86.");
        repo.updateProfile(id, blank(displayName) ? null : displayName.trim(), color);
        if (password != null && !password.isEmpty()) {
            if (a.oauth()) {
                throw ApiException.bad(a.email() + " signs in with " + ("microsoft".equals(a.provider()) ? "Microsoft" : "Google")
                        + ", so it has no password here. Use Sign in again instead.");
            }
            imap.verify(a.imapHost(), a.imapPort(), a.email(), password);
            repo.updatePassword(id, secrets.encrypt(password));
            sync.request(id);
        }
        return repo.find(id).orElseThrow();
    }

    public void remove(long userId, long id) {
        owned(userId, id);
        repo.delete(id);
        imap.forget(id);
        oauth.forget(id);
    }

    public List<Map<String, Object>> list(long userId) {
        Map<Long, Long> unread = messages.unreadByAccount(userId);
        Map<Long, String> signatures = repo.signatures(userId);
        return repo.forUser(userId).stream().map(a -> {
            Map<String, Object> m = view(a, unread.getOrDefault(a.id(), 0L));
            m.put("signature", signatures.get(a.id()));
            return m;
        }).toList();
    }

    public String signature(long id) {
        return repo.signature(id);
    }

    static final int SIGNATURE_LIMIT = 2000;

    /** Sets the signature for one mailbox; blank clears it. */
    public void setSignature(long userId, long id, String signature) {
        owned(userId, id);
        String s = signature == null ? "" : signature.replace("\r\n", "\n").strip();
        if (s.startsWith("-- \n")) s = s.substring(4).strip();            // they typed the separator; we add it
        if (s.length() > SIGNATURE_LIMIT) throw ApiException.bad("Keep the signature under " + SIGNATURE_LIMIT + " characters.");
        repo.setSignature(id, s.isEmpty() ? null : s);
    }

    public static Map<String, Object> view(Account a, long unread) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id());
        m.put("email", a.email());
        m.put("displayName", a.displayName());
        m.put("color", a.color());
        m.put("provider", a.provider());
        m.put("auth", a.oauth() ? "oauth" : "password");
        m.put("imapHost", a.imapHost());
        m.put("imapPort", a.imapPort());
        m.put("smtpHost", a.smtpHost());
        m.put("smtpPort", a.smtpPort());
        m.put("status", a.status());
        m.put("lastSyncAt", a.lastSyncAt() == null ? null : a.lastSyncAt().toInstant().toString());
        m.put("lastError", a.lastError());
        m.put("unread", unread);
        return m;
    }

    private static String defaultName(String email) {
        String local = email.substring(0, email.indexOf('@'));
        return local.isEmpty() ? email : Character.toUpperCase(local.charAt(0)) + local.substring(1);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
