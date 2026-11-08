package software.magizhchi.maillook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import software.magizhchi.maillook.forms.FormsClient;
import software.magizhchi.maillook.forms.FormsWatch;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Magizhchi Forms in MailLook, against a stand-in Forms: approvals waiting (decided from MailLook,
 * one phone alert each), a form sent from Compose (each person their own copy and signed link, a
 * reminder only to those who haven't filled it), and Forms sending from the mailbox only with consent.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${MAILLOOK_TEST_DB_URL:jdbc:h2:mem:forms;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${MAILLOOK_TEST_DB_USER:sa}",
        "spring.datasource.password=${MAILLOOK_TEST_DB_PASSWORD:}",
        "maillook.sync.interval-ms=3600000",
        "maillook.ai.api-key=",
        "magizhchi.apps.pa.api=",
        "magizhchi.apps.forms.secret=forms-secret-for-tests",
        "maillook.bills.timer=false"})
@AutoConfigureMockMvc
class FormsTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("tiru@magizhchi.test", "tiru@magizhchi.test", "pw"));

    static final String SECRET = "forms-secret-for-tests";
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<String> calls = new CopyOnWriteArrayList<>();
    static final List<String> approvals = new CopyOnWriteArrayList<>(List.of("rsp1"));
    static final Set<String> filledBy = Collections.synchronizedSet(new HashSet<>());
    static final HttpServer fake = startFakeForms();

    @DynamicPropertySource
    static void formsUrl(DynamicPropertyRegistry r) {
        r.add("magizhchi.apps.forms.api", () -> "http://localhost:" + fake.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        fake.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FormsWatch watch;
    @Autowired FormsClient forms;

    @Test
    void approvalsResponsesAndFormsSentFromCompose() throws Exception {
        String token = call(post("/api/auth/sso").contentType(MediaType.APPLICATION_JSON).content(
                "{\"idToken\":\"" + TestIds.idToken("2b7c1f0e-4444-4d3a-8b44-000000000041", "owner@magizhchi.test", "Owner") + "\"}"), null, 200).path("token").asText();
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'owner@magizhchi.test'", Long.class);
        long accountId = call(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(
                "{\"email\":\"tiru@magizhchi.test\",\"password\":\"pw\",\"imapHost\":\"localhost\",\"imapPort\":3143,\"smtpHost\":\"localhost\",\"smtpPort\":3025}"),
                token, 200).path("id").asLong();

        // What's waiting
        JsonNode over = call(get("/api/forms/overview"), token, 200);
        assertThat(over.path("approvals").get(0).path("who").asText()).isEqualTo("Priya");
        assertThat(calls).anyMatch(c -> c.startsWith("GET /overview") && c.contains("email=owner%40magizhchi.test"));
        assertThat(call(get("/api/forms/count"), token, 200).path("approvals").asInt()).isEqualTo(1);

        // The first look only notes what was already waiting; a new approval rings once.
        Instant now = Instant.now();
        jdbc.update("UPDATE users SET forms_checked_at = NULL WHERE id = ?", userId);
        watch.run(now);
        approvals.add("rsp2");
        jdbc.update("UPDATE users SET forms_checked_at = NULL WHERE id = ?", userId);
        watch.run(now);
        jdbc.update("UPDATE users SET forms_checked_at = NULL WHERE id = ?", userId);
        watch.run(now);
        assertThat(jdbc.queryForList("SELECT title FROM alerts WHERE user_id = ? AND tag LIKE 'maillook-forms-approval-%'", String.class, userId))
                .containsExactly("Approval waiting: Admission form");

        JsonNode decided = call(post("/api/forms/decision").contentType(MediaType.APPLICATION_JSON)
                .content("{\"responseId\":\"rsp2\",\"decision\":\"approve\",\"comment\":\"Welcome\"}"), token, 200);
        assertThat(decided.path("status").asText()).isEqualTo("approved");
        assertThat(calls).anyMatch(c -> c.startsWith("POST /decision") && c.contains("\"email\":\"owner@magizhchi.test\"") && c.contains("Welcome"));
        call(post("/api/forms/decision").contentType(MediaType.APPLICATION_JSON).content("{\"responseId\":\"rsp2\",\"decision\":\"delete\"}"), token, 400);

        // A form sent to two people: two copies, each with its own signed link and an HTML card.
        String payload = "{\"accountId\":" + accountId + ",\"to\":[\"Priya <priya@example.com>\"],\"cc\":[\"ravi@example.com\"],"
                + "\"subject\":\"Admission for next year\",\"text\":\"Please fill this in by Friday.\",\"form\":{\"formId\":\"frm1\",\"kind\":\"form\","
                + "\"title\":\"Admission form\",\"url\":\"http://localhost:5173/f/adm123\",\"emailKey\":\"email\"}}";
        call(multipart("/api/send").file(new MockMultipartFile("payload", "", "application/json", payload.getBytes())), token, 200);
        List<MimeMessage> out = delivered().filter(m -> subject(m).equals("Admission for next year")).toList();
        assertThat(out).hasSize(2);
        for (MimeMessage m : out) {
            String to = m.getRecipients(jakarta.mail.Message.RecipientType.TO)[0].toString();
            String email = to.contains("priya") ? "priya@example.com" : "ravi@example.com";
            assertThat(m.getRecipients(jakarta.mail.Message.RecipientType.CC)).isNull();
            String raw = GreenMailUtil.getWholeMessage(m).replace("=\r\n", "").replace("=3D", "=");
            assertThat(raw).contains("Fill in the form");
            assertThat(raw).contains("email=" + email.replace("@", "%40"));
            assertThat(raw).contains("ml=" + java.net.URLEncoder.encode(forms.personToken("frm1", email), StandardCharsets.UTF_8));
        }
        long sendId = jdbc.queryForObject("SELECT id FROM form_sends WHERE user_id = ?", Long.class, userId);

        filledBy.add("priya@example.com");
        JsonNode sent = call(get("/api/forms/sent/" + sendId), token, 200);
        assertThat(sent.path("filled").asInt()).isEqualTo(1);
        assertThat(sent.path("asked").asInt()).isEqualTo(2);
        long sentCopy = sent.path("messageId").asLong();
        assertThat(call(get("/api/forms/for-message/" + sentCopy), token, 200).path("found").asBoolean()).isTrue();

        assertThat(call(post("/api/forms/sent/" + sendId + "/remind"), token, 200).path("reminded").asInt()).isEqualTo(1);
        List<MimeMessage> reminders = delivered().filter(m -> subject(m).equals("Reminder: Admission for next year")).toList();
        assertThat(reminders).hasSize(1);
        assertThat(reminders.get(0).getRecipients(jakarta.mail.Message.RecipientType.TO)[0].toString()).isEqualTo("ravi@example.com");
        call(post("/api/forms/sent/" + sendId + "/remind"), token, 429);

        // Forms sending from the mailbox: refused until the person says yes, then from their mailbox.
        String req = "{\"owner\":\"owner@magizhchi.test\",\"to\":\"parent@example.com\",\"subject\":\"Your admission\",\"text\":\"We got it.\",\"formTitle\":\"Admission form\"}";
        assertThat(mvc.perform(post("/api/integration/forms/send").contentType(MediaType.APPLICATION_JSON).content(req)).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(post("/api/integration/forms/send").header("X-Integration-Secret", SECRET).contentType(MediaType.APPLICATION_JSON).content(req))
                .andReturn().getResponse().getStatus()).isEqualTo(409);
        call(put("/api/forms/settings").contentType(MediaType.APPLICATION_JSON).content("{\"sendAccountId\":" + accountId + ",\"responses\":\"daily\"}"), token, 200);
        assertThat(mvc.perform(post("/api/integration/forms/send").header("X-Integration-Secret", SECRET).contentType(MediaType.APPLICATION_JSON).content(req))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        MimeMessage viaMailbox = delivered().filter(m -> subject(m).equals("Your admission")).findFirst().orElseThrow();
        assertThat(viaMailbox.getFrom()[0].toString()).contains("tiru@magizhchi.test");
        assertThat(GreenMailUtil.getBody(viaMailbox)).contains("Sent through Magizhchi Forms");
        call(put("/api/forms/settings").contentType(MediaType.APPLICATION_JSON).content("{\"sendFromMailbox\":false}"), token, 200);
        assertThat(mvc.perform(post("/api/integration/forms/send").header("X-Integration-Secret", SECRET).contentType(MediaType.APPLICATION_JSON).content(req))
                .andReturn().getResponse().getStatus()).isEqualTo(409);

        // The briefing line
        String line = watch.briefingLine(userId, "owner@magizhchi.test", "Asia/Kolkata", Instant.now());
        assertThat(line).startsWith("Forms: 3 new responses · 1 approval waiting · Admission form closes");
    }

    @Test
    void briefingLineSaysNothingWhenNothingWaits() {
        assertThat(FormsWatchLine.line(0, 0)).isNull();
        assertThat(FormsWatchLine.line(1, 2)).isEqualTo("Forms: 1 new response · 2 approvals waiting");
    }

    /** FormsWatch.line is package-private; this reaches it through counts. */
    static final class FormsWatchLine {
        static String line(int fresh, int waiting) {
            try {
                var m = FormsWatch.class.getDeclaredMethod("line", FormsWatch.Counts.class);
                m.setAccessible(true);
                return (String) m.invoke(null, new FormsWatch.Counts(waiting, fresh, List.of(), Instant.now()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ── the stand-in Forms ────────────────────────────────────────

    static HttpServer startFakeForms() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            s.createContext("/api/v1/integration/maillook/", FormsTest::handle);
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath().replace("/api/v1/integration/maillook", "");
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        calls.add(ex.getRequestMethod() + " " + path + "?" + ex.getRequestURI().getRawQuery() + " " + body);
        if (!SECRET.equals(ex.getRequestHeaders().getFirst("X-Integration-Secret"))) {
            send(ex, 403, "{\"error\":\"Wrong integration secret.\"}");
            return;
        }
        ObjectNode out = JSON.createObjectNode();
        switch (path) {
            case "/overview" -> {
                ArrayNode fs = out.putArray("forms");
                fs.addObject().put("id", "frm1").put("title", "Admission form").put("status", "published").put("responses", 40).put("newResponses", 3)
                        .put("closesAt", Instant.now().plusSeconds(3 * 86400).toString());
                ArrayNode aps = out.putArray("approvals");
                for (String id : approvals) {
                    ObjectNode a = aps.addObject().put("responseId", id).put("formId", "frm1").put("formTitle", "Admission form")
                            .put("who", "Priya").put("whoEmail", "priya@example.com").put("submittedAt", Instant.now().toString());
                    a.putArray("answers").addObject().put("label", "Class").put("text", "Grade 5");
                }
                out.putArray("responses");
            }
            case "/decision" -> {
                JsonNode d = JSON.readTree(body);
                approvals.remove(d.path("responseId").asText());
                out.put("responseId", d.path("responseId").asText()).put("status", "approve".equals(d.path("decision").asText()) ? "approved" : "rejected");
            }
            case "/filled" -> {
                ArrayNode f = out.putArray("filled");
                for (JsonNode e : JSON.readTree(body).path("emails"))
                    if (filledBy.contains(e.asText())) f.addObject().put("email", e.asText()).put("status", "submitted").put("at", Instant.now().toString());
            }
            default -> {
                send(ex, 404, "{\"error\":\"Not found.\"}");
                return;
            }
        }
        send(ex, 200, out.toString());
    }

    static void send(HttpExchange ex, int code, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    /** What reached people: the Sent copy has the same Message-ID, so each mail once. */
    static java.util.stream.Stream<MimeMessage> delivered() {
        Map<String, MimeMessage> once = new LinkedHashMap<>();
        for (MimeMessage m : greenMail.getReceivedMessages()) {
            try {
                once.putIfAbsent(m.getMessageID(), m);
            } catch (Exception ignored) {
                // skip it
            }
        }
        return once.values().stream();
    }

    static String subject(MimeMessage m) {
        try {
            return m.getSubject() == null ? "" : m.getSubject();
        } catch (Exception e) {
            return "";
        }
    }

    private JsonNode call(MockHttpServletRequestBuilder req, String token, int status) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        var res = mvc.perform(req).andReturn().getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(status);
        String s = res.getContentAsString();
        return s.isBlank() ? JSON.createObjectNode() : JSON.readTree(s);
    }

    static ZoneId india() {
        return ZoneId.of("Asia/Kolkata");
    }
}
