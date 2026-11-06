package software.magizhchi.maillook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
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
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Saving attachments to Magizhchi Box, against a stand-in Box that checks requests the way Box does:
 * provisioning needs the shared secret, uploads need the person's mbk_ token, and Box answers 413 for
 * a full account (or lapsed plan) and for a single file that is too big.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${MAILLOOK_TEST_DB_URL:jdbc:h2:mem:box;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${MAILLOOK_TEST_DB_USER:sa}",
        "spring.datasource.password=${MAILLOOK_TEST_DB_PASSWORD:}",
        "maillook.sync.interval-ms=3600000",
        "maillook.ai.api-key=",
        "magizhchi.apps.pa.api=",
        "magizhchi.apps.box.provision-secret=box-provision-secret-for-tests"})
@AutoConfigureMockMvc
class BoxSaveTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("tiru@magizhchi.test", "tiru@magizhchi.test", "pw"));

    static final String ACCOUNT_ID = "2b7c1f0e-3333-4d3a-8b44-000000000003";
    static final String SECRET = "box-provision-secret-for-tests";
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<JsonNode> provisions = new CopyOnWriteArrayList<>();
    static final List<String> uploads = new CopyOnWriteArrayList<>(); // "token|raw multipart body"
    static final AtomicInteger issued = new AtomicInteger();
    static volatile String validToken = null;
    static volatile boolean full = false;
    static final HttpServer box = startFakeBox();

    @DynamicPropertySource
    static void boxUrl(DynamicPropertyRegistry r) {
        r.add("magizhchi.apps.box.api", () -> "http://localhost:" + box.getAddress().getPort());
        r.add("magizhchi.apps.box.web", () -> "http://box.test");
    }

    @AfterAll
    static void stop() {
        box.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void savesAttachmentsToTheirOwnBox() throws Exception {
        deliver("Batch reports", List.of(
                new Att("notes.txt", "text/plain", "Saturday batch notes"),
                new Att("report.pdf", "application/pdf", "%PDF-1.4 fake report"),
                new Att("big.bin", "application/octet-stream", "pretend this is huge")));

        String token = call(post("/api/auth/sso").contentType(MediaType.APPLICATION_JSON).content(
                "{\"idToken\":\"" + TestIds.idToken(ACCOUNT_ID, "tiru@magizhchi.test", "Tiru") + "\"}"), null, 200).path("token").asText();
        call(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(
                "{\"email\":\"tiru@magizhchi.test\",\"password\":\"pw\",\"imapHost\":\"localhost\",\"imapPort\":3143,\"smtpHost\":\"localhost\",\"smtpPort\":3025}"),
                token, 200);
        long msgId = waitForMessage(token, "Batch reports");
        String folder = "Magizhchi MailLook/Attachments/" + YearMonth.now(ZoneId.of("Asia/Kolkata")) + "/";

        // Before the first save: Box is set up on the server, but this person isn't connected yet.
        JsonNode status = call(get("/api/box/status"), token, 200);
        assertThat(status.path("configured").asBoolean()).isTrue();
        assertThat(status.path("connected").asBoolean()).isFalse();
        assertThat(provisions).isEmpty();

        // One tap saves one file, connecting them to their own Box on the way.
        JsonNode one = call(post("/api/messages/" + msgId + "/attachments/0/box"), token, 200);
        assertThat(one.path("status").asText()).isEqualTo("saved");
        assertThat(one.path("message").asText()).contains("notes.txt").contains("Attachments › " + YearMonth.now(ZoneId.of("Asia/Kolkata")));
        assertThat(one.path("webUrl").asText()).isEqualTo("http://box.test/?c=77");
        assertThat(provisions).hasSize(1);
        assertThat(provisions.get(0).path("email").asText()).isEqualTo("tiru@magizhchi.test");
        assertThat(provisions.get(0).path("appName").asText()).isEqualTo("Magizhchi MailLook");
        assertThat(uploads).hasSize(1);
        assertThat(uploads.get(0)).startsWith("mbk_test_1|").contains(folder).contains("filename=\"notes.txt\"").contains("Saturday batch notes");

        // A second tap doesn't upload it again.
        assertThat(call(post("/api/messages/" + msgId + "/attachments/0/box"), token, 200).path("status").asText()).isEqualTo("already");
        assertThat(uploads).hasSize(1);

        // The mail now says which file is in Box.
        JsonNode atts = call(get("/api/messages/" + msgId), token, 200).path("attachments");
        assertThat(atts.get(0).path("inBox").asBoolean()).isTrue();
        assertThat(atts.get(1).path("inBox").asBoolean()).isFalse();

        // Box revoked the token (Connected Apps). Save all reconnects once, saves the PDF, skips the file
        // Box says is too big, and leaves the one already there alone.
        validToken = "revoked";
        JsonNode all = call(post("/api/messages/" + msgId + "/box"), token, 200);
        assertThat(provisions).hasSize(2);
        assertThat(all.path("saved").asInt()).isEqualTo(1);
        assertThat(all.path("skipped").asInt()).isEqualTo(1);
        assertThat(all.path("inBox").toString()).isEqualTo("[0,1]");
        assertThat(all.path("message").asText()).contains("Saved 1 file").contains("1 file too big");
        assertThat(uploads).anyMatch(u -> u.startsWith("mbk_test_2|") && u.contains("filename=\"report.pdf\""));
        assertThat(uploads.stream().filter(u -> u.contains("filename=\"notes.txt\"")).count()).isEqualTo(1);

        // Box is full (or the plan lapsed): a clear 413, nothing recorded, nothing retried.
        deliver("Fee receipt", List.of(new Att("receipt.pdf", "application/pdf", "%PDF receipt")));
        call(post("/api/sync"), token, 202);
        long receiptId = waitForMessage(token, "Fee receipt");
        full = true;
        int before = uploads.size();
        JsonNode refused = call(post("/api/messages/" + receiptId + "/box"), token, 413);
        assertThat(refused.path("error").asText()).contains("full or its plan has ended").contains("Recharge");
        assertThat(uploads).hasSize(before + 1);
        assertThat(call(get("/api/messages/" + receiptId), token, 200).path("attachments").get(0).path("inBox").asBoolean()).isFalse();

        // Space and plan come straight from Box.
        JsonNode connected = call(get("/api/box/status"), token, 200);
        assertThat(connected.path("connected").asBoolean()).isTrue();
        assertThat(connected.path("usage").path("planLabel").asText()).isEqualTo("Basic");
        assertThat(connected.path("usage").path("planState").asText()).isEqualTo("GRACE");
        assertThat(connected.path("usage").path("limitBytes").asLong()).isEqualTo(107374182400L);
        assertThat(connected.path("usage").path("graceEndsAt").asText()).isEqualTo("2026-11-12T19:30:00Z");

        // Other people's mail can't be saved.
        String other = call(post("/api/auth/sso").contentType(MediaType.APPLICATION_JSON).content(
                "{\"idToken\":\"" + TestIds.idToken("2b7c1f0e-3333-4d3a-8b44-000000000004", "someone@magizhchi.test", "Someone") + "\"}"), null, 200).path("token").asText();
        call(post("/api/messages/" + msgId + "/box"), other, 404);
    }

    // ── a stand-in Magizhchi Box ─────────────────────────────────

    record Att(String name, String type, String content) {}

    static HttpServer startFakeBox() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            s.createContext("/", BoxSaveTest::handle);
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
        String key = ex.getRequestHeaders().getFirst("X-Api-Key");
        if (method.equals("POST") && path.equals("/api/provision/workspace")) {
            if (!SECRET.equals(ex.getRequestHeaders().getFirst("X-Provision-Secret"))) {
                reply(ex, 401, "{\"message\":\"Bad provision secret\"}");
                return;
            }
            provisions.add(JSON.readTree(body));
            validToken = "mbk_test_" + issued.incrementAndGet(); // Box revokes the old token with the same label
            reply(ex, 200, "{\"userId\":5,\"email\":\"tiru@magizhchi.test\",\"apiKey\":\"" + validToken + "\",\"conversationId\":77}");
            return;
        }
        if (key == null || !key.equals(validToken)) {
            reply(ex, 401, "{\"message\":\"Invalid API key\"}");
            return;
        }
        if (method.equals("POST") && path.equals("/api/files/send/77")) {
            uploads.add(key + "|" + body);
            if (full) {
                reply(ex, 413, "{\"message\":\"Storage limit exceeded. Upgrade your plan to upload more files.\"}");
            } else if (body.contains("filename=\"big.bin\"")) {
                reply(ex, 413, "{\"message\":\"File exceeds the 500 MB limit\"}");
            } else {
                reply(ex, 201, "{\"id\":" + (1000 + uploads.size()) + ",\"name\":\"x\"}");
            }
            return;
        }
        if (method.equals("GET") && path.equals("/api/storage/usage")) {
            reply(ex, 200, "{\"usedBytes\":104857600,\"limitBytes\":107374182400,\"usedPercent\":0.1,\"planCode\":\"PRO_100\","
                    + "\"planLabel\":\"Basic\",\"planExpiresAt\":\"2026-11-05T00:00:00Z\",\"planState\":\"GRACE\","
                    + "\"graceEndsAt\":\"2026-11-12T19:30:00Z\",\"pendingPayment\":null}");
            return;
        }
        reply(ex, 404, "{\"message\":\"not found\"}");
    }

    static void reply(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    // ── helpers ───────────────────────────────────────────────────

    private void deliver(String subject, List<Att> files) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress("Arun <arun@example.org>"));
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse("tiru@magizhchi.test"));
        m.setSubject(subject);
        MimeMultipart mp = new MimeMultipart();
        MimeBodyPart text = new MimeBodyPart();
        text.setText("Please find the attached files.");
        mp.addBodyPart(text);
        for (Att a : files) {
            MimeBodyPart p = new MimeBodyPart();
            p.setDataHandler(new DataHandler(new ByteArrayDataSource(a.content().getBytes(StandardCharsets.UTF_8), a.type())));
            p.setFileName(a.name());
            p.setDisposition(MimeBodyPart.ATTACHMENT);
            mp.addBodyPart(p);
        }
        m.setContent(mp);
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
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(status);
        String body = res.getContentAsString(StandardCharsets.UTF_8);
        return body.isEmpty() ? json.createObjectNode() : json.readTree(body);
    }
}
