package software.magizhchi.maillook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * After a meeting started from an email: Meet says the room emptied, MailLook reads that meeting
 * back (who came, who missed it, what was said), the AI writes the recap, the action items land on
 * the PA board, and the thread returns to Needs me with the recap and catch-up mail ready. A stand-in
 * Meet checks the notice is suite-signed and that MailLook asks only for that one meeting's words.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${MAILLOOK_TEST_DB_URL:jdbc:h2:mem:recap;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${MAILLOOK_TEST_DB_USER:sa}",
        "spring.datasource.password=${MAILLOOK_TEST_DB_PASSWORD:}",
        "maillook.sync.interval-ms=3600000",
        "maillook.meet.recap-sweep-ms=3600000",
        "maillook.ai.api-key=",
        "magizhchi.apps.meet.web=https://meet.test"})
@AutoConfigureMockMvc
class MeetRecapTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("tiru@magizhchi.test", "tiru@magizhchi.test", "pw"));

    static final String ACCOUNT_ID = "2b7c1f0e-5555-4d3a-8b44-000000000006";
    static final String ROOM = "88888888-2222-3333-4444-555555555555";
    static final Instant START = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
    static final Instant END = START.plus(32, ChronoUnit.MINUTES);
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<String> transcriptAsks = new CopyOnWriteArrayList<>();
    static final List<JsonNode> tasks = new CopyOnWriteArrayList<>();
    static final List<String> aiPrompts = new CopyOnWriteArrayList<>();
    static final HttpServer meet = start(MeetRecapTest::meet);
    static final HttpServer pa = start(MeetRecapTest::pa);

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry r) {
        r.add("magizhchi.apps.meet.api", () -> "http://localhost:" + meet.getAddress().getPort());
        r.add("magizhchi.apps.pa.api", () -> "http://localhost:" + pa.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        meet.stop(0);
        pa.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void recapsAMeetingStartedFromMail() throws Exception {
        deliver("Priya <priya@example.com>", "Saturday batch timing", "Can we talk about the Saturday batch?");
        String token = call(post("/api/auth/sso").contentType(MediaType.APPLICATION_JSON).content(
                "{\"idToken\":\"" + TestIds.idToken(ACCOUNT_ID, "tiru@magizhchi.test", "Tiru") + "\"}"), null, 200).path("token").asText();
        call(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(
                "{\"email\":\"tiru@magizhchi.test\",\"password\":\"pw\",\"imapHost\":\"localhost\",\"imapPort\":3143,\"smtpHost\":\"localhost\",\"smtpPort\":3025}"),
                token, 200);
        long mail = waitForMessage(token, "Saturday batch timing");
        for (int i = 0; i < 80 && !"fyi".equals(call(get("/api/messages/" + mail), token, 200).path("lane").asText()); i++) Thread.sleep(100);

        call(post("/api/messages/" + mail + "/meet").contentType(MediaType.APPLICATION_JSON)
                .content("{\"emails\":[\"priya@example.com\",\"arun@example.org\"]}"), token, 200);

        // Only Meet can say a meeting ended: an unsigned or wrongly signed notice is refused.
        call(post("/api/hooks/meet"), null, 401);
        call(post("/api/hooks/meet").header("Authorization", "Bearer " + notice(ROOM, "magizhchi-id")), null, 401);
        call(post("/api/hooks/meet").header("Authorization", "Bearer " + notice(ROOM, "magizhchi-meet")), null, 202);

        JsonNode meetings = null;
        for (int i = 0; i < 100; i++) {
            meetings = call(get("/api/messages/" + mail + "/meet"), token, 200).path("meetings");
            if (meetings.size() > 0 && meetings.get(0).path("tasksAdded").asInt() > 0) break;
            Thread.sleep(100);
        }
        assertThat(meetings).hasSize(1);
        JsonNode m = meetings.get(0);
        assertThat(m.path("minutes").asInt()).isEqualTo(32);
        assertThat(m.path("startedAt").asText()).isEqualTo(START.toString());
        assertThat(m.path("summary").asText()).isEqualTo("The Saturday batch moves to 4 PM from next week.");
        assertThat(m.path("decisions").get(0).asText()).isEqualTo("Saturday batch starts at 4 PM");
        assertThat(m.path("attendeeEmails").toString()).isEqualTo("[\"priya@example.com\"]");
        assertThat(m.path("missedEmails").toString()).isEqualTo("[\"arun@example.org\"]");
        assertThat(m.path("recapBody").asText()).startsWith("Thanks for joining \"Saturday batch timing\"")
                .contains("(32 min)").contains("Decided\n- Saturday batch starts at 4 PM").contains("- Priya: Share the notes")
                .contains("Attended: Tiru, Priya");
        assertThat(m.path("catchUpBody").asText()).startsWith("Sorry we missed you at \"Saturday batch timing\"")
                .contains("The Saturday batch moves to 4 PM");

        // MailLook asked for that meeting's words only, and the AI got them with the names of who came.
        assertThat(transcriptAsks).anyMatch(q -> q.contains("from=" + START.toString().replace(":", "%3A")) && q.contains("to=" + END.toString().replace(":", "%3A")));
        assertThat(aiPrompts).anyMatch(p -> p.contains("Attended: Tiru, Priya") && p.contains("Priya: Can we start at four?"));

        // Action items are on the PA board, one each, the other person's name in the title.
        assertThat(m.path("tasksAdded").asInt()).isEqualTo(2);
        assertThat(tasks).extracting(t -> t.path("title").asText())
                .containsExactlyInAnyOrder("Send the new timetable", "Priya: Share the notes");
        assertThat(tasks).filteredOn(t -> t.path("title").asText().startsWith("Send")).first()
                .satisfies(t -> assertThat(t.path("dueAt").asText()).startsWith("2026-11-09T12:30"));

        // The thread is back in Needs me, where the recap waits.
        JsonNode detail = call(get("/api/messages/" + mail), token, 200);
        assertThat(detail.path("lane").asText()).isEqualTo("needs_me");
        assertThat(detail.path("laneSource").asText()).isEqualTo("meet");

        // A repeated notice changes nothing: one meeting, no duplicate tasks.
        call(post("/api/hooks/meet").header("Authorization", "Bearer " + notice(ROOM, "magizhchi-meet")), null, 202);
        Thread.sleep(1500);
        assertThat(call(get("/api/messages/" + mail + "/meet"), token, 200).path("meetings")).hasSize(1);
        assertThat(tasks).hasSize(2);
    }

    static String notice(String card, String issuer) {
        return Jwts.builder().issuer(issuer).audience().add("magizhchi-maillook").and().subject(card)
                .claim("endedAt", END.toString()).issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(TestIds.SUITE_SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    // ── stand-ins ────────────────────────────────────────────────

    static HttpServer start(com.sun.net.httpserver.HttpHandler h) {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            s.createContext("/", h);
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void meet(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (path.equals("/api/v1/auth/sso")) { reply(ex, 200, "{\"token\":\"meet-session\"}"); return; }
        if (!"Bearer meet-session".equals(ex.getRequestHeaders().getFirst("Authorization"))) { reply(ex, 401, "{}"); return; }
        if (method.equals("POST") && path.equals("/api/v1/cards")) { reply(ex, 200, "{\"id\":\"" + ROOM + "\"}"); return; }
        if (path.equals("/api/v1/cards/" + ROOM + "/members")) { reply(ex, 200, "{\"status\":\"INVITED\"}"); return; }
        if (path.equals("/api/v1/cards/" + ROOM + "/meetings")) {
            String tiru = "{\"userId\":\"u1\",\"name\":\"Tiru\",\"email\":\"tiru@magizhchi.test\",\"minutes\":32}";
            String priya = "{\"userId\":\"u2\",\"name\":\"Priya\",\"email\":\"priya@example.com\",\"minutes\":30}";
            String arun = "{\"userId\":\"u3\",\"name\":\"Arun\",\"email\":\"arun@example.org\",\"minutes\":0}";
            reply(ex, 200, "[{\"startedAt\":\"" + START + "\",\"endedAt\":\"" + END + "\",\"ended\":true,\"minutes\":32,"
                    + "\"attended\":[" + tiru + "," + priya + "],\"missed\":[" + arun + "]},"
                    + "{\"startedAt\":\"" + Instant.now().minusSeconds(120) + "\",\"endedAt\":null,\"ended\":false,\"minutes\":2,"
                    + "\"attended\":[" + tiru + "," + priya + "],\"missed\":[]}]");
            return;
        }
        if (path.equals("/api/v1/cards/" + ROOM + "/meetings/transcript")) {
            transcriptAsks.add(ex.getRequestURI().getRawQuery());
            reply(ex, 200, "[{\"speaker\":\"Priya\",\"text\":\"Can we start at four?\",\"at\":\"" + START.plusSeconds(60) + "\"}]");
            return;
        }
        reply(ex, 404, "{}");
    }

    static void pa(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (path.equals("/api/v1/auth/sso")) { reply(ex, 200, "{\"accessToken\":\"pa-1\",\"expiresIn\":900}"); return; }
        if (path.equals("/api/v1/assistant/status")) { reply(ex, 200, "{\"enabled\":true}"); return; }
        if (path.equals("/api/v1/tasks")) {
            tasks.add(JSON.readTree(body));
            reply(ex, 200, "{\"id\":\"t" + tasks.size() + "\"}");
            return;
        }
        if (path.equals("/api/v1/assistant/complete")) {
            JsonNode req = JSON.readTree(body);
            String text;
            if (req.path("system").asText().contains("recap of an online meeting")) {
                aiPrompts.add(req.path("prompt").asText());
                text = "{\"summary\":\"The Saturday batch moves to 4 PM from next week.\",\"decisions\":[\"Saturday batch starts at 4 PM\"],"
                        + "\"actions\":[{\"owner\":\"You\",\"task\":\"Send the new timetable\",\"due\":\"2026-11-09\"},"
                        + "{\"owner\":\"Priya\",\"task\":\"Share the notes\",\"due\":null},{\"owner\":\"Priya\",\"task\":\"  \"}]}";
            } else {
                Matcher ids = Pattern.compile("- id (\\d+)").matcher(req.path("prompt").asText());
                StringBuilder items = new StringBuilder();
                while (ids.find()) items.append(items.length() > 0 ? "," : "").append("{\"id\":").append(ids.group(1)).append(",\"lane\":\"fyi\"}");
                text = "{\"items\":[" + items + "]}";
            }
            reply(ex, 200, JSON.writeValueAsString(Map.of("text", text)));
            return;
        }
        reply(ex, 404, "{}");
    }

    static void reply(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) ex.getResponseBody().write(b);
        ex.close();
    }

    // ── helpers ───────────────────────────────────────────────────

    private void deliver(String from, String subject, String text) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress(from));
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse("tiru@magizhchi.test"));
        m.setSubject(subject, "UTF-8");
        m.setText(text, "UTF-8");
        m.setSentDate(Date.from(Instant.now()));
        m.saveChanges();
        greenMail.getUserManager().getUserByEmail("tiru@magizhchi.test").deliver(m);
    }

    private long waitForMessage(String token, String subject) throws Exception {
        for (int i = 0; i < 100; i++) {
            for (JsonNode it : call(get("/api/messages"), token, 200).path("items")) {
                if (subject.equals(it.path("subject").asText())) return it.path("id").asLong();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Mail never synced: " + subject);
    }

    private JsonNode call(MockHttpServletRequestBuilder req, String token, int status) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        var res = mvc.perform(req).andReturn().getResponse();
        String body = res.getContentAsString(StandardCharsets.UTF_8);
        assertThat(res.getStatus()).as(body).isEqualTo(status);
        return body.isEmpty() ? json.createObjectNode() : json.readTree(body);
    }
}
