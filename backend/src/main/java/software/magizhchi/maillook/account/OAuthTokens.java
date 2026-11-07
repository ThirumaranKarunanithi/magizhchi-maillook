package software.magizhchi.maillook.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import software.magizhchi.maillook.common.ApiException;
import software.magizhchi.maillook.common.SecretBox;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * "Sign in with Google / Microsoft" for mailboxes: the provider's sign-in page, the code-for-token
 * exchange, and fresh access tokens for IMAP/SMTP (XOAUTH2) from the stored refresh token.
 * A provider is offered only when its client id and secret are set.
 */
@Component
public class OAuthTokens {

    private static final Logger log = LoggerFactory.getLogger(OAuthTokens.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Provider(String id, String label, String authUrl, String tokenUrl, String scope,
                           String clientId, String clientSecret, Map<String, String> extra, ProviderDetector.Preset servers) {
        public boolean configured() {
            return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
        }
    }

    /** What the token endpoint gave back for a sign-in. */
    public record Grant(String accessToken, String refreshToken, long expiresIn, String email) {}

    private record Token(String value, Instant expires) {}

    private final Map<String, Provider> providers = new LinkedHashMap<>();
    private final Map<Long, Token> cache = new ConcurrentHashMap<>();
    private final SecretBox secrets;
    private final AccountRepo accounts;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public OAuthTokens(SecretBox secrets, AccountRepo accounts,
                       @Value("${maillook.oauth.google.client-id:}") String googleId,
                       @Value("${maillook.oauth.google.client-secret:}") String googleSecret,
                       @Value("${maillook.oauth.google.auth-url:https://accounts.google.com/o/oauth2/v2/auth}") String googleAuth,
                       @Value("${maillook.oauth.google.token-url:https://oauth2.googleapis.com/token}") String googleToken,
                       @Value("${maillook.oauth.microsoft.client-id:}") String msId,
                       @Value("${maillook.oauth.microsoft.client-secret:}") String msSecret,
                       @Value("${maillook.oauth.microsoft.authority:https://login.microsoftonline.com/common/oauth2/v2.0}") String msAuthority,
                       @Value("${maillook.oauth.google.imap-host:imap.gmail.com}") String googleImap,
                       @Value("${maillook.oauth.google.imap-port:993}") int googleImapPort,
                       @Value("${maillook.oauth.google.smtp-host:smtp.gmail.com}") String googleSmtp,
                       @Value("${maillook.oauth.google.smtp-port:465}") int googleSmtpPort,
                       @Value("${maillook.oauth.microsoft.imap-host:outlook.office365.com}") String msImap,
                       @Value("${maillook.oauth.microsoft.imap-port:993}") int msImapPort,
                       @Value("${maillook.oauth.microsoft.smtp-host:smtp.office365.com}") String msSmtp,
                       @Value("${maillook.oauth.microsoft.smtp-port:587}") int msSmtpPort) {
        this.secrets = secrets;
        this.accounts = accounts;
        providers.put("google", new Provider("google", "Google", googleAuth, googleToken,
                "openid email https://mail.google.com/", googleId, googleSecret,
                Map.of("access_type", "offline", "prompt", "consent", "include_granted_scopes", "true"),
                new ProviderDetector.Preset("google", googleImap, googleImapPort, googleSmtp, googleSmtpPort, "Signed in with Google.")));
        String ms = msAuthority.replaceAll("/+$", "");
        providers.put("microsoft", new Provider("microsoft", "Microsoft", ms + "/authorize", ms + "/token",
                "openid email offline_access https://outlook.office.com/IMAP.AccessAsUser.All https://outlook.office.com/SMTP.Send",
                msId, msSecret, Map.of("prompt", "select_account"),
                new ProviderDetector.Preset("microsoft", msImap, msImapPort, msSmtp, msSmtpPort, "Signed in with Microsoft.")));
    }

    public List<Provider> configured() {
        return providers.values().stream().filter(Provider::configured).toList();
    }

    public Provider provider(String id) {
        Provider p = providers.get(id == null ? "" : id.toLowerCase(Locale.ROOT));
        if (p == null) throw ApiException.notFound("Sign-in provider");
        if (!p.configured()) throw ApiException.bad(p.label() + " sign-in isn't set up on this server yet.");
        return p;
    }

    public boolean offers(String providerId) {
        Provider p = providerId == null ? null : providers.get(providerId);
        return p != null && p.configured();
    }

    public String authorizeUrl(Provider p, String redirectUri, String state, String challenge, String loginHint) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("client_id", p.clientId());
        q.put("redirect_uri", redirectUri);
        q.put("response_type", "code");
        q.put("scope", p.scope());
        q.put("state", state);
        q.put("code_challenge", challenge);
        q.put("code_challenge_method", "S256");
        q.putAll(p.extra());
        if (loginHint != null && !loginHint.isBlank()) q.put("login_hint", loginHint.trim());
        return p.authUrl() + (p.authUrl().contains("?") ? "&" : "?") + form(q);
    }

