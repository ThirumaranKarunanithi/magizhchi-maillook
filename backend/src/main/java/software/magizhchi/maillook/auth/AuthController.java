package software.magizhchi.maillook.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.magizhchi.maillook.common.ApiException;
import software.magizhchi.maillook.mail.SyncService;
import software.magizhchi.maillook.suite.IdReporter;
import software.magizhchi.maillook.suite.MailLookUsers;
import software.magizhchi.maillook.suite.SuiteTokens;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sign-in is Magizhchi ID, like every suite app: the browser goes to the ID sign-in page,
 * comes back with a Magizhchi ID token, and trades it here for a MailLook session. Mailboxes
 * are then added (with their own passwords) under Manage accounts.
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    public record SsoRequest(String idToken) {}

    private final SuiteTokens suite;
    private final MailLookUsers users;
    private final SessionTokens tokens;
    private final IdReporter reporter;
    private final SyncService sync;

    public AuthController(SuiteTokens suite, MailLookUsers users, SessionTokens tokens, IdReporter reporter, SyncService sync) {
        this.suite = suite;
        this.users = users;
        this.tokens = tokens;
        this.reporter = reporter;
        this.sync = sync;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @PostMapping("/auth/sso")
    public Map<String, Object> sso(@RequestBody SsoRequest req, HttpServletRequest http) {
        SuiteTokens.Identity who = suite.verify(req == null ? null : req.idToken())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "That Magizhchi ID sign-in has expired or isn't valid. Please sign in again."));
        long userId = users.signIn(who);
        String fwd = http.getHeader("X-Forwarded-For");
        reporter.reportSignIn(who.email(), http.getHeader("User-Agent"),
                fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : http.getRemoteAddr());
        sync.requestForUser(userId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", tokens.issue(userId));
        m.put("user", users.view(userId).orElseThrow());
        return m;
    }

    @GetMapping("/auth/me")
    public Map<String, Object> me(@RequestAttribute long userId) {
        return users.view(userId).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Please sign in again."));
    }
}
