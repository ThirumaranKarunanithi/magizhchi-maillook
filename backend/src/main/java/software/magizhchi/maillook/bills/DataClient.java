package software.magizhchi.maillook.bills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * MailLook → Magizhchi Data, server to server: Data reads bills out of mail with its Gemini engine and
 * charges the person's own Data credits (the account their Magizhchi ID email opens).
 *
 *   GET  /api/v1/integration/maillook/account?email=   linked, balance, costPerBill
 *   POST /api/v1/integration/maillook/bill              multipart email, ref, text, free, files[]
 *
 * Every call carries X-Integration-Secret (MAGIZHCHI_DATA_SECRET here, MAILLOOK_INTEGRATION_SECRET on
 * Data). With the URL or secret unset, Bills are hidden.
 */
@Component
public class DataClient {
    private static final Logger log = LoggerFactory.getLogger(DataClient.class);

    /** A file to read with the mail. */
    public record File(String name, String contentType, byte[] bytes) {}

    /** Data's answer: the HTTP status and its JSON (null when Data couldn't be reached). */
    public record Reply(int code, JsonNode json) {
        public boolean ok() {
            return code >= 200 && code < 300 && json != null;
        }
        public String errorCode() {
            return json == null ? "" : json.path("code").asText("");
        }
    }

    private final String api;
    private final String secret;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public DataClient(@Value("${magizhchi.apps.data.api:}") String api,
                      @Value("${magizhchi.apps.data.secret:}") String secret) {
        this.api = api == null ? "" : api.trim().replaceAll("/+$", "");
        this.secret = secret == null ? "" : secret.trim();
    }

    public boolean configured() {
        return !api.isBlank() && !secret.isBlank();
    }

    public Reply account(String email) {
        String url = api + "/api/v1/integration/maillook/account?email=" + URLEncoder.encode(email, StandardCharsets.UTF_8);
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("X-Integration-Secret", secret).GET().build(), HttpResponse.BodyHandlers.ofString());
            return reply(res);
        } catch (Exception e) {
            log.info("Data account check failed: {}", e.toString());
            return new Reply(503, null);
        }
    }

    public Reply readBill(String email, String ref, String text, boolean free, List<File> files) {
        String boundary = "----MailLook" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        field(out, boundary, "email", email);
        field(out, boundary, "ref", ref == null ? "" : ref);
        if (text != null && !text.isBlank()) field(out, boundary, "text", text);
        field(out, boundary, "free", String.valueOf(free));
        for (File f : files) {
            String name = f.name().replaceAll("[\"\\r\\n]", "_");
            out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"" + name
                    + "\"\r\nContent-Type: " + (f.contentType() == null ? "application/octet-stream" : f.contentType())
                    + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.writeBytes(f.bytes());
            out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(api + "/api/v1/integration/maillook/bill"))
                    .timeout(Duration.ofSeconds(90))
                    .header("X-Integration-Secret", secret)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
            return reply(res);
        } catch (Exception e) {
            log.info("Data bill read failed: {}", e.toString());
            return new Reply(503, null);
        }
    }

    private Reply reply(HttpResponse<String> res) {
        JsonNode json = null;
        try {
            if (res.body() != null && !res.body().isBlank()) json = mapper.readTree(res.body());
        } catch (Exception ignored) {
            // not JSON: a proxy error page
        }
        return new Reply(res.statusCode(), json);
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) {
        out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}
