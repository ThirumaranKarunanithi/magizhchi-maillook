package software.magizhchi.maillook.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import software.magizhchi.maillook.common.ApiException;
import software.magizhchi.maillook.common.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Talks to Magizhchi PA as the signed-in person. MailLook signs a five-minute Magizhchi ID token
 * for them (shared suite secret), trades it at PA's /api/v1/auth/sso for PA's own session,
 * and calls PA's normal API with it, the same way Magizhchi Forms does. No PA password, no
 * separate AI key in MailLook.
 */
@Component
public class PaClient {

    private static final Logger log = LoggerFactory.getLogger(PaClient.class);

    public record Reply(int code, JsonNode json, String raw) {
        public boolean ok() {
            return code >= 200 && code < 300;
        }

        public String message() {
            if (json == null) return null;
            String m = json.path("message").asText(json.path("error").asText(""));
            return m.isBlank() ? null : m;
        }
    }

    private record Session(String token, long until) {}

    private final String api;
    private final String web;
    private final SuiteTokens tokens;
    private final MailLookUsers users;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();
    private final Map<Long, Object[]> aiStatus = new ConcurrentHashMap<>(); // userId -> {Boolean, expiresAtMillis}

    public PaClient(@Value("${magizhchi.apps.pa.api:}") String api, @Value("${magizhchi.apps.pa.web:}") String web,
                    SuiteTokens tokens, MailLookUsers users) {
        this.api = api == null ? "" : api.trim().replaceAll("/+$", "");
        this.web = web == null ? "" : web.trim().replaceAll("/+$", "");
        this.tokens = tokens;
        this.users = users;
    }

    public boolean configured() {
        return !api.isBlank();
    }

    public String webUrl() {
        return web;
    }

