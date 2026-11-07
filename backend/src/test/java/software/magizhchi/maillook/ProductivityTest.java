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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import software.magizhchi.maillook.notify.Reminders;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 2: snooze, "remind me if nobody replies", notes (and copying them to PA), the PA calendar,
 * contacts from mail and from the Magizhchi Box book, and browser alert settings. One stand-in server
 * plays both Magizhchi Box and Magizhchi PA.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:productivity;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "maillook.sync.interval-ms=3600000",
        "maillook.ai.api-key=",
        "magizhchi.apps.box.provision-secret=box-secret-for-tests"})
@AutoConfigureMockMvc
class ProductivityTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("tiru@magizhchi.test", "tiru@magizhchi.test", "pw"));

    static final ObjectMapper JSON = new ObjectMapper();
    static final String KEY = "mbk_test_contacts";
    /** Box files in Synced/Mobile/Contacts/: id → text. */
    static final Map<Long, String> files = new ConcurrentHashMap<>();
    static final List<String> uploads = new CopyOnWriteArrayList<>();
    static final List<JsonNode> paNotes = new CopyOnWriteArrayList<>();
    static final List<String> paCalls = new CopyOnWriteArrayList<>();
    static final HttpServer fake = start();

    static {
        // A phone backup, then a card edited in Box that merged two Ravis and deleted Old Friend.
        files.put(1L, """
                BEGIN:VCARD\r
                VERSION:2.1\r
                FN:Priya Raman\r
                TEL;CELL:+91 98765 43210\r
                END:VCARD\r
                BEGIN:VCARD\r
                VERSION:2.1\r
                N:Ravi;Kumar;;;\r
                TEL:9000000001\r
                END:VCARD\r
                BEGIN:VCARD\r
                FN:Ravi K\r
                TEL:9000000002\r
                END:VCARD\r
                BEGIN:VCARD\r
                FN:Old Friend\r
                TEL:9111111111\r
                EMAIL:old@friend.example\r
                END:VCARD\r
                """);
        files.put(2L, """
                BEGIN:VCARD\r
                VERSION:3.0\r
                FN:Ravi Kumar\r
                TEL:9000000001\r
                EMAIL:ravi.kumar@example.net\r
                ORG:Kumar Tutorials\r
                X-MBOX-REPLACES:ravi kumar|9000000001||ravi k|9000000002\r
                X-MBOX-AUTHORED:1\r
                END:VCARD\r
                BEGIN:VCARD\r
                X-MBOX-DELETED:old friend|9111111111\r
                X-MBOX-AUTHORED:1\r
                END:VCARD\r
                """);
    }

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry r) {
        String base = "http://localhost:" + fake.getAddress().getPort();
        r.add("magizhchi.apps.box.api", () -> base);
        r.add("magizhchi.apps.pa.api", () -> base);
        r.add("magizhchi.apps.pa.web", () -> "http://pa.test");
    }

    @AfterAll
    static void stop() {
        fake.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Reminders reminders;

    @Test
    void snoozeRemindersNotesCalendarContactsAndAlerts() throws Exception {
        deliver("Priya Raman <priya@example.com>", "Batch timing", "Can we move Saturday's batch to 6pm?", null);
        deliver("Deals <deals@shop.example>", "50% off this week", "Big sale", "<mailto:unsub@shop.example>");

        String token = call(post("/api/auth/sso").contentType(MediaType.APPLICATION_JSON).content("{\"idToken\":\"" +
                TestIds.idToken("2b7c1f0e-4444-4d3a-8b44-000000000004", "tiru@magizhchi.test", "Tiru") + "\"}"), null, 200).path("token").asText();
        long accountId = call(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(
                "{\"email\":\"tiru@magizhchi.test\",\"password\":\"pw\",\"imapHost\":\"localhost\",\"imapPort\":3143,\"smtpHost\":\"localhost\",\"smtpPort\":3025}"),
                token, 200).path("id").asLong();
        long batch = waitFor(token, "Batch timing");

        // ── Send one with "remind me if nobody replies" ──
        Instant remind = Instant.now().plus(Duration.ofHours(1)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        String payload = "{\"accountId\":" + accountId + ",\"to\":[\"Priya Raman <priya@example.com>\",\"Kumar <kumar@client.example>\"]," +
                "\"subject\":\"Saturday plan\",\"text\":\"Let's do 6pm.\",\"remindAt\":\"" + remind + "\"}";
        long sent = call(multipart("/api/send").file(new MockMultipartFile("payload", "", "application/json", payload.getBytes())), token, 200)
                .path("id").asLong();
        assertThat(call(get("/api/messages/" + sent), token, 200).path("followUpAt").asText()).isEqualTo(remind.toString());
        call(post("/api/messages/" + batch + "/followup").contentType(MediaType.APPLICATION_JSON).content("{\"at\":\"" + remind + "\"}"), token, 400);

        // ── Snooze: gone from the inbox and the counts, listed under Snoozed ──
        Instant until = Instant.now().plus(Duration.ofMinutes(30)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        call(post("/api/messages/" + batch + "/seen").contentType(MediaType.APPLICATION_JSON).content("{\"seen\":true}"), token, 204);
        JsonNode snoozed = call(post("/api/messages/" + batch + "/snooze").contentType(MediaType.APPLICATION_JSON)
                .content("{\"at\":\"" + until + "\"}"), token, 200);
        assertThat(snoozed.path("snoozedUntil").asText()).isEqualTo(until.toString());
        assertThat(subjects(token, "lane=all")).doesNotContain("Batch timing");
        assertThat(subjects(token, "folder=SNOOZED")).containsExactly("Batch timing");
        call(post("/api/messages/" + batch + "/snooze").contentType(MediaType.APPLICATION_JSON)
                .content("{\"at\":\"" + Instant.now().minusSeconds(60) + "\"}"), token, 400);
        call(post("/api/messages/" + sent + "/snooze").contentType(MediaType.APPLICATION_JSON)
                .content("{\"at\":\"" + until + "\"}"), token, 400);       // only inbox mail

        // Two hours later: it's back on top and unread, and the unanswered mail is back in Needs me.
        assertThat(reminders.run(Instant.now().plus(Duration.ofHours(2)))).isEqualTo(2);
        JsonNode top = items(token, "lane=all").path(0);
        assertThat(top.path("backReason").asText()).isIn("snooze", "no_reply");
        List<String> back = new ArrayList<>();
        items(token, "lane=all").forEach(n -> { if (!n.path("backReason").isNull()) back.add(n.path("subject").asText() + ":" + n.path("backReason").asText()); });
        assertThat(back).containsExactlyInAnyOrder("Batch timing:snooze", "Saturday plan:no_reply");
        assertThat(subjects(token, "lane=needs_me")).contains("Saturday plan");
        assertThat(subjects(token, "folder=SNOOZED")).isEmpty();
        JsonNode woke = call(get("/api/messages/" + batch), token, 200);
        assertThat(woke.path("seen").asBoolean()).isFalse();
        // Opened: no longer pinned.
        for (JsonNode n : items(token, "lane=all")) {
            if (n.path("id").asLong() == batch) assertThat(n.path("backReason").isNull()).isTrue();
        }
        assertThat(call(get("/api/messages/" + sent), token, 200).path("followUpAt").isNull()).isTrue();

        // ── Notes on the conversation ──
        assertThat(call(get("/api/messages/" + batch + "/note"), token, 200).path("body").isNull()).isTrue();
        JsonNode note = call(put("/api/messages/" + batch + "/note").contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Ask about parking near the hall.\\n\\nShe prefers WhatsApp.\"}"), token, 200);
        assertThat(note.path("updatedAt").asText()).isNotBlank();
        assertThat(call(get("/api/messages/" + batch), token, 200).path("note").path("body").asText()).startsWith("Ask about parking");
        assertThat(subjects(token, "q=parking")).containsExactly("Batch timing");
        for (JsonNode n : items(token, "lane=all")) {
            if (n.path("id").asLong() == batch) assertThat(n.path("hasNote").asBoolean()).isTrue();
        }
        call(put("/api/messages/" + batch + "/note").contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"" + "x".repeat(10_001) + "\"}"), token, 400);

        JsonNode toPa = call(post("/api/messages/" + batch + "/pa/note"), token, 200);
        assertThat(toPa.path("id").asText()).isEqualTo("note-1");
        assertThat(paNotes.get(0).path("title").asText()).isEqualTo("Note: Batch timing");
        assertThat(paNotes.get(0).path("content").asText()).contains("<p>Ask about parking near the hall.</p>").contains("Priya Raman");
        call(post("/api/messages/" + batch + "/pa/note"), token, 200);
        assertThat(paCalls).contains("PUT /api/v1/notes/note-1");      // the second time it updates the same note
        call(delete("/api/messages/" + batch + "/note"), token, 204);
        assertThat(subjects(token, "q=parking")).isEmpty();
        call(post("/api/messages/" + batch + "/pa/note"), token, 400);

        // ── The PA calendar ──
        Instant from = Instant.parse("2026-11-09T00:00:00Z");
        JsonNode agenda = call(get(URI.create("/api/pa/agenda?from=" + enc(from.toString()) + "&to=" + enc(from.plus(Duration.ofDays(7)).toString()))), token, 200);
        assertThat(agenda.path("events").size()).isEqualTo(1);          // the cancelled one is left out
        assertThat(agenda.path("events").path(0).path("title").asText()).isEqualTo("Saturday batch");
        assertThat(agenda.path("events").path(0).path("color").asText()).isEqualTo("#0ea5e9");   // from its calendar
        assertThat(agenda.path("tasks").size()).isEqualTo(1);           // done and out-of-range tasks left out
        assertThat(agenda.path("tasks").path(0).path("title").asText()).isEqualTo("Send fee receipts");
        call(get(URI.create("/api/pa/agenda?from=" + enc(from.toString()) + "&to=" + enc(from.plus(Duration.ofDays(60)).toString()))), token, 400);

        // ── Contacts: from mail before Box is connected ──
        JsonNode s1 = call(get("/api/contacts/suggest?q=pri"), token, 200);
        assertThat(s1.path("box").path("connected").asBoolean()).isFalse();
        assertThat(s1.path("items").path(0).path("email").asText()).isEqualTo("priya@example.com");
        assertThat(s1.path("items").path(0).path("name").asText()).isEqualTo("Priya Raman");
        assertThat(s1.path("items").path(0).path("hint").asText()).startsWith("You wrote once");
        assertThat(call(get("/api/contacts/suggest?q=deals"), token, 200).path("items").size()).isZero();   // newsletters aren't people
        // Nothing typed, Priya already on the mail: Kumar usually comes with her.
        JsonNode often = call(get("/api/contacts/suggest?q=&with=priya@example.com"), token, 200).path("items");
        assertThat(often.path(0).path("email").asText()).isEqualTo("kumar@client.example");
        assertThat(often.path(0).path("why").asText()).isEqualTo("Often on mail with priya@example.com");

        // ── Connect the Box book: merged the way the Box app merges it ──
        JsonNode connected = call(post("/api/contacts/connect"), token, 200);
        assertThat(connected.path("box").path("connected").asBoolean()).isTrue();
        assertThat(connected.path("box").path("contacts").asInt()).isEqualTo(2);    // Priya, Ravi Kumar (merged); Old Friend deleted
        JsonNode rav = call(get("/api/contacts/suggest?q=rav"), token, 200).path("items");
        assertThat(rav.size()).isEqualTo(1);
        assertThat(rav.path(0).path("email").asText()).isEqualTo("ravi.kumar@example.net");
        assertThat(rav.path(0).path("phone").asText()).isEqualTo("9000000001");
        assertThat(rav.path(0).path("source").asText()).isEqualTo("box");
        assertThat(call(get("/api/contacts/suggest?q=old"), token, 200).path("items").size()).isZero();
        JsonNode book = call(get("/api/contacts"), token, 200).path("items");
        List<String> names = new ArrayList<>();
        book.forEach(n -> names.add(n.path("name").asText()));
        assertThat(names).contains("Priya Raman", "Ravi Kumar", "Kumar").doesNotContain("Old Friend", "Ravi K");

        // ── Before sending: typos and first-timers ──
        JsonNode check = call(get(URI.create("/api/contacts/check?emails=" + enc("priya@exmaple.com") + "&emails=" + enc("new@nowhere.example")
                + "&emails=" + enc("bob@gmial.com") + "&emails=" + enc("kumar@client.example") + "&emails=" + enc("not-an-address"))), token, 200).path("items");
        assertThat(check.size()).isEqualTo(4);
        assertThat(check.path(0).path("didYouMean").asText()).isEqualTo("priya@example.com");
        assertThat(check.path(0).path("message").asText()).isEqualTo("Did you mean priya@example.com (Priya Raman)?");
        assertThat(check.path(1).path("problem").asText()).isEqualTo("first_time");
        assertThat(check.path(2).path("didYouMean").asText()).isEqualTo("bob@gmail.com");
        assertThat(check.path(3).path("problem").asText()).isEqualTo("invalid");

        // ── The sender card, and saving Priya's email onto her Box card ──
        JsonNode card = call(get("/api/contacts/card?email=priya@example.com"), token, 200);
        assertThat(card.path("sent").asInt()).isEqualTo(1);
        assertThat(card.path("received").asInt()).isEqualTo(1);
        assertThat(card.path("inBox").asBoolean()).isFalse();           // her Box card has her phone, not her email
        assertThat(card.path("oftenWith").path(0).asText()).isEqualTo("kumar@client.example");
        JsonNode saved = call(post("/api/contacts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Priya Raman\",\"email\":\"priya@example.com\"}"), token, 200);
        assertThat(saved.path("message").asText()).isEqualTo("Added to Priya Raman's card in Magizhchi Box.");
        assertThat(saved.path("inBox").asBoolean()).isTrue();
        assertThat(saved.path("phones").path(0).asText()).isEqualTo("+91 98765 43210");
        assertThat(uploads).hasSize(1);
        assertThat(uploads.get(0)).contains("name=\"folderPath\"\r\n\r\nSynced/Mobile/Contacts/")
                .contains("EMAIL:priya@example.com").contains("TEL:+91 98765 43210")
                .contains("X-MBOX-REPLACES:priya raman|9876543210").contains("X-MBOX-AUTHORED:1");
        call(post("/api/contacts").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"nope\"}"), token, 400);
        assertThat(call(get("/api/contacts"), token, 200).path("box").path("contacts").asInt()).isEqualTo(2);  // still one Priya

        // ── Alerts: settings and this browser ──
        JsonNode push = call(get("/api/push"), token, 200);
        assertThat(push.path("configured").asBoolean()).isFalse();
        assertThat(push.path("mode").asText()).isEqualTo("needs_me");
        call(post("/api/push/subscribe").contentType(MediaType.APPLICATION_JSON).content(
                "{\"endpoint\":\"https://push.example/abc\",\"keys\":{\"p256dh\":\"BPk\",\"auth\":\"a1\"}}"), token, 200);
        assertThat(call(get("/api/push"), token, 200).path("devices").asInt()).isEqualTo(1);
        call(post("/api/push/subscribe").contentType(MediaType.APPLICATION_JSON).content(
                "{\"endpoint\":\"http://insecure.example/abc\",\"keys\":{\"p256dh\":\"BPk\",\"auth\":\"a1\"}}"), token, 400);
        assertThat(call(put("/api/push").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"all\"}"), token, 200).path("mode").asText()).isEqualTo("all");
        call(put("/api/push").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"loud\"}"), token, 400);
        call(post("/api/push/test"), token, 400);
        call(post("/api/push/unsubscribe").contentType(MediaType.APPLICATION_JSON).content("{\"endpoint\":\"https://push.example/abc\"}"), token, 200);
        assertThat(call(get("/api/push"), token, 200).path("devices").asInt()).isZero();
    }

    // ── a stand-in Magizhchi Box and Magizhchi PA ─────────────────

    static HttpServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            s.createContext("/", ProductivityTest::handle);
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getRawQuery();
        String method = ex.getRequestMethod();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            // Magizhchi PA
            if (path.startsWith("/api/v1/")) {
                paCalls.add(method + " " + path);
                if (path.equals("/api/v1/auth/sso")) {
                    reply(ex, 200, "{\"accessToken\":\"pa-session\",\"refreshToken\":\"r\",\"expiresIn\":900,\"user\":{\"id\":\"u1\"}}");
                    return;
                }
                if (!"Bearer pa-session".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                    reply(ex, 401, "{\"message\":\"Unauthorized\"}");
                    return;
                }
                switch (method + " " + path) {
                    case "GET /api/v1/calendars" -> reply(ex, 200, "[{\"id\":\"cal-1\",\"name\":\"Work\",\"color\":\"#0ea5e9\",\"isDefault\":true}]");
                    case "GET /api/v1/events" -> {
                        assertThat(query).contains("from=2026-11-09T00%3A00%3A00Z");
                        reply(ex, 200, "[{\"eventId\":\"e1\",\"calendarId\":\"cal-1\",\"title\":\"Saturday batch\",\"occurrenceStart\":\"2026-11-14T12:30:00Z\"," +
                                "\"occurrenceEnd\":\"2026-11-14T14:00:00Z\",\"allDay\":false,\"status\":\"CONFIRMED\",\"recurring\":true}," +
                                "{\"eventId\":\"e2\",\"calendarId\":\"cal-1\",\"title\":\"Called off\",\"occurrenceStart\":\"2026-11-10T05:00:00Z\"," +
                                "\"occurrenceEnd\":\"2026-11-10T06:00:00Z\",\"status\":\"CANCELLED\"}]");
                    }
                    case "GET /api/v1/tasks" -> reply(ex, 200, "[{\"id\":\"t1\",\"title\":\"Send fee receipts\",\"status\":\"TODO\",\"dueAt\":\"2026-11-11T04:30:00Z\"}," +
                            "{\"id\":\"t2\",\"title\":\"Done already\",\"status\":\"DONE\",\"dueAt\":\"2026-11-11T04:30:00Z\"}," +
                            "{\"id\":\"t3\",\"title\":\"Next month\",\"status\":\"TODO\",\"dueAt\":\"2026-12-20T04:30:00Z\"}," +
                            "{\"id\":\"t4\",\"title\":\"Someday\",\"status\":\"TODO\"}]");
                    case "POST /api/v1/notes" -> {
                        paNotes.add(JSON.readTree(body));
                        reply(ex, 201, "{\"id\":\"note-1\"}");
                    }
                    case "PUT /api/v1/notes/note-1" -> {
                        paNotes.add(JSON.readTree(body));
                        reply(ex, 200, "{\"id\":\"note-1\"}");
                    }
                    default -> reply(ex, 404, "{\"message\":\"not found\"}");
                }
                return;
            }
            // Magizhchi Box
            if (method.equals("POST") && path.equals("/api/provision/workspace")) {
                reply(ex, 200, "{\"apiKey\":\"" + KEY + "\",\"conversationId\":77}");
                return;
            }
            if (path.startsWith("/dl/")) {                          // presigned: no key needed
                String text = files.get(Long.parseLong(path.substring(4)));
                byte[] b = text.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
                return;
            }
            if (!KEY.equals(ex.getRequestHeaders().getFirst("X-Api-Key"))) {
                reply(ex, 401, "{\"message\":\"Invalid API key\"}");
                return;
            }
            if (method.equals("GET") && path.equals("/api/files/mirror-list")) {
                assertThat(query).isEqualTo("prefix=Synced%2FMobile%2FContacts%2F");
                StringBuilder sb = new StringBuilder("[");
                files.keySet().stream().sorted().forEach(id -> sb.append(sb.length() > 1 ? "," : "")
                        .append("{\"id\":").append(id).append(",\"fileName\":\"contacts-").append(id).append(".vcf\",")
                        .append("\"folderPath\":\"Synced/Mobile/Contacts/\",\"sentAt\":\"2026-11-0").append(Math.min(9, id)).append("T00:00:00\"}"));
                sb.append(",{\"id\":99,\"fileName\":\"readme.txt\",\"sentAt\":\"2026-11-01T00:00:00\"}]");
                reply(ex, 200, sb.toString());
                return;
            }
            if (method.equals("GET") && path.matches("/api/files/\\d+/preview-url")) {
                String id = path.split("/")[3];
                reply(ex, 200, "{\"url\":\"http://localhost:" + fake.getAddress().getPort() + "/dl/" + id + "\"}");
                return;
            }
            if (method.equals("POST") && path.equals("/api/files/send/77")) {
                uploads.add(body);
                int start = body.indexOf("BEGIN:VCARD");
                int end = body.indexOf("END:VCARD") + "END:VCARD".length();
                files.put(10L + uploads.size(), body.substring(start, end) + "\r\n");
                reply(ex, 201, "{\"id\":" + (500 + uploads.size()) + "}");
                return;
            }
            reply(ex, 404, "{\"message\":\"not found\"}");
        } catch (AssertionError | Exception e) {
            reply(ex, 500, "{\"message\":\"" + e.getMessage().replace("\"", "'").replace("\n", " ") + "\"}");
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

    private void deliver(String from, String subject, String text, String unsubscribe) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress(from));
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse("tiru@magizhchi.test"));
        m.setSubject(subject);
        m.setText(text);
        if (unsubscribe != null) m.setHeader("List-Unsubscribe", unsubscribe);
        m.setSentDate(Date.from(Instant.now().minusSeconds(600)));
        m.saveChanges();
        greenMail.getUserManager().getUserByEmail("tiru@magizhchi.test").deliver(m);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private JsonNode call(MockHttpServletRequestBuilder req, String token, int status) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        var res = mvc.perform(req).andReturn().getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(status);
        String body = res.getContentAsString();
        return body.isEmpty() ? json.createObjectNode() : json.readTree(body);
    }

    private JsonNode items(String token, String query) throws Exception {
        return call(get(URI.create("/api/messages?" + query)), token, 200).path("items");
    }

    private List<String> subjects(String token, String query) throws Exception {
        List<String> out = new ArrayList<>();
        items(token, query).forEach(n -> out.add(n.path("subject").asText()));
        return out;
    }

    private long waitFor(String token, String subject) throws Exception {
        for (int i = 0; i < 100; i++) {
            for (JsonNode it : items(token, "lane=all")) if (subject.equals(it.path("subject").asText())) return it.path("id").asLong();
            Thread.sleep(100);
        }
        throw new AssertionError("No message " + subject);
    }
}
