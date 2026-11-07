package software.magizhchi.maillook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
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
import software.magizhchi.maillook.mail.Outbox;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The everyday basics: a draft keeps its files; Send waits 10 seconds and can be undone (the mail comes back
 * as a draft, files and all); Send later waits until its time and can be moved or sent now; Archive all
 * empties Can wait and Undo puts it back; Files lists every attachment; one-tap replies and translation.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${MAILLOOK_TEST_DB_URL:jdbc:h2:mem:everyday;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${MAILLOOK_TEST_DB_USER:sa}",
        "spring.datasource.password=${MAILLOOK_TEST_DB_PASSWORD:}",
        "maillook.sync.interval-ms=3600000",
        "maillook.meet.recap-sweep-ms=3600000",
        "maillook.ai.api-key="})
@AutoConfigureMockMvc
class EverydayMailTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("tiru@magizhchi.test", "tiru@magizhchi.test", "pw"));

    static final String ACCOUNT_ID = "2b7c1f0e-5555-4d3a-8b44-000000000041";
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<String> prompts = new CopyOnWriteArrayList<>();
    static final HttpServer pa = start();

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry r) {
        r.add("magizhchi.apps.pa.api", () -> "http://localhost:" + pa.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        pa.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Outbox outbox;

    @Test
    void draftsUndoSendLaterArchiveAllFilesQuickRepliesAndTranslate() throws Exception {
        deliver(withPdf("Fee receipts for October", "Hi sir, the receipts are attached.", "<fees@example.com>", "receipts-october.pdf"));
        deliver(plain("Can we move Saturday's batch to 4 PM?", "Hi sir, can the Saturday batch start at 4 PM instead?", "<batch@example.com>"));
        deliver(plain("சனிக்கிழமை வகுப்பு", "வணக்கம் சார், சனிக்கிழமை வகுப்பு 4 மணிக்கு தொடங்கலாமா?", "<tamil@example.com>"));
        for (int i = 1; i <= 3; i++) deliver(plain("Weekly offers " + i, "This week's offers.", "<offer-" + i + "@example.com>"));

        String token = call(post("/api/auth/sso").contentType(MediaType.APPLICATION_JSON).content(
                "{\"idToken\":\"" + TestIds.idToken(ACCOUNT_ID, "tiru@magizhchi.test", "Tiru Karuna") + "\"}"), null, 200).path("token").asText();
        long accountId = call(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(
                "{\"email\":\"tiru@magizhchi.test\",\"password\":\"pw\",\"imapHost\":\"localhost\",\"imapPort\":3143,\"smtpHost\":\"localhost\",\"smtpPort\":3025}"),
                token, 200).path("id").asLong();
        long fees = waitForMessage(token, "Fee receipts for October");
        long batch = waitForMessage(token, "Can we move Saturday's batch to 4 PM?");
        long tamil = waitForMessage(token, "சனிக்கிழமை வகுப்பு");
        for (int i = 1; i <= 3; i++) waitForMessage(token, "Weekly offers " + i);

        // ── a draft keeps its files
        long draft = call(put("/api/drafts").contentType(MediaType.APPLICATION_JSON).content(
                "{\"accountId\":" + accountId + ",\"mode\":\"new\",\"to\":[\"ravi@example.net\"],\"subject\":\"Timetable\",\"body\":\"Hi Ravi, the timetable is attached.\"}"),
                token, 200).path("id").asLong();
        JsonNode files = call(multipart("/api/drafts/" + draft + "/files")
                .file(new MockMultipartFile("files", "timetable.pdf", "application/pdf", "%PDF-1.4 timetable".getBytes()))
                .file(new MockMultipartFile("files", "notes.txt", "text/plain", "bring the register".getBytes())), token, 200);
        assertThat(files).hasSize(2);
        long notes = files.get(1).path("id").asLong();
        assertThat(call(delete("/api/drafts/" + draft + "/files/" + notes), token, 200)).hasSize(1);
        JsonNode d = call(get("/api/drafts/" + draft), token, 200);
        assertThat(d.path("files").get(0).path("name").asText()).isEqualTo("timetable.pdf");

        // ── Send waits 10 seconds; Undo brings it back as a draft with its file
        String payload = "{\"accountId\":" + accountId + ",\"to\":[\"ravi@example.net\"],\"subject\":\"Timetable\",\"text\":\"Hi Ravi, the timetable is attached.\","
                + "\"mode\":\"new\",\"draftId\":" + draft + ",\"delaySeconds\":10}";
        JsonNode queued = call(multipart("/api/send").file(new MockMultipartFile("payload", "", "application/json", payload.getBytes())), token, 200);
        assertThat(queued.path("queued").asBoolean()).isTrue();
        assertThat(queued.path("scheduled").asBoolean()).isFalse();
        assertThat(queued.path("files").get(0).path("name").asText()).isEqualTo("timetable.pdf");
        call(get("/api/drafts/" + draft), token, 404);                       // the draft became the outgoing mail
        assertThat(outbox.sendDue()).isZero();                              // not yet: still in its undo window
        JsonNode back = call(post("/api/outbox/" + queued.path("id").asLong() + "/undo"), token, 200);
        assertThat(back.path("subject").asText()).isEqualTo("Timetable");
        assertThat(back.path("files").get(0).path("name").asText()).isEqualTo("timetable.pdf");
        call(post("/api/outbox/" + queued.path("id").asLong() + "/undo"), token, 404);

        // ── sent again with no wait: it goes, with the file kept in the draft
        String again = payload.replace("\"draftId\":" + draft, "\"draftId\":" + back.path("id").asLong()).replace("\"delaySeconds\":10", "\"delaySeconds\":0");
        JsonNode q2 = call(multipart("/api/send").file(new MockMultipartFile("payload", "", "application/json", again.getBytes())), token, 200);
        outbox.sendDue();
        MimeMessage out = waitForSent("Timetable");
        assertThat(attachmentNames(out)).containsExactly("timetable.pdf");
        call(post("/api/outbox/" + q2.path("id").asLong() + "/undo"), token, 404);   // gone: nothing to take back
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mail_files", Long.class)).isZero();

        // ── Send later: waits until its time; the time can change; Send now sends it
        String later = "{\"accountId\":" + accountId + ",\"to\":[\"meena@example.org\"],\"subject\":\"Hall on Saturday\",\"text\":\"Hi Meena, is the hall free?\","
                + "\"sendAt\":\"" + Instant.now().plus(2, ChronoUnit.HOURS) + "\"}";
        JsonNode s = call(multipart("/api/send").file(new MockMultipartFile("payload", "", "application/json", later.getBytes())), token, 200);
        assertThat(s.path("scheduled").asBoolean()).isTrue();
        assertThat(call(get("/api/counts"), token, 200).path("scheduled").asLong()).isEqualTo(1);
        assertThat(call(get("/api/outbox"), token, 200)).hasSize(1);
        assertThat(outbox.sendDue()).isZero();
        call(post("/api/outbox/" + s.path("id").asLong() + "/reschedule").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sendAt\":\"" + Instant.now().plusSeconds(10) + "\"}"), token, 400);   // too soon
        JsonNode moved = call(post("/api/outbox/" + s.path("id").asLong() + "/reschedule").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sendAt\":\"" + Instant.now().plus(1, ChronoUnit.DAYS) + "\"}"), token, 200);
        assertThat(Instant.parse(moved.path("sendAt").asText())).isAfter(Instant.now().plus(20, ChronoUnit.HOURS));
        call(post("/api/outbox/" + s.path("id").asLong() + "/now"), token, 200);
        assertThat(outbox.sendDue()).isEqualTo(1);
        waitForSent("Hall on Saturday");
        assertThat(call(get("/api/counts"), token, 200).path("scheduled").asLong()).isZero();
        String bad = later.replace(Instant.now().plus(2, ChronoUnit.HOURS).toString().substring(0, 4), "2099");
        call(multipart("/api/send").file(new MockMultipartFile("payload", "", "application/json", bad.getBytes())), token, 400);   // over a year ahead

        // ── Archive all of Can wait, then Undo
        long offers = jdbc.update("UPDATE messages SET lane = 'can_wait' WHERE subject LIKE 'Weekly offers%'");
        assertThat(offers).isEqualTo(3);
        call(post("/api/messages/bulk/archive-lane").contentType(MediaType.APPLICATION_JSON).content("{\"lane\":\"needs_me\"}"), token, 400);
        JsonNode swept = call(post("/api/messages/bulk/archive-lane").contentType(MediaType.APPLICATION_JSON).content("{\"lane\":\"can_wait\"}"), token, 200);
        assertThat(swept.path("moved").asInt()).isEqualTo(3);
        assertThat(call(get("/api/messages").param("lane", "can_wait"), token, 200).path("items")).isEmpty();
        assertThat(call(get("/api/messages").param("folder", "ARCHIVE"), token, 200).path("items")).hasSize(3);
        JsonNode undo = call(post("/api/messages/bulk/move").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":" + swept.path("ids") + ",\"folder\":\"INBOX\"}"), token, 200);
        assertThat(undo.path("moved").asInt()).isEqualTo(3);
        assertThat(call(get("/api/messages").param("lane", "can_wait"), token, 200).path("items")).hasSize(3);

        // ── Files: every attachment, by kind and by name
        JsonNode all = call(get("/api/files"), token, 200).path("items");
        List<String> names = new java.util.ArrayList<>();
        all.forEach(f -> names.add(f.path("name").asText()));
        assertThat(names).contains("receipts-october.pdf", "timetable.pdf");
        JsonNode pdfs = call(get("/api/files").param("kind", "pdf").param("q", "receipts"), token, 200).path("items");
        assertThat(pdfs).hasSize(1);
        assertThat(pdfs.get(0).path("messageId").asLong()).isEqualTo(fees);
        assertThat(pdfs.get(0).path("kind").asText()).isEqualTo("pdf");
        assertThat(call(get("/api/files").param("kind", "image"), token, 200).path("items")).isEmpty();
        call(get("/api/files").param("kind", "nonsense"), token, 400);

        // ── one-tap replies under mail from a person; none under mail you sent
        JsonNode quick = call(get("/api/ai/quick-replies").param("messageId", String.valueOf(batch)), token, 200).path("replies");
        assertThat(quick).hasSize(3);
        assertThat(quick.get(0).path("body").asText()).isEqualTo("Yes, 4 PM works for me.");
        assertThat(last("one-tap replies")).contains("Saturday batch");
        int before = prompts.size();
        call(get("/api/ai/quick-replies").param("messageId", String.valueOf(batch)), token, 200);
        assertThat(prompts.size()).isEqualTo(before);                         // cached
        long sentId = jdbc.queryForObject("SELECT id FROM messages WHERE folder = 'SENT' AND subject = 'Hall on Saturday'", Long.class);
        assertThat(call(get("/api/ai/quick-replies").param("messageId", String.valueOf(sentId)), token, 200).path("replies")).isEmpty();

        // ── translate a Tamil mail into English
        JsonNode tr = call(post("/api/ai/translate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"messageId\":" + tamil + ",\"to\":\"en\"}"), token, 200);
        assertThat(tr.path("body").asText()).contains("Saturday class");
        assertThat(last("translate an email into natural, simple English")).contains("சனிக்கிழமை");
        call(post("/api/ai/translate").contentType(MediaType.APPLICATION_JSON).content("{\"messageId\":" + tamil + ",\"to\":\"fr\"}"), token, 400);
    }

    // ── fake Magizhchi PA (sign-in and AI) ────────────────────────

    static HttpServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            s.createContext("/", ex -> {
                try {
                    handle(ex);
                } catch (Exception e) {
                    e.printStackTrace();
                    reply(ex, 500, "{}");
                }
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (path.equals("/api/v1/auth/sso")) { reply(ex, 200, "{\"accessToken\":\"pa-1\",\"expiresIn\":900}"); return; }
        if (path.equals("/api/v1/assistant/status")) { reply(ex, 200, "{\"enabled\":true}"); return; }
        if (path.equals("/api/v1/assistant/complete")) {
            JsonNode req = JSON.readTree(body);
            String system = req.path("system").asText();
            prompts.add(system + "\n" + req.path("prompt").asText());
            String text;
            if (system.contains("one-tap replies")) {
                text = "{\"replies\":[{\"label\":\"Yes, 4 PM\",\"body\":\"Yes, 4 PM works for me.\"},"
                        + "{\"label\":\"Ask the room\",\"body\":\"Is the hall free at 4?\"},{\"label\":\"Not this week\",\"body\":\"Let's keep 3 PM this week.\"}]}";
            } else if (system.contains("You translate an email")) {
                text = "{\"subject\":\"Saturday class\",\"body\":\"Hello sir, can the Saturday class start at 4?\",\"from\":\"Tamil\"}";
            } else {
                text = "{\"items\":[]}";
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

    private static String last(String systemPart) {
        for (int i = prompts.size() - 1; i >= 0; i--) if (prompts.get(i).contains(systemPart)) return prompts.get(i);
        throw new AssertionError("No AI call: " + systemPart);
    }

    private static List<String> attachmentNames(MimeMessage m) throws Exception {
        List<String> out = new java.util.ArrayList<>();
        if (m.getContent() instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                var p = mp.getBodyPart(i);
                if (Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition()) || p.getFileName() != null) out.add(p.getFileName());
            }
        }
        return out;
    }

    private static MimeMessage plain(String subject, String text, String id) throws Exception {
        MimeMessage m = message(id);
        m.setFrom(new InternetAddress("Priya <priya@example.com>"));
        m.setSubject(subject, "UTF-8");
        m.setText(text, "UTF-8");
        return m;
    }

    private static MimeMessage withPdf(String subject, String text, String id, String file) throws Exception {
        MimeMessage m = message(id);
        m.setFrom(new InternetAddress("Accounts <accounts@example.com>"));
        m.setSubject(subject, "UTF-8");
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart t = new MimeBodyPart();
        t.setText(text, "UTF-8");
        mixed.addBodyPart(t);
        MimeBodyPart pdf = new MimeBodyPart();
        pdf.setContent("%PDF-1.4 receipts".getBytes(), "application/pdf");
        pdf.setFileName(file);
        pdf.setDisposition(Part.ATTACHMENT);
        mixed.addBodyPart(pdf);
        m.setContent(mixed);
        return m;
    }

    private static MimeMessage message(String id) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties())) {
            @Override
            protected void updateMessageID() throws jakarta.mail.MessagingException {
                setHeader("Message-ID", id);
            }
        };
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse("tiru@magizhchi.test"));
        m.setSentDate(Date.from(Instant.now()));
        return m;
    }

    private static void deliver(MimeMessage m) throws Exception {
        m.saveChanges();
        greenMail.getUserManager().getUserByEmail("tiru@magizhchi.test").deliver(m);
    }

    private MimeMessage waitForSent(String subject) throws Exception {
        for (int i = 0; i < 50; i++) {
            var found = Arrays.stream(greenMail.getReceivedMessages()).filter(x -> {
                try {
                    return subject.equals(x.getSubject()) && x.getRecipients(Message.RecipientType.TO) != null
                            && !"tiru@magizhchi.test".equals(x.getRecipients(Message.RecipientType.TO)[0].toString());
                } catch (Exception e) {
                    return false;
                }
            }).findFirst();
            if (found.isPresent()) return found.get();
            Thread.sleep(100);
        }
        throw new AssertionError("Not sent: " + subject);
    }

    private long waitForMessage(String token, String subject) throws Exception {
        for (int i = 0; i < 100; i++) {
            for (String folder : new String[]{"INBOX"}) {
                for (JsonNode it : call(get("/api/messages").param("folder", folder), token, 200).path("items")) {
                    if (subject.equals(it.path("subject").asText())) return it.path("id").asLong();
                }
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
