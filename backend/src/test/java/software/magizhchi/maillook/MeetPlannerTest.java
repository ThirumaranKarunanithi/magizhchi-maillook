package software.magizhchi.maillook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
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
 * Before the meeting: "can we talk?" gets three free times from the PA calendar, the offer is
 * remembered, the answer "the second one works" becomes a one-tap booking, a meeting about to start
 * puts its mail at the top of Needs me, and the briefing's open questions go into the room as the agenda.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${MAILLOOK_TEST_DB_URL:jdbc:h2:mem:planner;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${MAILLOOK_TEST_DB_USER:sa}",
        "spring.datasource.password=${MAILLOOK_TEST_DB_PASSWORD:}",
        "maillook.sync.interval-ms=3600000",
        "maillook.meet.recap-sweep-ms=3600000",
        "maillook.ai.api-key=",
        "magizhchi.apps.meet.web=https://meet.test"})
@AutoConfigureMockMvc
class MeetPlannerTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("tiru@magizhchi.test", "tiru@magizhchi.test", "pw"));

    static final String ACCOUNT_ID = "2b7c1f0e-5555-4d3a-8b44-000000000007";
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final Instant NOW = Instant.now();
    /** The whole of the next day after the earliest bookable time is taken in the calendar. */
    static final Instant BUSY_FROM = NOW.plus(2, ChronoUnit.HOURS);
    static final Instant BUSY_TO = NOW.plus(26, ChronoUnit.HOURS);
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<String> roomsMade = new CopyOnWriteArrayList<>();
    static final List<JsonNode> chatPosts = new CopyOnWriteArrayList<>();
    static final List<String> eventQueries = new CopyOnWriteArrayList<>();
    static final HttpServer meet = start(MeetPlannerTest::meet);
    static final HttpServer pa = start(MeetPlannerTest::pa);

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
    void findsATimeBooksTheOnePickedAndPreparesTheMeeting() throws Exception {
        deliver("Can we talk about the placement drive?", "Hi sir, can we get on a call this week about the drive?", "<ask@example.com>", null);
        String token = call(post("/api/auth/sso").contentType(MediaType.APPLICATION_JSON).content(
                "{\"idToken\":\"" + TestIds.idToken(ACCOUNT_ID, "tiru@magizhchi.test", "Tiru") + "\"}"), null, 200).path("token").asText();
        call(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(
                "{\"email\":\"tiru@magizhchi.test\",\"password\":\"pw\",\"imapHost\":\"localhost\",\"imapPort\":3143,\"smtpHost\":\"localhost\",\"smtpPort\":3025}"),
                token, 200);
        long ask = waitForMessage(token, "Can we talk about the placement drive?");
        waitForLane(token, ask);

        // Three free times, none inside the busy day, all in working hours, never on a Sunday.
        JsonNode t = call(get("/api/messages/" + ask + "/meet/times"), token, 200);
        assertThat(t.path("kind").asText()).isEqualTo("request");
        assertThat(t.path("minutes").asInt()).isEqualTo(45);
        assertThat(t.path("topic").asText()).isEqualTo("Placement drive plan");
        assertThat(t.path("calendarChecked").asBoolean()).isTrue();
        assertThat(t.path("slots")).hasSize(3);
        for (JsonNode s : t.path("slots")) {
            Instant start = Instant.parse(s.path("start").asText());
            var local = start.atZone(IST);
            assertThat(start).isAfterOrEqualTo(BUSY_TO.truncatedTo(ChronoUnit.MINUTES).minusSeconds(60));
            assertThat(local.getDayOfWeek()).isNotEqualTo(DayOfWeek.SUNDAY);
            assertThat(local.toLocalTime()).isBetween(LocalTime.of(10, 0), LocalTime.of(17, 15));
        }
        assertThat(eventQueries).isNotEmpty();

        // Offer the first two; the reply proposes them in India time.
        String first = t.path("slots").get(0).path("start").asText();
        String second = t.path("slots").get(1).path("start").asText();
        JsonNode offered = call(post("/api/messages/" + ask + "/meet/offer").contentType(MediaType.APPLICATION_JSON)
                .content("{\"slots\":[\"" + first + "\",\"" + second + "\"],\"minutes\":45,\"topic\":\"Placement drive plan\"}"), token, 200);
        assertThat(offered.path("replyBody").asText()).contains("Do any of these work").contains(t.path("slots").get(1).path("label").asText()).contains("IST");

        // "The second one works" → that exact time, ready to book.
        deliver("Re: Can we talk about the placement drive?", "The second one works for me.", "<pick@example.com>", "<ask@example.com>");
        call(post("/api/sync"), token, 202);
        long pick = waitForMessage(token, "Re: Can we talk about the placement drive?");
        waitForLane(token, pick);
        JsonNode p = call(get("/api/messages/" + pick + "/meet/times"), token, 200);
        assertThat(p.path("kind").asText()).isEqualTo("picked");
        assertThat(p.path("slot").path("start").asText()).isEqualTo(second);
        assertThat(p.path("minutes").asInt()).isEqualTo(45);

        // Book it: one room at that time; the offer is settled and the card goes away.
        JsonNode booked = call(post("/api/messages/" + pick + "/meet").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Placement drive plan\",\"emails\":[\"priya@example.com\"],\"startAt\":\"" + second + "\",\"minutes\":45}"), token, 200);
        assertThat(booked.path("startAt").asText()).isEqualTo(second);
        assertThat(call(get("/api/messages/" + pick + "/meet/times"), token, 200).path("kind").asText()).isEqualTo("none");

        // A meeting in ten minutes puts the thread's latest mail first in Needs me, with its start time.
        String soon = Instant.now().plus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS).toString();
        deliver("Quick question on fees", "Can we sort the fee receipts?", "<fees@example.com>", null);
        call(post("/api/sync"), token, 202);
        long fees = waitForMessage(token, "Quick question on fees");
        call(post("/api/messages/" + ask + "/meet").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Drive prep\",\"emails\":[\"priya@example.com\"],\"startAt\":\"" + soon + "\",\"minutes\":30}"), token, 200);
        JsonNode list = call(get("/api/messages?lane=needs_me"), token, 200).path("items");
        assertThat(list.get(0).path("id").asLong()).isEqualTo(pick);
        assertThat(list.get(0).path("meetingStartsAt").asText()).isEqualTo(soon);
        assertThat(list.findValues("id").stream().filter(n -> n.asLong() == pick).count()).isEqualTo(1);
        assertThat(list.findValues("id").stream().anyMatch(n -> n.asLong() == fees)).isTrue();
        // Searching never pins.
        assertThat(call(get("/api/messages?lane=needs_me&q=fees"), token, 200).path("items").get(0).has("meetingStartsAt")).isFalse();

        // Briefing: the thread in short and what's still open.
        JsonNode b = call(get("/api/messages/" + pick + "/meet/briefing"), token, 200);
        assertThat(b.path("summary").asText()).isEqualTo("Priya wants to plan the placement drive.");
        assertThat(b.path("questions").get(0).asText()).isEqualTo("How many seats for the drive?");
        assertThat(b.path("lastMeeting").isNull()).isTrue();

        // Agenda into the room's chat.
        String room = roomsMade.get(0);
        JsonNode posted = call(post("/api/messages/" + pick + "/meet/agenda").contentType(MediaType.APPLICATION_JSON)
                .content("{\"roomId\":\"" + room + "\",\"items\":[\"How many seats for the drive?\",\"  \",\"Aptitude test date\"]}"), token, 200);
        assertThat(chatPosts).hasSize(1);
        assertThat(chatPosts.get(0).path("text").asText())
                .isEqualTo("Agenda (from \"Can we talk about the placement drive?\")\n1. How many seats for the drive?\n2. Aptitude test date");
        assertThat(posted.path("message").asText()).contains("posted");
        call(post("/api/messages/" + pick + "/meet/agenda").contentType(MediaType.APPLICATION_JSON)
                .content("{\"roomId\":\"not-a-room\",\"items\":[\"x\"]}"), token, 400);
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
        if (method.equals("POST") && path.equals("/api/v1/cards")) {
            String id = String.format("%08d-2222-3333-4444-555555555555", roomsMade.size() + 1);
            roomsMade.add(id);
            reply(ex, 200, "{\"id\":\"" + id + "\"}");
            return;
        }
        if (path.endsWith("/schedule")) { reply(ex, 200, ""); return; }
        if (path.endsWith("/members")) { reply(ex, 200, "{\"status\":\"INVITED\"}"); return; }
        if (method.equals("POST") && path.endsWith("/chat")) {
            chatPosts.add(JSON.readTree(body));
            reply(ex, 200, "{\"id\":\"c1\"}");
            return;
        }
        reply(ex, 404, "{}");
    }

    static void pa(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (path.equals("/api/v1/auth/sso")) { reply(ex, 200, "{\"accessToken\":\"pa-1\",\"expiresIn\":900}"); return; }
        if (path.equals("/api/v1/assistant/status")) { reply(ex, 200, "{\"enabled\":true}"); return; }
        if (path.equals("/api/v1/events")) {
            eventQueries.add(ex.getRequestURI().getRawQuery());
            reply(ex, 200, "[{\"title\":\"Busy day\",\"occurrenceStart\":\"" + BUSY_FROM + "\",\"occurrenceEnd\":\"" + BUSY_TO + "\",\"allDay\":false,\"status\":\"CONFIRMED\"},"
                    + "{\"title\":\"Cancelled\",\"occurrenceStart\":\"" + BUSY_TO + "\",\"occurrenceEnd\":\"" + BUSY_TO.plus(30, ChronoUnit.DAYS) + "\",\"allDay\":false,\"status\":\"CANCELLED\"}]");
            return;
        }
        if (path.equals("/api/v1/assistant/complete")) {
            JsonNode req = JSON.readTree(body);
            String system = req.path("system").asText();
            String text;
            if (system.contains("offered these meeting times")) {
                text = "{\"kind\":\"picked\",\"pick\":2,\"why\":\"Priya chose the second time.\"}";
            } else if (system.contains("asks the mailbox owner")) {
                text = "{\"kind\":\"request\",\"minutes\":40,\"topic\":\"Placement drive plan\",\"why\":\"Priya asks for a call.\"}";
            } else if (system.contains("prepare the mailbox owner")) {
                text = "{\"summary\":\"Priya wants to plan the placement drive.\",\"questions\":[\"How many seats for the drive?\",\"Aptitude test date\"]}";
            } else {
                Matcher ids = Pattern.compile("- id (\\d+)").matcher(req.path("prompt").asText());
                StringBuilder items = new StringBuilder();
                while (ids.find()) items.append(items.length() > 0 ? "," : "").append("{\"id\":").append(ids.group(1)).append(",\"lane\":\"needs_me\"}");
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

    private void deliver(String subject, String text, String messageId, String inReplyTo) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties())) {
            @Override
            protected void updateMessageID() throws jakarta.mail.MessagingException {
                setHeader("Message-ID", messageId);
            }
        };
        m.setFrom(new InternetAddress("Priya <priya@example.com>"));
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse("tiru@magizhchi.test"));
        m.setSubject(subject, "UTF-8");
        m.setText(text, "UTF-8");
        if (inReplyTo != null) {
            m.setHeader("In-Reply-To", inReplyTo);
            m.setHeader("References", inReplyTo);
        }
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

    private void waitForLane(String token, long id) throws Exception {
        for (int i = 0; i < 80; i++) {
            JsonNode m = call(get("/api/messages/" + id), token, 200);
            if ("ai".equals(m.path("laneSource").asText()) && "needs_me".equals(m.path("lane").asText())) return;
            Thread.sleep(100);
        }
    }

    private JsonNode call(MockHttpServletRequestBuilder req, String token, int status) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        var res = mvc.perform(req).andReturn().getResponse();
        String body = res.getContentAsString(StandardCharsets.UTF_8);
        assertThat(res.getStatus()).as(body).isEqualTo(status);
        return body.isEmpty() ? json.createObjectNode() : json.readTree(body);
    }
}
