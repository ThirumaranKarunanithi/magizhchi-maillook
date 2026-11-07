package software.magizhchi.maillook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Slice 4, the academy tools, against a stand-in Meet and PA: the weekly Scrum digest (emailed
 * reports, a PDF and the week's standups), the placement drive room, the "should be a call" hint,
 * and a meeting invite sent from the person's own mailbox (Meet asked to stay quiet).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${MAILLOOK_TEST_DB_URL:jdbc:h2:mem:academy;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${MAILLOOK_TEST_DB_USER:sa}",
        "spring.datasource.password=${MAILLOOK_TEST_DB_PASSWORD:}",
        "maillook.sync.interval-ms=3600000",
        "maillook.ai.api-key=",
        "magizhchi.apps.meet.web=https://meet.test"})
@AutoConfigureMockMvc
class AcademyFlowTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("tiru@magizhchi.test", "tiru@magizhchi.test", "pw"));

    static final String ACCOUNT_ID = "2b7c1f0e-5555-4d3a-8b44-000000000009";
    static final String SCRUM = "12121212-2222-3333-4444-555555555555";
    static final String NEW_ROOM = "34343434-2222-3333-4444-555555555555";
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<JsonNode> created = new CopyOnWriteArrayList<>();
    static final List<JsonNode> schedules = new CopyOnWriteArrayList<>();
    static final List<JsonNode> members = new CopyOnWriteArrayList<>();
    static final List<String> digestPrompts = new CopyOnWriteArrayList<>();
    static final String DRIVE_DAY = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(5).toString();
    static final HttpServer meet = MeetFlowTest.start(AcademyFlowTest::meet);
    static final HttpServer pa = MeetFlowTest.start(AcademyFlowTest::pa);

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
    void digestDriveCallHintAndOwnInvites() throws Exception {
        // Three daily reports from two people; Arun's is a PDF.
        deliver("Priya <priya@example.com>", "Daily report – 3 Nov", "Done: checkout page.\nNext: refunds screen.", null, null);
        deliver("Arun <arun@example.com>", "Daily report – 4 Nov", "Report attached.", pdf("Arun: payment API finished. Blocked on the bank's test keys."), null);
        deliver("Priya <priya@example.com>", "Re: Daily report - 5 Nov", "Done: refunds screen half way.", null, null);
        // A placement drive with a shortlist.
        deliver("Placement Cell <placement@college.test>", "Zentech campus placement drive",
                "Dear sir, Zentech's placement drive is on " + DRIVE_DAY + " at 10:00 AM for 2 hours, 120 seats.\n"
                        + "Shortlisted: kavin@student.test, meena@student.test\nPlease host it online.", null, null);
        // A long thread between three people.
        for (int i = 0; i < 6; i++) {
            String[] who = {"Priya <priya@example.com>", "Arun <arun@example.com>", "Divya <divya@example.com>"};
            deliver(who[i % 3], i == 0 ? "Release plan" : "Re: Release plan", "Point " + i + " about the release.", null, i == 0 ? "first" : "reply");
        }

        String token = call(post("/api/auth/sso").contentType(MediaType.APPLICATION_JSON).content(
                "{\"idToken\":\"" + TestIds.idToken(ACCOUNT_ID, "tiru@magizhchi.test", "Tiru") + "\"}"), null, 200).path("token").asText();
        JsonNode account = call(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(
                "{\"email\":\"tiru@magizhchi.test\",\"password\":\"pw\",\"imapHost\":\"localhost\",\"imapPort\":3143,\"smtpHost\":\"localhost\",\"smtpPort\":3025}"),
                token, 200);
        long arunReport = waitForMessage(token, "Daily report – 4 Nov");
        long drive = waitForMessage(token, "Zentech campus placement drive");
        long release = waitForMessage(token, "Re: Release plan");
        waitForCount(token, 10);

        // ── Weekly Scrum digest ──
        JsonNode offer = call(get("/api/messages/" + arunReport + "/digest"), token, 200);
        assertThat(offer.path("available").asBoolean()).isTrue();
        assertThat(offer.path("reports").asInt()).isEqualTo(3);
        assertThat(offer.path("senders").asInt()).isEqualTo(2);
        assertThat(offer.path("title").asText()).isEqualTo("Daily report");
        assertThat(offer.path("rooms").size()).isEqualTo(1);
        assertThat(offer.path("roomId").asText()).isEqualTo(SCRUM);
        // The placement mail is no series.
        assertThat(call(get("/api/messages/" + drive + "/digest"), token, 200).path("available").asBoolean()).isFalse();

        JsonNode digest = call(post("/api/messages/" + arunReport + "/digest").contentType(MediaType.APPLICATION_JSON)
                .content("{\"roomId\":\"" + SCRUM + "\",\"days\":7}"), token, 200);
        String prompt = digestPrompts.get(digestPrompts.size() - 1);
        assertThat(prompt).contains("Team: Payments standup").contains("checkout page")
                .contains("Blocked on the bank's test keys")             // read out of the PDF
                .contains("Standup on").contains("Missed: Divya")
                .contains("Priya: Refunds screen is next.");             // the standup's captions
        assertThat(digest.path("subject").asText()).startsWith("Weekly digest: Daily report (");
        assertThat(digest.path("reports").asInt()).isEqualTo(3);
        assertThat(digest.path("pdfs").asInt()).isEqualTo(1);
        assertThat(digest.path("standups").asInt()).isEqualTo(2);
        assertThat(texts(digest.path("to"))).containsExactlyInAnyOrder("arun@example.com", "priya@example.com", "divya@example.com");
        JsonNode divya = find(digest.path("attendance"), "Divya");
        assertThat(divya.path("missed").asInt()).isEqualTo(2);
        assertThat(digest.path("body").asText()).contains("Arun\n  Done: Payment API").contains("Blocked on: Bank test keys")
                .contains("Standups: 2 held. Attendance: Arun 2/2, Priya 2/2, Divya 0/2.")
                .contains("Made from 3 emailed reports (1 PDF) and 2 standup transcripts.");

        // ── Placement drive ──
        JsonNode event = call(get("/api/messages/" + drive + "/meet/event"), token, 200);
        assertThat(event.path("isEvent").asBoolean()).isTrue();
        assertThat(event.path("title").asText()).isEqualTo("Zentech placement drive");
        assertThat(event.path("seats").asInt()).isEqualTo(120);
        assertThat(event.path("minutes").asInt()).isEqualTo(120);
        assertThat(event.path("startAt").asText()).isEqualTo(java.time.LocalDateTime.parse(DRIVE_DAY + "T10:00")
                .atZone(ZoneId.of("Asia/Kolkata")).toInstant().toString());
        // The AI's made-up address is dropped; only addresses really in the thread are kept.
        assertThat(texts(event.path("applicants"))).containsExactly("kavin@student.test", "meena@student.test");
        // A thread with no event words never asks the AI.
        assertThat(call(get("/api/messages/" + release + "/meet/event"), token, 200).path("isEvent").asBoolean()).isFalse();

        members.clear();
        JsonNode room = call(post("/api/messages/" + drive + "/meet/event").contentType(MediaType.APPLICATION_JSON).content(
                "{\"name\":\"Zentech placement drive\",\"emails\":[\"kavin@student.test\",\"meena@student.test\"],\"startAt\":\""
                        + event.path("startAt").asText() + "\",\"minutes\":120,\"seats\":120}"), token, 200);
        JsonNode card = created.get(created.size() - 1);
        assertThat(card.path("type").asText()).isEqualTo("AUDITORIUM");
        assertThat(card.path("capacity").asInt()).isEqualTo(120);
        assertThat(card.path("secure").asBoolean()).isFalse();
        assertThat(room.path("message").asText()).contains("2 people invited").contains("120 seats");
        assertThat(members).hasSize(2);
        assertThat(members.get(0).has("sendEmail")).isFalse();   // Meet emails the applicants itself
        call(post("/api/messages/" + drive + "/meet/event").contentType(MediaType.APPLICATION_JSON).content(
                "{\"emails\":[\"kavin@student.test\"]}"), token, 400);

        // ── This thread should be a call ──
        JsonNode hint = call(get("/api/messages/" + release + "/meet"), token, 200).path("callHint");
        assertThat(hint.path("replies").asInt()).isEqualTo(6);
        assertThat(hint.path("people").asInt()).isEqualTo(3);
        assertThat(texts(hint.path("names"))).containsExactly("Priya", "Arun", "Divya");
        assertThat(texts(hint.path("emails"))).contains("priya@example.com", "arun@example.com", "divya@example.com");
        assertThat(call(get("/api/messages/" + arunReport + "/meet"), token, 200).path("callHint").isObject()).isFalse();

        // ── The invite from your own mailbox ──
        members.clear();
        String at = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES).toString();
        JsonNode booked = call(post("/api/messages/" + release + "/meet").contentType(MediaType.APPLICATION_JSON).content(
                "{\"emails\":[\"priya@example.com\",\"arun@example.com\"],\"startAt\":\"" + at + "\",\"minutes\":30,\"fromMailbox\":true}"), token, 200);
        assertThat(booked.path("inviteCardId").asText()).isEqualTo(NEW_ROOM);
        assertThat(booked.path("replyBody").asText()).contains("The calendar invite is attached.");
        assertThat(booked.path("message").asText()).contains("send the reply to invite them");
        assertThat(members).allMatch(m -> !m.path("sendEmail").asBoolean(true));   // Meet stays quiet...
        assertThat(schedules.get(schedules.size() - 1).path("sendEmail").asBoolean(true)).isFalse();
        // ...and the thread now has its room, so no more call hint.
        assertThat(call(get("/api/messages/" + release + "/meet"), token, 200).path("callHint").isObject()).isFalse();

        long accountId = account.path("id").asLong();
        String payload = "{\"accountId\":" + accountId + ",\"to\":[\"Priya <priya@example.com>\"],\"cc\":[\"arun@example.com\"],"
                + "\"subject\":\"Re: Release plan\",\"text\":" + JSON.writeValueAsString(booked.path("replyBody").asText())
                + ",\"replyToMessageId\":" + release + ",\"inviteCardId\":\"" + NEW_ROOM + "\"}";
        call(multipart("/api/send").file(new MockMultipartFile("payload", "", "application/json", payload.getBytes(StandardCharsets.UTF_8))),
                token, 200);
        MimeMessage sent = null;
        for (int i = 0; i < 50 && sent == null; i++) {
            for (MimeMessage m : greenMail.getReceivedMessages()) {
                if ("Re: Release plan".equals(m.getSubject()) && m.getFrom()[0].toString().contains("tiru@magizhchi.test")) sent = m;
            }
            if (sent == null) Thread.sleep(100);
        }
        assertThat(sent).isNotNull();
        String raw = GreenMailUtil.getWholeMessage(sent).replace("\r\n ", "");
        String start = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC).format(Instant.parse(at));
        assertThat(raw).contains("text/calendar").contains("method=REQUEST").contains("invite.ics")
                .contains("METHOD:REQUEST").contains("DTSTART:" + start)
                .contains("ORGANIZER;CN=").contains(":mailto:tiru@magizhchi.test")
                .contains("ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:priya@example.com")
                .contains("mailto:arun@example.com")
                .contains("https://meet.test/room/" + NEW_ROOM)
                .contains("In-Reply-To:");
        // An invite for a room this mailbox never booked is refused.
        String bad = payload.replace(NEW_ROOM, "ffffffff-2222-3333-4444-555555555555");
        call(multipart("/api/send").file(new MockMultipartFile("payload", "", "application/json", bad.getBytes(StandardCharsets.UTF_8))),
                token, 400);
    }

    // ── stand-ins ────────────────────────────────────────────────

    static void meet(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (path.equals("/api/v1/auth/sso")) { reply(ex, 200, "{\"token\":\"meet-session-1\",\"user\":{\"id\":\"u1\"}}"); return; }
        if (!"Bearer meet-session-1".equals(ex.getRequestHeaders().getFirst("Authorization"))) { reply(ex, 401, "{}"); return; }
        String scrum = "{\"id\":\"" + SCRUM + "\",\"name\":\"Payments standup\",\"type\":\"SCRUM\",\"myRole\":\"SUPER_ADMIN\",\"ephemeral\":false}";
        String batch = "{\"id\":\"56565656-2222-3333-4444-555555555555\",\"name\":\"Saturday batch\",\"type\":\"CLASS\",\"myRole\":\"SUPER_ADMIN\",\"ephemeral\":false}";
        if (method.equals("GET") && path.equals("/api/v1/cards")) { reply(ex, 200, "[" + scrum + "," + batch + "]"); return; }
        if (method.equals("POST") && path.equals("/api/v1/cards")) {
            created.add(JSON.readTree(body));
            reply(ex, 200, "{\"id\":\"" + NEW_ROOM + "\"}");
            return;
        }
        if (method.equals("GET") && path.equals("/api/v1/cards/" + SCRUM)) { reply(ex, 200, scrum); return; }
        if (method.equals("GET") && path.equals("/api/v1/cards/" + SCRUM + "/meetings")) {
            assertThat(ex.getRequestURI().getRawQuery()).startsWith("since=");
            Instant d1 = Instant.now().minus(2, ChronoUnit.DAYS), d2 = Instant.now().minus(1, ChronoUnit.DAYS);
            String people = "\"attended\":[{\"name\":\"Arun\",\"email\":\"arun@example.com\",\"minutes\":15},{\"name\":\"Priya\",\"email\":\"priya@example.com\",\"minutes\":15}],"
                    + "\"missed\":[{\"name\":\"Divya\",\"email\":\"divya@example.com\",\"minutes\":0}]";
            reply(ex, 200, "[{\"startedAt\":\"" + d2 + "\",\"endedAt\":\"" + d2.plus(15, ChronoUnit.MINUTES) + "\",\"ended\":true,\"minutes\":15," + people + "},"
                    + "{\"startedAt\":\"" + d1 + "\",\"endedAt\":\"" + d1.plus(15, ChronoUnit.MINUTES) + "\",\"ended\":true,\"minutes\":15," + people + "}]");
            return;
        }
        if (method.equals("GET") && path.equals("/api/v1/cards/" + SCRUM + "/meetings/transcript")) {
            reply(ex, 200, "[{\"speaker\":\"Priya\",\"text\":\"Refunds screen is next.\",\"at\":\"" + Instant.now() + "\"}]");
            return;
        }
        Matcher m = Pattern.compile("^/api/v1/cards/([0-9a-f-]{36})(/.*)?$").matcher(path);
        if (m.matches()) {
            String rest = m.group(2) == null ? "" : m.group(2);
            if (method.equals("PUT") && rest.equals("/schedule")) { schedules.add(JSON.readTree(body)); reply(ex, 200, ""); return; }
            if (method.equals("POST") && rest.equals("/members")) {
                members.add(JSON.readTree(body));
                reply(ex, 200, "{\"status\":\"INVITED\",\"email\":\"" + JSON.readTree(body).path("email").asText() + "\"}");
                return;
            }
        }
        reply(ex, 404, "{}");
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
            if (system.contains("weekly Scrum digest")) {
                digestPrompts.add(req.path("prompt").asText());
                text = "{\"summary\":\"Checkout shipped; payments wait on the bank.\",\"people\":["
                        + "{\"name\":\"Arun\",\"done\":[\"Payment API\"],\"next\":[],\"blockers\":[\"Bank test keys\"]},"
                        + "{\"name\":\"Priya\",\"done\":[\"Checkout page\"],\"next\":[\"Refunds screen\"],\"blockers\":[]}],\"risks\":[]}";
            } else if (system.contains("online session for many people")) {
                assertThat(req.path("prompt").asText()).contains("kavin@student.test").contains("meena@student.test");
                text = "{\"isEvent\":true,\"title\":\"Zentech placement drive\",\"start\":\"" + DRIVE_DAY + "T10:00\",\"zone\":null,"
                        + "\"minutes\":120,\"seats\":120,\"applicants\":[\"kavin@student.test\",\"MEENA@student.test\",\"ghost@nowhere.test\"]}";
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
        MeetFlowTest.reply(ex, code, body);
    }

    // ── helpers ───────────────────────────────────────────────────

    private static byte[] pdf(String line) throws IOException {
        try (PDDocument d = new PDDocument()) {
            PDPage p = new PDPage();
            d.addPage(p);
            try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(line);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            d.save(out);
            return out.toByteArray();
        }
    }

    /** {@code thread}: "first" starts the release thread, "reply" answers it. */
    private void deliver(String from, String subject, String text, byte[] pdf, String thread) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress(from));
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse("tiru@magizhchi.test"));
        m.setSubject(subject, "UTF-8");
        if (pdf == null) {
            m.setText(text, "UTF-8");
        } else {
            MimeMultipart mp = new MimeMultipart();
            MimeBodyPart t = new MimeBodyPart();
            t.setText(text, "UTF-8");
            mp.addBodyPart(t);
            MimeBodyPart f = new MimeBodyPart();
            f.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(pdf, "application/pdf")));
            f.setFileName("report.pdf");
            mp.addBodyPart(f);
            m.setContent(mp);
        }
        m.setSentDate(Date.from(Instant.now()));
        m.saveChanges();
        if ("first".equals(thread)) m.setHeader("Message-ID", "<release-1@example.com>");
        if ("reply".equals(thread)) {
            m.setHeader("In-Reply-To", "<release-1@example.com>");
            m.setHeader("References", "<release-1@example.com>");
        }
        greenMail.getUserManager().getUserByEmail("tiru@magizhchi.test").deliver(m);
    }

    private long waitForMessage(String token, String subject) throws Exception {
        for (int i = 0; i < 100; i++) {
            for (JsonNode it : call(get("/api/messages?limit=100"), token, 200).path("items")) {
                if (subject.equals(it.path("subject").asText())) return it.path("id").asLong();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Mail never synced: " + subject);
    }

    private void waitForCount(String token, int n) throws Exception {
        for (int i = 0; i < 100 && call(get("/api/messages?limit=100"), token, 200).path("items").size() < n; i++) Thread.sleep(100);
    }

    private static List<String> texts(JsonNode a) {
        List<String> out = new java.util.ArrayList<>();
        a.forEach(x -> out.add(x.asText()));
        return out;
    }

    private static JsonNode find(JsonNode list, String name) {
        for (JsonNode x : list) if (name.equals(x.path("name").asText())) return x;
        throw new AssertionError("No " + name + " in " + list);
    }

    private JsonNode call(MockHttpServletRequestBuilder req, String token, int status) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        var res = mvc.perform(req).andReturn().getResponse();
        String body = res.getContentAsString(StandardCharsets.UTF_8);
        assertThat(res.getStatus()).as(body).isEqualTo(status);
        return body.isEmpty() ? json.createObjectNode() : json.readTree(body);
    }

}
