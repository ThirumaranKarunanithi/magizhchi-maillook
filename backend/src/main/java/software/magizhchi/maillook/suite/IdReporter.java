package software.magizhchi.maillook.suite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Tells Magizhchi ID that this person signed in to MailLook (its /api/v1/provision/device-seen,
 * same as Forms and CRM), so MailLook appears under their connected apps. Best-effort and
 * silent; does nothing unless MAGIZHCHI_ID_BASE_URL and MAGIZHCHI_PROVISION_SECRET are set.
 */
@Component
public class IdReporter {

    public static final String APP_NAME = "Magizhchi MailLook";
    private static final Logger log = LoggerFactory.getLogger(IdReporter.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String baseUrl;
    private final String secret;

    public IdReporter(@Value("${magizhchi.base-url:}") String baseUrl,
                      @Value("${magizhchi.provision-secret:}") String secret) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.secret = secret == null ? "" : secret.trim();
    }

    public boolean enabled() {
        return !baseUrl.isBlank() && !secret.isBlank();
    }

    public void reportSignIn(String email, String userAgent, String clientIp) {
        if (!enabled()) return;
        CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> body = new java.util.LinkedHashMap<>();
                body.put("email", email);
                body.put("app", APP_NAME);
                body.put("kind", "web");
                body.put("userAgent", userAgent);
                body.put("clientIp", clientIp);
                HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/provision/device-seen"))
                        .header("Content-Type", "application/json")
                        .header("X-Provision-Secret", secret)
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(software.magizhchi.maillook.common.Json.write(body)))
                        .build();
                http.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                log.debug("device-seen report to Magizhchi ID failed: {}", e.getMessage());
            }
        });
    }
}
