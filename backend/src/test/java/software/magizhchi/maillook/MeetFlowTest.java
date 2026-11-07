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
 * MailLook ↔ Magizhchi Meet, against a stand-in Meet that checks what MailLook sends the way Meet
 * would: the SSO token must verify with the suite secret, every call must carry Meet's session, and a
 * schedule save must carry the room's own repeat rule, days and zone (Meet resets what it isn't sent).
 * AI comes from a stand-in PA that answers the classroom question.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${MAILLOOK_TEST_DB_URL:jdbc:h2:mem:meet;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${MAILLOOK_TEST_DB_USER:sa}",
        "spring.datasource.password=${MAILLOOK_TEST_DB_PASSWORD:}",
        "maillook.sync.interval-ms=3600000",
        "maillook.ai.api-key=",
        "magizhchi.apps.meet.web=https://meet.test"})
@AutoConfigureMockMvc
class MeetFlowTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("tiru@magizhchi.test", "tiru@magizhchi.test", "pw"));

    static final String ACCOUNT_ID = "2b7c1f0e-5555-4d3a-8b44-000000000005";
    static final String BATCH = "11111111-2222-3333-4444-555555555555";   // admin, weekly, has a zone
    static final String OLD_ROOM = "66666666-2222-3333-4444-555555555555"; // admin, weekly, no zone (older Meet)
    static final String JOINED = "77777777-2222-3333-4444-555555555555";   // only a member
    static final String NEW_ROOM = "88888888-2222-3333-4444-555555555555";
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<String> meetCalls = new CopyOnWriteArrayList<>();
    static final List<JsonNode> created = new CopyOnWriteArrayList<>();
    static final List<JsonNode> schedules = new CopyOnWriteArrayList<>();   // "<cardId>" field added
    static final List<JsonNode> members = new CopyOnWriteArrayList<>();
    static volatile Claims meetSso;
    static final HttpServer meet = start(MeetFlowTest::meet);
    static final HttpServer pa = start(MeetFlowTest::pa);

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
    void startsRoomsMovesBatchesAndLetsStudentsJoin() throws Exception {
        deliver("Priya <priya@example.com>", "Saturday batch timing", "text/plain",
                "Hi sir, three of us have college till 3:30. Can we move the Saturday batch to 4 PM?");
        String token = call(post("/api/auth/sso").contentType(MediaType.APPLICATION_JSON).content(
                "{\"idToken\":\"" + TestIds.idToken(ACCOUNT_ID, "tiru@magizhchi.test", "Tiru") + "\"}"), null, 200).path("token").asText();
        call(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(
                "{\"email\":\"tiru@magizhchi.test\",\"password\":\"pw\",\"imapHost\":\"localhost\",\"imapPort\":3143,\"smtpHost\":\"localhost\",\"smtpPort\":3025}"),
                token, 200);
        long priyaMail = waitForMessage(token, "Saturday batch timing");
        for (int i = 0; i < 80 && !"needs_me".equals(call(get("/api/messages/" + priyaMail), token, 200).path("lane").asText()); i++) Thread.sleep(100);

        // Connected to Meet as the same Magizhchi ID person, with no setup.
        JsonNode status = call(get("/api/meet/status"), token, 200);
        assertThat(status.path("connected").asBoolean()).isTrue();
        assertThat(meetSso.getSubject()).isEqualTo(ACCOUNT_ID);
        assertThat(meetSso.get("email", String.class)).isEqualTo("tiru@magizhchi.test");

        // Only rooms they run, never quick huddles or rooms they merely joined.
        JsonNode rooms = call(get("/api/meet/rooms"), token, 200);
        assertThat(rooms.size()).isEqualTo(2);
        assertThat(rooms.get(0).path("link").asText()).isEqualTo("https://meet.test/room/" + BATCH);
        assertThat(rooms.get(0).path("zone").asText()).isEqualTo("Asia/Kolkata");

        // The AI reads Priya's mail as "move the Saturday batch to 4 PM".
        JsonNode s = call(get("/api/messages/" + priyaMail + "/meet/suggestion"), token, 200);
        assertThat(s.path("kind").asText()).isEqualTo("reschedule");
        assertThat(s.path("room").path("id").asText()).isEqualTo(BATCH);
        assertThat(s.path("newTime").asText()).isEqualTo("16:00");
        assertThat(s.path("personEmail").asText()).isEqualTo("priya@example.com");

        // One tap: same weekly rule, days and zone; same two-hour length; only the time moves.
        JsonNode moved = call(post("/api/messages/" + priyaMail + "/meet/reschedule").contentType(MediaType.APPLICATION_JSON)
                .content("{\"roomId\":\"" + BATCH + "\",\"newTime\":\"16:00\"}"), token, 200);
        assertThat(moved.path("message").asText()).contains("4:00 PM");
        assertThat(moved.path("replyBody").asText()).contains("4:00 PM").contains("6:00 PM");
        JsonNode sched = schedules.get(schedules.size() - 1);
        assertThat(sched.path("cardId").asText()).isEqualTo(BATCH);
        assertThat(sched.path("repeat").asText()).isEqualTo("WEEKLY");
        assertThat(sched.path("days").asText()).isEqualTo("SAT");
        assertThat(sched.path("openTime").asText()).isEqualTo("16:00");
        assertThat(sched.path("lockTime").asText()).isEqualTo("18:00");
        assertThat(sched.path("zone").asText()).isEqualTo("Asia/Kolkata");

        // A room whose zone Meet doesn't report is left alone rather than reset to UTC.
        int before = schedules.size();
        call(post("/api/messages/" + priyaMail + "/meet/reschedule").contentType(MediaType.APPLICATION_JSON)
                .content("{\"roomId\":\"" + OLD_ROOM + "\",\"newTime\":\"16:00\"}"), token, 409);
        call(post("/api/messages/" + priyaMail + "/meet/reschedule").contentType(MediaType.APPLICATION_JSON)
                .content("{\"roomId\":\"" + BATCH + "\",\"newTime\":\"25:00\"}"), token, 400);
        assertThat(schedules).hasSize(before);

        // A room they only joined is not theirs to run: MailLook refuses before Meet is even asked.
        int calls = schedules.size();
        JsonNode refused = call(post("/api/messages/" + priyaMail + "/meet/reschedule").contentType(MediaType.APPLICATION_JSON)
                .content("{\"roomId\":\"" + JOINED + "\",\"newTime\":\"16:00\"}"), token, 403);
        assertThat(refused.path("error").asText()).contains("Only the building owner or an admin of \"Design review\"");
        call(post("/api/messages/" + priyaMail + "/meet/add").contentType(MediaType.APPLICATION_JSON)
                .content("{\"roomId\":\"" + JOINED + "\"}"), token, 403);
        assertThat(schedules).hasSize(calls);
        assertThat(members.stream().map(m -> m.path("email").asText())).doesNotContain("priya@example.com");

        // Meet now, at a set time: room → time → people, guests can use a browser, own mailbox left out.
        String at = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES).toString();
        JsonNode started = call(post("/api/messages/" + priyaMail + "/meet").contentType(MediaType.APPLICATION_JSON)
                .content("{\"emails\":[\"priya@example.com\",\"TIRU@magizhchi.test\",\"arun@example.org\"],\"startAt\":\"" + at + "\",\"minutes\":45}"), token, 200);
        assertThat(started.path("link").asText()).isEqualTo("https://meet.test/room/" + NEW_ROOM);
        assertThat(started.path("name").asText()).isEqualTo("Saturday batch timing");
        assertThat(started.path("invited").size()).isEqualTo(2);
        assertThat(started.path("replyBody").asText()).contains("https://meet.test/room/" + NEW_ROOM).contains("45 min");
        JsonNode card = created.get(created.size() - 1);
        assertThat(card.path("secure").asBoolean()).isFalse();
        assertThat(card.path("ephemeral").asBoolean()).isFalse();
        JsonNode slot = schedules.get(schedules.size() - 1);
        assertThat(slot.path("openAt").asText()).isEqualTo(at);
        assertThat(slot.path("lockAt").asText()).isEqualTo(Instant.parse(at).plus(45, ChronoUnit.MINUTES).toString());
        List<String> order = meetCalls.stream().filter(c -> c.contains(NEW_ROOM) || c.equals("POST /api/v1/cards")).toList();
        assertThat(order.get(0)).isEqualTo("POST /api/v1/cards");
        assertThat(order.get(1)).isEqualTo("PUT /api/v1/cards/" + NEW_ROOM + "/schedule");
        assertThat(order.get(2)).isEqualTo("POST /api/v1/cards/" + NEW_ROOM + "/members");
        assertThat(members.stream().map(m -> m.path("email").asText()).toList()).contains("priya@example.com", "arun@example.org")
                .doesNotContain("tiru@magizhchi.test");

        // The thread now shows its room.
        JsonNode linked = call(get("/api/messages/" + priyaMail + "/meet"), token, 200).path("rooms");
        assertThat(linked.get(0).path("id").asText()).isEqualTo(NEW_ROOM);

        // Meet now, right now: an instant room, no schedule.
        int scheduleCount = schedules.size();
        JsonNode instant = call(post("/api/messages/" + priyaMail + "/meet").contentType(MediaType.APPLICATION_JSON)
                .content("{\"emails\":[\"priya@example.com\"]}"), token, 200);
        assertThat(instant.path("startAt").isNull()).isTrue();
        assertThat(created.get(created.size() - 1).path("ephemeral").asBoolean()).isTrue();
        assertThat(schedules).hasSize(scheduleCount);
        call(post("/api/messages/" + priyaMail + "/meet").contentType(MediaType.APPLICATION_JSON).content("{\"emails\":[]}"), token, 400);

        // An invite mail with a Meet link turns into a join card, even for a room they only joined.
        deliver("Magizhchi Meet <noreply@meet.test>", "Invitation to “Design review” · Magizhchi Meet", "text/html",
                "<p>You're invited.</p><a href=\"https://meet.test/room/" + JOINED + "\">Join the room</a>");
        call(post("/api/sync"), token, 202);
        long invite = waitForMessage(token, "Invitation to “Design review” · Magizhchi Meet");
        JsonNode card2 = call(get("/api/messages/" + invite + "/meet"), token, 200).path("rooms");
        assertThat(card2.size()).isEqualTo(1);
        assertThat(card2.get(0).path("name").asText()).isEqualTo("Design review");
        assertThat(card2.get(0).path("startsAt").asText()).isEqualTo("2026-11-10T09:30:00Z");

        // Leave mail → let the sender attend today's class online.
        members.clear();
        JsonNode added = call(post("/api/messages/" + priyaMail + "/meet/add").contentType(MediaType.APPLICATION_JSON)
                .content("{\"roomId\":\"" + BATCH + "\"}"), token, 200);
        assertThat(members).hasSize(1);
        assertThat(members.get(0).path("email").asText()).isEqualTo("priya@example.com");
        assertThat(members.get(0).path("role").asText()).isEqualTo("MEMBER");
        assertThat(added.path("replyBody").asText()).contains("https://meet.test/room/" + BATCH);

        // Every Meet call after sign-in carried Meet's own session; one sign-in in total.
        assertThat(meetCalls.stream().filter(c -> c.startsWith("POST /api/v1/auth/sso")).count()).isEqualTo(1);
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

    static String room(String id, String name, String role, String repeat, String days, String open, String lock, String zone, boolean ephemeral, String openAt) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"type\":\"CLASS\",\"myRole\":\"" + role + "\",\"ephemeral\":" + ephemeral
                + ",\"locked\":false,\"liveCount\":0,\"scheduleRepeat\":" + q(repeat) + ",\"repeatDays\":" + q(days)
                + ",\"openTime\":" + q(open) + ",\"lockTime\":" + q(lock) + ",\"scheduleZone\":" + q(zone)
                + ",\"scheduledOpenAt\":" + q(openAt) + ",\"scheduledLockAt\":null}";
    }

    static String q(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }

    static void meet(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        meetCalls.add(method + " " + path);
        try {
            if (path.equals("/api/v1/auth/sso")) {
                meetSso = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(TestIds.SUITE_SECRET.getBytes(StandardCharsets.UTF_8)))
                        .requireIssuer("magizhchi-id").build().parseSignedClaims(JSON.readTree(body).path("token").asText()).getPayload();
                reply(ex, 200, "{\"token\":\"meet-session-1\",\"user\":{\"id\":\"u1\"}}");
                return;
            }
        } catch (Exception e) {
            reply(ex, 401, "{\"message\":\"Invalid Magizhchi ID token\"}");
            return;
        }
        if (!"Bearer meet-session-1".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            reply(ex, 401, "{\"message\":\"Unauthorized\"}");
            return;
        }
        String batch = room(BATCH, "Saturday batch J61", "SUPER_ADMIN", "WEEKLY", "SAT", "15:00", "17:00", "Asia/Kolkata", false, null);
        String old = room(OLD_ROOM, "Old weekday batch", "ADMIN", "WEEKDAYS", null, "10:00", "11:00", null, false, null);
        String joined = room(JOINED, "Design review", "MEMBER", null, null, null, null, null, false, "2026-11-10T09:30:00Z");
        String huddle = room("99999999-2222-3333-4444-555555555555", "Quick call", "SUPER_ADMIN", null, null, null, null, null, true, null);
        if (method.equals("GET") && path.equals("/api/v1/auth/me")) { reply(ex, 200, "{\"id\":\"u1\"}"); return; }
        if (method.equals("GET") && path.equals("/api/v1/cards")) { reply(ex, 200, "[" + batch + "," + joined + "," + huddle + "," + old + "]"); return; }
        if (method.equals("POST") && path.equals("/api/v1/cards")) {
            created.add(JSON.readTree(body));
            reply(ex, 200, "{\"id\":\"" + NEW_ROOM + "\",\"name\":\"x\",\"joinCode\":\"ABC123\"}");
            return;
        }
        Matcher m = Pattern.compile("^/api/v1/cards/([0-9a-f-]{36})(/.*)?$").matcher(path);
        if (m.matches()) {
            String id = m.group(1), rest = m.group(2) == null ? "" : m.group(2);
            if (method.equals("GET") && rest.isEmpty()) {
                reply(ex, 200, id.equals(BATCH) ? batch : id.equals(OLD_ROOM) ? old : id.equals(JOINED) ? joined
                        : room(id, "Saturday batch timing", "SUPER_ADMIN", null, null, null, null, null, false, null));
                return;
            }
            if (method.equals("PUT") && rest.equals("/schedule")) {
                var n = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(body);
                n.put("cardId", id);
                schedules.add(n);
                reply(ex, 200, "");
                return;
            }
            if (method.equals("POST") && rest.equals("/members")) {
                members.add(JSON.readTree(body));
                reply(ex, 200, "{\"status\":\"INVITED\",\"email\":\"" + JSON.readTree(body).path("email").asText() + "\"}");
                return;
            }
        }
        reply(ex, 404, "{\"message\":\"not found\"}");
    }

    static void pa(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (path.equals("/api/v1/auth/sso")) { reply(ex, 200, "{\"accessToken\":\"pa-1\",\"expiresIn\":900}"); return; }
        if (path.equals("/api/v1/assistant/status")) { reply(ex, 200, "{\"enabled\":true}"); return; }
        if (path.equals("/api/v1/assistant/complete")) {
            JsonNode req = JSON.readTree(body);
            String system = req.path("system").asText();
            String text;
            if (system.contains("online class rooms")) {
                assertThat(system).contains(BATCH).contains("WEEKLY SAT 15:00-17:00 Asia/Kolkata");
                text = "{\"kind\":\"reschedule\",\"roomId\":\"" + BATCH + "\",\"newTime\":\"16:00\",\"why\":\"Priya asks to start the Saturday batch at 4 PM.\"}";
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

    private void deliver(String from, String subject, String type, String content) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress(from));
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse("tiru@magizhchi.test"));
        m.setSubject(subject, "UTF-8");
        m.setContent(content, type + "; charset=UTF-8");
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