    public Grant exchange(Provider p, String code, String redirectUri, String verifier) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("grant_type", "authorization_code");
        f.put("code", code);
        f.put("redirect_uri", redirectUri);
        f.put("client_id", p.clientId());
        f.put("client_secret", p.clientSecret());
        f.put("code_verifier", verifier);
        JsonNode r = post(p, f);
        String access = r.path("access_token").asText("");
        if (access.isEmpty()) throw new ApiException(HttpStatus.BAD_GATEWAY, p.label() + " didn't send a sign-in token. Please try again.");
        String email = emailFrom(r.path("id_token").asText(""));
        if (email == null) throw new ApiException(HttpStatus.BAD_GATEWAY, p.label() + " didn't say which email address you signed in with.");
        String refresh = r.path("refresh_token").asText("");
        if (refresh.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, p.label() + " didn't allow offline access, so MailLook couldn't keep your mail in sync. Please try again and accept every permission.");
        }
        return new Grant(access, refresh, r.path("expires_in").asLong(3600), email.toLowerCase(Locale.ROOT));
    }

    /** Keeps the access token from a fresh sign-in, so the first sync doesn't refresh straight away. */
    public void remember(long accountId, Grant g) {
        cache.put(accountId, new Token(g.accessToken(), Instant.now().plusSeconds(Math.max(60, g.expiresIn()) - 60)));
    }

    public void forget(long accountId) {
        cache.remove(accountId);
    }

    /** A working access token for an OAuth mailbox, refreshed when it's within a minute of expiring. */
    public String accessToken(Account a) {
        Token t = cache.get(a.id());
        if (t != null && t.expires().isAfter(Instant.now())) return t.value();
        Provider p = providers.get(a.provider());
        if (p == null || !p.configured()) {
            throw new ApiException(HttpStatus.CONFLICT, "Sign-in with " + (p == null ? a.provider() : p.label())
                    + " is turned off on this server, so " + a.email() + " can't connect.");
        }
        String refresh = secrets.decrypt(a.passwordEnc());
        if (refresh == null) throw signInAgain(p, a);
        Map<String, String> f = new LinkedHashMap<>();
        f.put("grant_type", "refresh_token");
        f.put("refresh_token", refresh);
        f.put("client_id", p.clientId());
        f.put("client_secret", p.clientSecret());
        if ("microsoft".equals(p.id())) f.put("scope", p.scope());
        JsonNode r;
        try {
            r = post(p, f);
        } catch (ApiException e) {
            if (e.status() == HttpStatus.UNAUTHORIZED) throw signInAgain(p, a);
            throw e;
        }
        String access = r.path("access_token").asText("");
        if (access.isEmpty()) throw signInAgain(p, a);
        String rotated = r.path("refresh_token").asText("");
        if (!rotated.isEmpty() && !rotated.equals(refresh)) {
            accounts.updateSecret(a.id(), secrets.encrypt(rotated));
        }
        cache.put(a.id(), new Token(access, Instant.now().plusSeconds(Math.max(120, r.path("expires_in").asLong(3600)) - 60)));
        return access;
    }

    private static ApiException signInAgain(Provider p, Account a) {
        return new ApiException(HttpStatus.UNAUTHORIZED, p.label() + " needs you to sign in again for " + a.email()
                + ". Open Manage accounts and choose Sign in again.");
    }

    private JsonNode post(Provider p, Map<String, String> f) {
        HttpResponse<String> res;
        try {
            res = http.send(HttpRequest.newBuilder(URI.create(p.tokenUrl()))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(form(f))).build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Couldn't reach " + p.label() + " sign-in. Please try again.");
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Couldn't reach " + p.label() + " sign-in. Please try again.");
        }
        JsonNode body;
        try {
            body = JSON.readTree(res.body() == null || res.body().isBlank() ? "{}" : res.body());
        } catch (Exception e) {
            body = JSON.createObjectNode();
        }
        if (res.statusCode() / 100 != 2) {
            String err = body.path("error").asText("");
            log.warn("{} token endpoint said {} {} {}", p.label(), res.statusCode(), err, body.path("error_description").asText(""));
            if ("invalid_grant".equals(err) || res.statusCode() == 401) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, p.label() + " sign-in has expired or was taken back.");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, p.label() + " sign-in failed (" + (err.isEmpty() ? res.statusCode() : err) + "). Please try again.");
        }
        return body;
    }

    /**
     * The signed-in address from the ID token. The token came straight from the provider's token
     * endpoint over TLS, so its claims are read without checking the signature again.
     */
    static String emailFrom(String idToken) {
        String[] parts = idToken == null ? new String[0] : idToken.split("\\.");
        if (parts.length < 2) return null;
        try {
            JsonNode c = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            List<String> tries = new ArrayList<>();
            tries.add(c.path("email").asText(""));
            tries.add(c.path("preferred_username").asText(""));
            tries.add(c.path("upn").asText(""));
            for (String t : tries) {
                if (t.contains("@") && !t.contains(" ")) return t;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static String form(Map<String, String> f) {
        return f.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
