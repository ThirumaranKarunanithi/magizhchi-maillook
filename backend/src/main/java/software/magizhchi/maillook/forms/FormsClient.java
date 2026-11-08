package software.magizhchi.maillook.forms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

/**
 * MailLook → Magizhchi Forms, server to server, as the person (their Magizhchi ID email):
 *
 *   GET  /api/v1/integration/maillook/overview?email=&since=   approvals waiting, new responses, forms
 *   POST /api/v1/integration/maillook/decision                 approve / reject a response
 *   GET  /api/v1/integration/maillook/forms?email=             forms they can send
 *   POST /api/v1/integration/maillook/filled                   who of these people has filled a form
 *   GET  /api/v1/integration/maillook/person?email=&who=       what this person filled of theirs
 *   POST /api/v1/integration/maillook/poll                     a one-question poll to put in a mail
 *   POST /api/v1/integration/maillook/build                    a form made by AI from a request
 *   POST /api/v1/integration/maillook/response                 a mail turned into a response
 *
 * Every call carries X-Integration-Secret (MAGIZHCHI_FORMS_SECRET here, MAILLOOK_INTEGRATION_SECRET on
 * Forms). Links put in mail carry a per-person token signed with the same secret, so Forms knows who
 * filled a form without asking, and nobody can forge another person's answer.
 */
@Component
public class FormsClient {
    private static final Logger log = LoggerFactory.getLogger(FormsClient.class);

    /** Forms' answer: the HTTP status and its JSON (null when Forms couldn't be reached). */
    public record Reply(int code, JsonNode json) {
        public boolean ok() {
            return code >= 200 && code < 300 && json != null;
        }
    }

    private final String api;
    private final String web;
    private final String secret;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public FormsClient(@Value("${magizhchi.apps.forms.api:}") String api,
                       @Value("${magizhchi.apps.forms.web:https://forms.magizhchi.software}") String web,
                       @Value("${magizhchi.apps.forms.secret:}") String secret) {
        this.api = api == null ? "" : api.trim().replaceAll("/+$", "");
        this.web = web == null ? "" : web.trim().replaceAll("/+$", "");
        this.secret = secret == null ? "" : secret.trim();
    }

    public boolean configured() {
        return !api.isBlank() && !secret.isBlank();
    }

    public String web() {
        return web;
    }

    /** Forms' server: poll buttons in mail lead to its public poll page. */
    public String api() {
        return api;
    }

    /** True when the integration secret given matches ours (constant time). */
    public boolean secretMatches(String given) {
        return !secret.isBlank() && given != null && java.security.MessageDigest.isEqual(
                given.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8));
    }

    public Reply get(String path, Map<String, String> query) {
        StringBuilder url = new StringBuilder(api).append("/api/v1/integration/maillook").append(path);
        char sep = '?';
        for (Map.Entry<String, String> q : query.entrySet()) {
            if (q.getValue() == null) continue;
            url.append(sep).append(q.getKey()).append('=').append(URLEncoder.encode(q.getValue(), StandardCharsets.UTF_8));
            sep = '&';
        }
        return call(HttpRequest.newBuilder(URI.create(url.toString())).GET(), path);
    }

    public Reply post(String path, Object body) {
        try {
            String json = mapper.writeValueAsString(body);
            return call(HttpRequest.newBuilder(URI.create(api + "/api/v1/integration/maillook" + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)), path);
        } catch (Exception e) {
            return new Reply(500, null);
        }
    }

    private Reply call(HttpRequest.Builder req, String path) {
        if (!configured()) return new Reply(503, null);
        try {
            HttpResponse<String> res = http.send(req.timeout(Duration.ofSeconds(25)).header("X-Integration-Secret", secret)
                    .header("Accept", "application/json").build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode json = null;
            try {
                json = res.body() == null || res.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(res.body());
            } catch (Exception ignored) {
                // not JSON: keep the status only
            }
            return new Reply(res.statusCode(), json);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(503, null);
        } catch (Exception e) {
            log.info("Forms {} failed: {}", path, e.toString());
            return new Reply(503, null);
        }
    }

    /**
     * The token a link in mail carries for one person and one form: {@code base64url(formId|email)} and
     * an HMAC of it. Forms checks it with the same secret.
     */
    public String personToken(String formId, String email) {
        String payload = formId + "|" + email.trim().toLowerCase(Locale.ROOT);
        String p = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return p + "." + sign(p);
    }

    String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(("maillook-forms-link:" + secret).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(h, 18));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
