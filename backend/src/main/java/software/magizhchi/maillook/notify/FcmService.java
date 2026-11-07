package software.magizhchi.maillook.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Firebase Cloud Messaging: alerts for the MailLook phone app, even while it's closed. Configure with one
 * variable, {@code FIREBASE_SERVICE_ACCOUNT}, holding the service-account JSON (Firebase console → Project
 * settings → Service accounts → Generate new private key); the same project Magizhchi Meet uses works.
 * The OAuth token is minted here (an RS256 JWT for Google's token endpoint), so no Google SDK is needed.
 * Best-effort: a hiccup never stops anything else.
 */
@Service
public class FcmService {

    private static final Logger log = LoggerFactory.getLogger(FcmService.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    /** What became of a send: delivered to FCM, the token is dead (forget it), or something else failed. */
    public enum Result { SENT, DEAD, FAILED }

    private final ObjectMapper json = new ObjectMapper();
    private final RestClient http = RestClient.builder().build();

    private String projectId;
    private String clientEmail;
    private String tokenUri;
    private PrivateKey privateKey;

    private volatile String accessToken;
    private volatile Instant accessTokenExpiry = Instant.EPOCH;

    public FcmService(@Value("${maillook.fcm.service-account:${FIREBASE_SERVICE_ACCOUNT:}}") String serviceAccountJson) {
        if (serviceAccountJson == null || serviceAccountJson.isBlank()) {
            log.info("Phone alerts not configured (set FIREBASE_SERVICE_ACCOUNT to the Firebase service-account JSON)");
            return;
        }
        try {
            JsonNode sa = json.readTree(serviceAccountJson);
            projectId = sa.path("project_id").asText(null);
            clientEmail = sa.path("client_email").asText(null);
            tokenUri = sa.path("token_uri").asText("https://oauth2.googleapis.com/token");
            String pem = sa.path("private_key").asText("")
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
            if (projectId == null || clientEmail == null) throw new IllegalArgumentException("missing project_id or client_email");
            log.info("Phone alerts configured (Firebase project {})", projectId);
        } catch (Exception e) {
            log.warn("Phone alerts disabled, FIREBASE_SERVICE_ACCOUNT doesn't work: {}", e.getMessage());
            privateKey = null;
        }
    }

    public boolean configured() {
        return privateKey != null;
    }

    /**
     * One alert to one phone. Shown by Android itself on the "mail" or "reminders" channel (the app creates
     * both, with MailLook's sounds); tapping it opens {@code url} in the app.
     */
    public Result send(String deviceToken, String kind, String title, String body, String url, String tag) {
        if (!configured()) return Result.FAILED;
        String channel = "reminder".equals(kind) || "problem".equals(kind) ? "reminders" : "mail";
        try {
            Map<String, Object> notification = new LinkedHashMap<>();
            notification.put("channel_id", channel);
            notification.put("tag", tag == null ? "maillook" : tag);
            notification.put("icon", "ic_stat_maillook");
            notification.put("color", "#0D6A75");
            notification.put("sound", "reminders".equals(channel) ? "reminder" : "chime");
            Map<String, Object> message = Map.of(
                    "token", deviceToken,
                    "notification", Map.of("title", title == null ? "Magizhchi MailLook" : title, "body", body == null ? "" : body),
                    "data", Map.of("url", url == null ? "/" : url, "kind", kind == null ? "mail" : kind, "tag", tag == null ? "maillook" : tag),
                    "android", Map.of("priority", "reminders".equals(channel) ? "HIGH" : "NORMAL", "notification", notification));
            http.post()
                    .uri("https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send")
                    .header("Authorization", "Bearer " + accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("message", message))
                    .retrieve().toBodilessEntity();
            return Result.SENT;
        } catch (RestClientResponseException e) {
            String text = e.getResponseBodyAsString();
            if (e.getStatusCode().value() == 404 || text.contains("UNREGISTERED")) return Result.DEAD;
            log.warn("Phone alert failed ({}): {}", e.getStatusCode().value(), text.length() > 300 ? text.substring(0, 300) : text);
            return Result.FAILED;
        } catch (Exception e) {
            log.warn("Phone alert failed: {}", e.getMessage());
            return Result.FAILED;
        }
    }

    /** The OAuth access token for FCM, reused until five minutes before it expires. */
    private synchronized String accessToken() throws Exception {
        if (accessToken != null && Instant.now().isBefore(accessTokenExpiry.minusSeconds(300))) return accessToken;
        Instant now = Instant.now();
        String assertion = Jwts.builder()
                .issuer(clientEmail)
                .audience().single(tokenUri) // a plain string: Google rejects an array here
                .claim("scope", SCOPE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        form.add("assertion", assertion);
        JsonNode resp = http.post().uri(tokenUri).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode.class);
        if (resp == null || !resp.hasNonNull("access_token")) throw new IllegalStateException("no access_token");
        accessToken = resp.get("access_token").asText();
        accessTokenExpiry = now.plusSeconds(resp.path("expires_in").asLong(3600));
        return accessToken;
    }
}