    /** Calls PA as this MailLook user. Signs in (or re-signs after a 401) as needed. */
    public Reply as(long userId, String method, String path, Object body) {
        if (!configured()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Magizhchi PA isn't set up on this server (MAGIZHCHI_PA_URL).");
        Session s = session(userId);
        Reply r = send(method, path, s.token(), body);
        if (r.code() == 401) {
            sessions.remove(userId);
            r = send(method, path, session(userId).token(), body);
        }
        return r;
    }

    private Session session(long userId) {
        Session s = sessions.get(userId);
        if (s != null && s.until() > System.currentTimeMillis()) return s;
        // One sign-in at a time per person, so parallel requests (e.g. background sorting) share it.
        synchronized (locks.computeIfAbsent(userId, k -> new Object())) {
            s = sessions.get(userId);
            if (s != null && s.until() > System.currentTimeMillis()) return s;
            return signIn(userId);
        }
    }

    private Session signIn(long userId) {
        SuiteTokens.Identity who = users.identity(userId).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                "Sign in with Magizhchi ID to use Magizhchi PA."));
        Reply r = send("POST", "/api/v1/auth/sso", null, Map.of("token", tokens.mint(who)));
        if (!r.ok()) {
            String said = r.message();
            if (r.code() == 503) throw new ApiException(HttpStatus.BAD_GATEWAY, "Magizhchi PA doesn't have Magizhchi ID sign-in switched on (MAGIZHCHI_JWT_SECRET).");
            if (r.code() == 401) throw new ApiException(HttpStatus.BAD_GATEWAY, "Magizhchi PA didn't accept the sign-in. Its MAGIZHCHI_JWT_SECRET must match MailLook's.");
            if (r.code() == 0) throw new ApiException(HttpStatus.BAD_GATEWAY, "Can't reach Magizhchi PA right now.");
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Magizhchi PA answered " + r.code() + " to the sign-in" + (said == null ? "." : ": " + said));
        }
        String token = r.json().path("accessToken").asText("");
        if (token.isBlank()) throw new ApiException(HttpStatus.BAD_GATEWAY, "Magizhchi PA signed you in but sent no session back.");
        long seconds = r.json().path("expiresIn").asLong(900);
        // PA reports seconds; stay well inside it and never longer than an hour.
        long life = Math.max(60, Math.min(seconds, 3600) - 60);
        Session fresh = new Session(token, System.currentTimeMillis() + life * 1000);
        sessions.put(userId, fresh);
        return fresh;
    }

    private Reply send(String method, String path, String bearer, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(api + path))
                    .timeout(Duration.ofSeconds(path.contains("/assistant/") ? 120 : 20))
                    .header("Accept", "application/json");
            if (bearer != null) b.header("Authorization", "Bearer " + bearer);
            if (body != null) {
                b.header("Content-Type", "application/json");
                b.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json = null;
            try {
                json = res.body() == null || res.body().isBlank() ? null : mapper.readTree(res.body());
            } catch (Exception ignored) {
            }
            return new Reply(res.statusCode(), json, res.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(0, null, e.getMessage());
        } catch (Exception e) {
            log.debug("PA call {} {} failed: {}", method, path, e.toString());
            return new Reply(0, null, e.getMessage());
        }
    }

    // ── what MailLook uses ────────────────────────────────────────────

    /** True when PA can answer AI requests for this user (cached for five minutes). */
    public boolean aiEnabled(long userId) {
        Object[] c = aiStatus.get(userId);
        if (c != null && (Long) c[1] > System.currentTimeMillis()) return (Boolean) c[0];
        boolean on;
        try {
            Reply r = as(userId, "GET", "/api/v1/assistant/status", null);
            on = r.ok() && r.json() != null && r.json().path("enabled").asBoolean(false);
        } catch (ApiException e) {
            on = false;
        }
        aiStatus.put(userId, new Object[]{on, System.currentTimeMillis() + (on ? 300_000 : 60_000)});
        return on;
    }

    /** A tool-free completion on PA's AI; returns the parsed JSON answer. */
    public JsonNode completeJson(long userId, String system, String prompt, int maxTokens) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("system", system);
        body.put("prompt", prompt.length() > 60000 ? prompt.substring(0, 60000) : prompt);
        body.put("maxTokens", maxTokens);
        body.put("json", true);
        Reply r = as(userId, "POST", "/api/v1/assistant/complete", body);
        if (r.code() == 503) {
            aiStatus.remove(userId);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Magizhchi PA's AI is off or busy. " + (r.message() == null ? "" : r.message()));
        }
        if (!r.ok()) throw new ApiException(HttpStatus.BAD_GATEWAY, "Magizhchi PA's AI answered " + r.code() + (r.message() == null ? "." : ": " + r.message()));
        String text = r.json() == null ? "" : r.json().path("text").asText("");
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Magizhchi PA's AI sent an answer MailLook couldn't read.");
        }
    }

    public Map<String, Object> status(long userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", configured());
        m.put("webUrl", web.isBlank() ? null : web);
        if (!configured()) {
            m.put("connected", false);
            m.put("aiEnabled", false);
            m.put("message", "Magizhchi PA isn't set up on this server.");
            return m;
        }
        try {
            // A live call, not just a cached session: the sidebar should say so when PA is down.
            Reply r = as(userId, "GET", "/api/v1/assistant/status", null);
            if (r.code() == 0) throw new ApiException(HttpStatus.BAD_GATEWAY, "Can't reach Magizhchi PA right now.");
            boolean ai = r.ok() && r.json() != null && r.json().path("enabled").asBoolean(false);
            aiStatus.put(userId, new Object[]{ai, System.currentTimeMillis() + (ai ? 300_000 : 60_000)});
            m.put("connected", true);
            m.put("aiEnabled", ai);
            m.put("message", ai ? "Connected. AI comes from Magizhchi PA." : "Connected, but PA's AI is off (PA needs ANTHROPIC_API_KEY).");
        } catch (ApiException e) {
            m.put("connected", false);
            m.put("aiEnabled", false);
            m.put("message", e.getMessage());
        }
        return m;
    }
}
