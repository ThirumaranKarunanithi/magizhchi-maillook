package software.magizhchi.maillook.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.magizhchi.maillook.common.ApiException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sign in with Google / Microsoft for a mailbox. The web app asks for the provider's sign-in page,
 * the person signs in there (MailLook never sees their password), the provider sends them back to
 * the callback here, and the callback adds the mailbox and returns them to Manage accounts.
 */
@RestController
@RequestMapping("/api")
public class OAuthController {

    private static final Logger log = LoggerFactory.getLogger(OAuthController.class);
    private static final Duration PENDING_FOR = Duration.ofMinutes(15);
    private static final SecureRandom RANDOM = new SecureRandom();

    public record StartRequest(String email) {}

    private record Pending(long userId, String provider, String verifier, Instant started) {}

    private final OAuthTokens oauth;
    private final AccountService accounts;
    private final String publicUrl;
    private final String webUrl;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public OAuthController(OAuthTokens oauth, AccountService accounts,
                           @Value("${maillook.public-url:http://localhost:8097}") String publicUrl,
                           @Value("${maillook.web-url:http://localhost:5177}") String webUrl) {
        this.oauth = oauth;
        this.accounts = accounts;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
        this.webUrl = webUrl.replaceAll("/+$", "");
    }

    /** Which sign-in buttons this server can offer. */
    @GetMapping("/accounts/oauth")
    public Map<String, Object> providers() {
        List<Map<String, String>> list = oauth.configured().stream()
                .map(p -> Map.of("id", p.id(), "label", p.label())).toList();
        return Map.of("providers", list);
    }

    /** The provider's sign-in page for this person; the web app sends the browser there. */
    @PostMapping("/accounts/oauth/{provider}")
    public Map<String, Object> start(@RequestAttribute long userId, @PathVariable String provider,
                                     @RequestBody(required = false) StartRequest req) {
        OAuthTokens.Provider p = oauth.provider(provider);
        sweep();
        String state = random(24);
        String verifier = random(48);
        pending.put(state, new Pending(userId, p.id(), verifier, Instant.now()));
        String hint = req == null || req.email() == null || !req.email().contains("@") ? null : req.email().trim();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", oauth.authorizeUrl(p, redirectUri(p.id()), state, challenge(verifier), hint));
        return out;
    }

    /** Where Google / Microsoft send the browser back. Always ends on Manage accounts. */
    @GetMapping("/oauth/{provider}/callback")
    public ResponseEntity<Void> callback(@PathVariable String provider,
                                         @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         @RequestParam(name = "error_description", required = false) String errorDescription) {
        Pending p = state == null ? null : pending.remove(state);
        if (p == null || p.started().plus(PENDING_FOR).isBefore(Instant.now()) || !p.provider().equalsIgnoreCase(provider)) {
            return back("connectError", "That sign-in took too long or was already used. Please try again.");
        }
        if (error != null) {
            boolean cancelled = "access_denied".equals(error) || "consent_required".equals(error);
            log.info("Mailbox sign-in with {} came back with {} {}", provider, error, errorDescription);
            return back("connectError", cancelled
                    ? "Sign-in was cancelled, so nothing was added."
                    : "Sign-in didn't finish (" + error + "). Please try again.");
        }
        if (code == null || code.isBlank()) return back("connectError", "Sign-in didn't finish. Please try again.");
        try {
            OAuthTokens.Provider pr = oauth.provider(p.provider());
            OAuthTokens.Grant g = oauth.exchange(pr, code, redirectUri(pr.id()), p.verifier());
            Account a = accounts.connectOAuth(p.userId(), pr, g);
            return back("connected", a.email());
        } catch (ApiException e) {
            return back("connectError", e.getMessage());
        } catch (Exception e) {
            log.warn("Mailbox sign-in with {} failed: {}", provider, e.toString());
            return back("connectError", "Something went wrong while adding the mailbox. Please try again.");
        }
    }

    private String redirectUri(String provider) {
        return publicUrl + "/api/oauth/" + provider + "/callback";
    }

    private ResponseEntity<Void> back(String key, String value) {
        URI to = URI.create(webUrl + "/accounts?" + key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
        HttpHeaders h = new HttpHeaders();
        h.setLocation(to);
        h.setCacheControl("no-store");
        return new ResponseEntity<>(h, HttpStatus.FOUND);
    }

    private void sweep() {
        Instant cutoff = Instant.now().minus(PENDING_FOR);
        pending.values().removeIf(x -> x.started().isBefore(cutoff));
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String challenge(String verifier) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
