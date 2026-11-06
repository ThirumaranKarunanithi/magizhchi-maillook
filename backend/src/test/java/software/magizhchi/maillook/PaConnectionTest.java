package software.magizhchi.maillook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Mail ↔ Magizhchi PA over suite SSO, against a stand-in PA that checks what Mail sends exactly
 * as PA would: the SSO token must verify with the suite secret, every call must carry the PA
 * session, and AI must go through /api/v1/assistant/complete.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pa;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "maillook.sync.interval-ms=3600000",
        "maillook.ai.api-key="})
@AutoConfigureMockMvc
class PaConnectionTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("tiru@magizhchi.test", "tiru@magizhchi.test", "pw"));

    static final String ACCOUNT_ID = "2b7c1f0e-2222-4d3a-8b44-000000000002";
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<String> calls = new CopyOnWriteArrayList<>();
    static final List<JsonNode> completions = new CopyOnWriteArrayList<>();
    static final List<JsonNode> tasks = new CopyOnWriteArrayList<>();
    static final List<JsonNode> events = new CopyOnWriteArrayList<>();
    static volatile Claims ssoClaims;
    static final HttpServer pa = startFakePa();

    @DynamicPropertySource
    static void paUrl(DynamicPropertyRegistry r) {
        r.add("magizhchi.apps.pa.api", () -> "http://localhost:" + pa.getAddress().getPort());
        r.add("magizhchi.apps.pa.web", () -> "http://localhost:5173");
    }

    @AfterAll
    static void stop() {
        pa.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void mailUsesPaForAiTasksAndEvents() throws Exception {
        deliver("Can we move Saturday's batch to 6pm? Please confirm by Friday.");

        String token = call(post("/api/auth/sso").contentType(MediaType.APPLICATION_JSON).content(
                "{\"idToken\":\"" + TestIds.idToken(ACCOUNT_ID, "tiru@magizhchi.test", "Tiru") + "\"}"), null, 200).path("token").asText();
        call(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(
                "{\"email\":\"tiru@magizhchi.test\",\"password\":\"pw\",\"imapHost\":\"localhost\",\"imapPort\":3143,\"smtpHost\":\"localhost\",\"smtpPort\":3025}"),
                token, 200);
        long msgId = waitForMessage(token);

        // Connection: Mail signed in to PA as the same Magizhchi ID person.
        JsonNode status = call(get("/api/pa/status"), token, 200);
        assertThat(status.path("connected").asBoolean()).isTrue();
        assertThat(status.path("aiEnabled").asBoolean()).isTrue();
        assertThat(ssoClaims.getSubject()).isEqualTo(ACCOUNT_ID);
        assertThat(ssoClaims.get("email", String.class)).isEqualTo("tiru@magizhchi.test");
        assertThat(ssoClaims.getIssuer()).isEqualTo("magizhchi-id");
        assertThat(ssoClaims.getExpiration().getTime() - System.currentTimeMillis()).isLessThanOrEqualTo(5 * 60_000);

        JsonNode ai = call(get("/api/ai/status"), token, 200);
        assertThat(ai.path("provider").asText()).isEqualTo("pa");

        // AI lane sorting runs through PA in the background after the first sync.
        for (int i = 0; i < 100 && completions.stream().noneMatch(c -> c.path("system").asText().contains("sort email")); i++) Thread.sleep(100);
        for (int i = 0; i < 50; i++) {
            if ("ai".equals(call(get("/api/messages/" + msgId), token, 200).path("laneSource").asText())) break;
            Thread.sleep(100);
        }
        assertThat(call(get("/api/messages/" + msgId), token, 200).path("laneSource").asText()).isEqualTo("ai");

        // Summary + drafts come from PA's /assistant/complete.
        JsonNode summary = call(post("/api/messages/" + msgId + "/summary"), token, 200);
        assertThat(summary.path("summary").asText()).isEqualTo("Priya wants Saturday's batch moved to 6pm.");
        JsonNode drafts = call(post("/api/messages/" + msgId + "/drafts"), token, 200);
        assertThat(drafts.path("drafts").size()).isEqualTo(3);
        JsonNode lastCompletion = completions.get(completions.size() - 1);
        assertThat(lastCompletion.path("json").asBoolean()).isTrue();
        assertThat(lastCompletion.path("prompt").asText()).contains("6pm");

        // Email → PA task.
        JsonNode task = call(post("/api/messages/" + msgId + "/pa/task").contentType(MediaType.APPLICATION_JSON)
                .content("{\"dueAt\":\"2026-11-07T04:30:00Z\",\"priority\":\"high\"}"), token, 200);
        assertThat(task.path("status").asText()).isEqualTo("done");
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).path("title").asText()).isEqualTo("Batch timing");
        assertThat(tasks.get(0).path("priority").asText()).isEqualTo("HIGH");
        assertThat(tasks.get(0).path("dueAt").asText()).isEqualTo("2026-11-07T04:30:00Z");
        assertThat(tasks.get(0).path("labels").get(0).asText()).isEqualTo("Magizhchi MailLook");
        assertThat(tasks.get(0).path("description").asText()).contains("priya@example.com").contains("6pm");

        // Email → PA calendar event (default calendar picked automatically).
        call(post("/api/messages/" + msgId + "/pa/event").contentType(MediaType.APPLICATION_JSON)
                .content("{\"startAt\":\"2026-11-08T12:30:00Z\",\"durationMinutes\":45}"), token, 200);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).path("calendarId").asText()).isEqualTo("cal-default");
        assertThat(events.get(0).path("endAt").asText()).isEqualTo("2026-11-08T13:15:00Z");

        // Bad input never reaches PA.
        call(post("/api/messages/" + msgId + "/pa/task").contentType(MediaType.APPLICATION_JSON).content("{\"priority\":\"urgent\"}"), token, 400);
        call(post("/api/messages/" + msgId + "/pa/event").contentType(MediaType.APPLICATION_JSON).content("{}"), token, 400);
        assertThat(tasks).hasSize(1);

        // Every PA call after sign-in carried PA's own session, and Mail signed in only once.
        assertThat(calls.stream().filter(c -> c.startsWith("POST /api/v1/auth/sso")).count()).isEqualTo(1);
        assertThat(calls.stream().filter(c -> !c.startsWith("POST /api/v1/auth/sso")).allMatch(c -> c.endsWith("auth=ok"))).isTrue();
    }

    // ── a stand-in Magizhchi PA ───────────────────────────────────

    static HttpServer startFakePa() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            s.createContext("/", PaConnectionTest::handle);
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        boolean authed = "Bearer pa-session-1".equals(ex.getRequestHeaders().getFirst("Authorization"));
        calls.add(method + " " + path + " auth=" + (authed ? "ok" : "none"));
        try {
            if (path.equals("/api/v1/auth/sso")) {
                String t = JSON.readTree(body).path("token").asText();
                ssoClaims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(TestIds.SUITE_SECRET.getBytes(StandardCharsets.UTF_8)))
                        .requireIssuer("magizhchi-id").build().parseSignedClaims(t).getPayload();
                reply(ex, 200, "{\"accessToken\":\"pa-session-1\",\"refreshToken\":\"r\",\"expiresIn\":900,\"user\":{\"id\":\"u1\"}}");
                return;
            }
            if (!authed) {
                reply(ex, 401, "{\"message\":\"Unauthorized\"}");
                return;
            }
            switch (method + " " + path) {
                case "GET /api/v1/assistant/status" -> reply(ex, 200, "{\"enabled\":true}");
                case "POST /api/v1/assistant/complete" -> {
                    JsonNode req = JSON.readTree(body);
                    completions.add(req);
                    String system = req.path("system").asText();
                    String text;
                    if (system.contains("sort email")) {
                        Matcher m = Pattern.compile("- id (\\d+)").matcher(req.path("prompt").asText());
                        StringBuilder items = new StringBuilder();
                        while (m.find()) items.append(items.length() > 0 ? "," : "").append("{\"id\":").append(m.group(1)).append(",\"lane\":\"needs_me\"}");
                        text = "{\"items\":[" + items + "]}";
                    } else if (system.contains("summarise")) {
                        text = "```json\n{\"summary\":\"Priya wants Saturday's batch moved to 6pm.\",\"decision\":null,\"actions\":[{\"who\":\"You\",\"what\":\"Confirm by Friday\"}],\"nextStep\":\"Reply to confirm\"}\n```";
                        text = text.replace("```json\n", "").replace("\n```", ""); // PA strips fences before replying
                    } else {
                        text = "{\"drafts\":[{\"intent\":\"accept\",\"label\":\"Yes, 6pm\",\"body\":\"Yes, 6pm works.\"},"
                                + "{\"intent\":\"decline\",\"label\":\"Not this week\",\"body\":\"Sorry, not this week.\"},"
                                + "{\"intent\":\"ask\",\"label\":\"Which room?\",\"body\":\"Which room?\"}]}";
                    }
                    reply(ex, 200, JSON.writeValueAsString(java.util.Map.of("text", text, "model", "test")));
                }
                case "GET /api/v1/calendars" -> reply(ex, 200, "[{\"id\":\"cal-other\",\"name\":\"Work\",\"isDefault\":false},{\"id\":\"cal-default\",\"name\":\"Personal\",\"isDefault\":true}]");
                case "POST /api/v1/tasks" -> {
                    tasks.add(JSON.readTree(body));
                    reply(ex, 201, "{\"id\":\"task-1\"}");
                }
                case "POST /api/v1/events" -> {
                    events.add(JSON.readTree(body));
                    reply(ex, 201, "{\"id\":\"event-1\"}");
                }
                default -> reply(ex, 404, "{\"message\":\"not found\"}");
            }
        } catch (Exception e) {
            reply(ex, 401, "{\"message\":\"Invalid Magizhchi ID token\"}");
        }
    }

    static void reply(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    // ── helpers ───────────────────────────────────────────────────

    private void deliver(String text) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress("Priya <priya@example.com>"));
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse("tiru@magizhchi.test"));
        m.setSubject("Re: Batch timing");
        m.setText(text);
        m.setSentDate(Date.from(Instant.now()));
        m.saveChanges();
        greenMail.getUserManager().getUserByEmail("tiru@magizhchi.test").deliver(m);
    }

    private long waitForMessage(String token) throws Exception {
        for (int i = 0; i < 100; i++) {
            JsonNode items = call(get("/api/messages"), token, 200).path("items");
            if (items.size() > 0) return items.get(0).path("id").asLong();
            Thread.sleep(100);
        }
        throw new AssertionError("Mail never synced");
    }

    private JsonNode call(MockHttpServletRequestBuilder req, String token, int status) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        var res = mvc.perform(req).andReturn().getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(status);
        String body = res.getContentAsString();
        return body.isEmpty() ? json.createObjectNode() : json.readTree(body);
    }
}
