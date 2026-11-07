package software.magizhchi.maillook.notify;

import nl.martijndwars.webpush.Notification;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import software.magizhchi.maillook.common.Json;

import java.security.Security;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Browser and phone alerts (Web Push with VAPID): they reach people whose MailLook tab is closed, on
 * Chrome, Edge and Firefox, and on Android or an iPhone that added MailLook to its home screen.
 * Configure with VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY (and VAPID_SUBJECT). Always best-effort.
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    public static final String OFF = "off";
    public static final String NEEDS_ME = "needs_me";
    public static final String ALL = "all";
    public static final Set<String> MODES = Set.of(OFF, NEEDS_ME, ALL);

    public record Subscription(long id, String endpoint, String p256dh, String auth) {}

    private final JdbcTemplate jdbc;
    private final String publicKey;
    private final nl.martijndwars.webpush.PushService push;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    public PushService(JdbcTemplate jdbc,
                       @Value("${maillook.push.vapid-public-key:${VAPID_PUBLIC_KEY:}}") String publicKey,
                       @Value("${maillook.push.vapid-private-key:${VAPID_PRIVATE_KEY:}}") String privateKey,
                       @Value("${maillook.push.vapid-subject:${VAPID_SUBJECT:mailto:maillook@magizhchi.software}}") String subject) {
        this.jdbc = jdbc;
        this.publicKey = publicKey == null ? "" : publicKey.trim();
        nl.martijndwars.webpush.PushService svc = null;
        if (!this.publicKey.isEmpty() && privateKey != null && !privateKey.isBlank()) {
            try {
                if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) Security.addProvider(new BouncyCastleProvider());
                svc = new nl.martijndwars.webpush.PushService(this.publicKey, privateKey.trim(), subject);
                log.info("Web Push configured (VAPID)");
            } catch (Exception e) {
                log.warn("Web Push disabled, the VAPID keys don't work: {}", e.getMessage());
            }
        } else {
            log.info("Web Push not configured (set VAPID_PUBLIC_KEY and VAPID_PRIVATE_KEY)");
        }
        this.push = svc;
    }

    public boolean configured() {
        return push != null;
    }

    public String publicKey() {
        return publicKey;
    }

    public String mode(long userId) {
        List<String> m = jdbc.queryForList("SELECT notify FROM users WHERE id = ?", String.class, userId);
        return m.isEmpty() || m.get(0) == null ? NEEDS_ME : m.get(0);
    }

    public void setMode(long userId, String mode) {
        jdbc.update("UPDATE users SET notify = ? WHERE id = ?", mode, userId);
    }

    public int devices(long userId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM push_subscriptions WHERE user_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }

    /** Remembers this browser for this person (the same browser moves to whoever signs in last). */
    public void subscribe(long userId, String endpoint, String p256dh, String auth) {
        int n = jdbc.update("UPDATE push_subscriptions SET user_id = ?, p256dh = ?, auth = ? WHERE endpoint = ?", userId, p256dh, auth, endpoint);
        if (n == 0) {
            jdbc.update("INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth) VALUES (?, ?, ?, ?)", userId, endpoint, p256dh, auth);
        }
    }

    public void unsubscribe(long userId, String endpoint) {
        jdbc.update("DELETE FROM push_subscriptions WHERE user_id = ? AND endpoint = ?", userId, endpoint);
    }

    /** New mail: sent unless they turned alerts off, or chose "Needs me" and this isn't. */
    public void newMail(long userId, boolean needsMe, String title, String body, String url, String tag) {
        String mode = mode(userId);
        if (OFF.equals(mode) || (NEEDS_ME.equals(mode) && !needsMe)) return;
        send(userId, title, body, url, tag);
    }

    /** A reminder they set themselves (snooze, no reply): sent unless alerts are off. */
    public void reminder(long userId, String title, String body, String url, String tag) {
        if (OFF.equals(mode(userId))) return;
        send(userId, title, body, url, tag);
    }

    /** Sends to every browser this person allowed, in the background. Dead subscriptions are dropped. */
    public void send(long userId, String title, String body, String url, String tag) {
        if (push == null) return;
        List<Subscription> subs = jdbc.query("SELECT id, endpoint, p256dh, auth FROM push_subscriptions WHERE user_id = ?",
                (rs, i) -> new Subscription(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), userId);
        if (subs.isEmpty()) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title == null ? "Magizhchi MailLook" : clip(title, 120));
        payload.put("body", body == null ? "" : clip(body, 240));
        payload.put("url", url == null ? "/" : url);
        payload.put("tag", tag == null ? "maillook" : tag);
        byte[] bytes = Json.write(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        pool.submit(() -> {
            for (Subscription s : subs) {
                try {
                    var res = push.send(new Notification(s.endpoint(), s.p256dh(), s.auth(), bytes));
                    int code = res.getStatusLine().getStatusCode();
                    if (code == 404 || code == 410) jdbc.update("DELETE FROM push_subscriptions WHERE id = ?", s.id());
                    else if (code >= 400) log.warn("Push returned {}", code);
                } catch (Exception e) {
                    log.debug("Push failed: {}", e.toString());
                }
            }
        });
    }

    private static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
