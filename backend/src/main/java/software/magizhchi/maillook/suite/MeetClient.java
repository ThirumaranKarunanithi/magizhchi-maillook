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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Talks to Magizhchi Meet as the signed-in person, the same way MailLook talks to PA: a five-minute
 * Magizhchi ID token (shared suite secret) is traded at Meet's /api/v1/auth/sso for Meet's own
 * session, which is kept in memory and renewed after a 401. Nothing to set up per person.
 *
 * Meet itself emails invitees (with a calendar file) and writes the PA calendar event when a room is
 * scheduled, so MailLook never does either; it only writes the personal reply in the email thread.
 */
@Component
public class MeetClient {

    private static final Logger log = LoggerFactory.getLogger(MeetClient.class);

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

    public MeetClient(@Value("${magizhchi.apps.meet.api:}") String api, @Value("${magizhchi.apps.meet.web:}") String web,
                      SuiteTokens tokens, MailLookUsers users) {
        this.api = api == null ? "" : api.trim().replaceAll("/+$", "");
        this.web = web == null ? "" : web.trim().replaceAll("/+$", "");
        this.tokens = tokens;
        this.users = users;
    }

    public boolean configured() {
        return !api.isBlank() && !web.isBlank();
    }

    public String webUrl() {
        return web;
    }

    /** The link people open to join a room (Meet builds it the same way in its own emails). */
    public String roomLink(String cardId) {
        return web + "/room/" + cardId;
    }

    /** Calls Meet as this MailLook user, signing in (again after a 401) as needed. */
    public Reply as(long userId, String method, String path, Object body) {
        if (!configured()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Magizhchi Meet isn't set up on this server (MAGIZHCHI_MEET_URL).");
        Reply r = send(method, path, session(userId).token(), body);
        if (r.code() == 401) {
            sessions.remove(userId);
            r = send(method, path, session(userId).token(), body);
        }
        return r;
    }

    /** Like {@link #as} but turns anything other than 2xx into a message the person can act on. */
    public JsonNode call(long userId, String method, String path, Object body, String what) {
        Reply r = as(userId, method, path, body);
        if (r.ok()) return r.json();
        if (r.code() == 0) throw new ApiException(HttpStatus.BAD_GATEWAY, "Can't reach Magizhchi Meet right now, so it couldn't " + what + ". Try again in a minute.");
        if (r.code() == 403) throw new ApiException(HttpStatus.FORBIDDEN, "Magizhchi Meet says you can't " + what + " in that room. Only the room's admins can.");
        if (r.code() == 404) throw new ApiException(HttpStatus.NOT_FOUND, "That Magizhchi Meet room no longer exists.");
        String said = r.message();
        throw new ApiException(HttpStatus.BAD_GATEWAY, "Magizhchi Meet couldn't " + what + " (" + r.code() + ")" + (said == null ? "." : ": " + said));
    }

    private Session session(long userId) {
        Session s = sessions.get(userId);
        if (s != null && s.until() > System.currentTimeMillis()) return s;
        synchronized (locks.computeIfAbsent(userId, k -> new Object())) {
            s = sessions.get(userId);
            if (s != null && s.until() > System.currentTimeMillis()) return s;
            return signIn(userId);
        }
    }

    private Session signIn(long userId) {
        SuiteTokens.Identity who = users.identity(userId).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                "Sign in with Magizhchi ID to use Magizhchi Meet."));
        Reply r = send("POST", "/api/v1/auth/sso", null, Map.of("token", tokens.mint(who)));
        if (!r.ok()) {
            if (r.code() == 0) throw new ApiException(HttpStatus.BAD_GATEWAY, "Can't reach Magizhchi Meet right now.");
            if (r.code() == 401 || r.code() == 503) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Magizhchi Meet didn't accept the sign-in. Its MAGIZHCHI_JWT_SECRET must match MailLook's.");
            }
            String said = r.message();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Magizhchi Meet answered " + r.code() + " to the sign-in" + (said == null ? "." : ": " + said));
        }
        String token = r.json() == null ? "" : r.json().path("token").asText("");
        if (token.isBlank()) throw new ApiException(HttpStatus.BAD_GATEWAY, "Magizhchi Meet signed you in but sent no session back.");
        // Meet's sessions last months; renew every six hours anyway so a revoked one doesn't linger.
        Session fresh = new Session(token, System.currentTimeMillis() + 6 * 3600_000L);
        sessions.put(userId, fresh);
        return fresh;
    }

    private Reply send(String method, String path, String bearer, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(api + path))
                    .timeout(Duration.ofSeconds(20))
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
            log.debug("Meet call {} {} failed: {}", method, path, e.toString());
            return new Reply(0, null, e.getMessage());
        }
    }
}
