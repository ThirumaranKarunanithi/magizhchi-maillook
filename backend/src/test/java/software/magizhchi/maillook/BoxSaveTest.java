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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
    static final List<String[]> stored = new CopyOnWriteArrayList<>(); // [id, name]
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
    @Autowired software.magizhchi.maillook.suite.BoxAutoSave autoSave;

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

        // Searching inside documents goes to Box's own content search; saved files lead back to their mail.
        JsonNode inside = call(get("/api/files/inside").param("q", "inheritance deed"), token, 200);
        assertThat(inside.path("connected").asBoolean()).isTrue();
        assertThat(inside.path("enabled").asBoolean()).isTrue();
        assertThat(inside.path("terms").toString()).isEqualTo("[\"inheritance\",\"deed\"]");
        JsonNode hits = inside.path("results");
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).path("name").asText()).isEqualTo("notes.txt");
        assertThat(hits.get(0).path("messageId").asLong()).isEqualTo(msgId);
        assertThat(hits.get(0).path("index").asInt()).isEqualTo(0);
        assertThat(hits.get(0).path("matches").get(0).path("snippet").asText()).contains("inheritance deed");
        assertThat(hits.get(1).path("name").asText()).isEqualTo("Will 2019.pdf");
        assertThat(hits.get(1).path("kind").asText()).isEqualTo("pdf");
        assertThat(hits.get(1).has("messageId")).isFalse();
        assertThat(call(get("/api/files/box/5555/open"), token, 200).path("url").asText()).isEqualTo("https://s3.test/will.pdf?sig=1");
        assertThat(call(get("/api/files/inside").param("q", "x"), token, 200).path("results")).isEmpty();

        // Attachments live in Box by default: the automatic save puts the rest there by itself, skips the
        // file Box calls too big, and has nothing left to do on the next round.
        full = false;
        JsonNode st = call(get("/api/box/status"), token, 200);
        assertThat(st.path("filesStore").asText()).isEqualTo("box");
        assertThat(st.path("autoSave").path("backlog").asInt()).isEqualTo(2);
        int beforeAuto = uploads.size();
        assertThat(autoSave.runOnce()).isEqualTo(1);
        assertThat(uploads.subList(beforeAuto, uploads.size())).anyMatch(u -> u.contains("filename=\"receipt.pdf\""));
        assertThat(uploads.subList(beforeAuto, uploads.size())).anyMatch(u -> u.contains("filename=\"big.bin\""));
        assertThat(autoSave.runOnce()).isZero();
        assertThat(call(get("/api/box/status"), token, 200).path("autoSave").path("backlog").asInt()).isZero();
        assertThat(call(get("/api/messages/" + receiptId), token, 200).path("attachments").get(0).path("inBox").asBoolean()).isTrue();

        // The Files page now comes from Box, each file tied back to its mail; names are searched in Box.
        JsonNode files = call(get("/api/files"), token, 200);
        assertThat(files.path("source").asText()).isEqualTo("box");
        List<String> boxNames = new java.util.ArrayList<>();
        files.path("items").forEach(f -> boxNames.add(f.path("name").asText()));
        assertThat(boxNames).contains("notes.txt", "report.pdf", "receipt.pdf").doesNotContain("big.bin");
        JsonNode receiptItem = null;
        for (JsonNode f : files.path("items")) if (f.path("name").asText().equals("receipt.pdf")) receiptItem = f;
        assertThat(receiptItem.path("messageId").asLong()).isEqualTo(receiptId);
        assertThat(call(get("/api/files").param("kind", "pdf"), token, 200).path("items")).hasSize(2);
        JsonNode named = call(get("/api/files").param("q", "notes"), token, 200);
        assertThat(named.path("source").asText()).isEqualTo("box");
        assertThat(named.path("items")).hasSize(1);
        assertThat(call(get("/api/files/box/" + receiptItem.path("boxFileId").asLong() + "/download"), token, 200).path("url").asText())
                .startsWith("https://s3.test/");

        // Kept on this computer instead: the list is the mail's own again, and nothing more goes to Box.
        assertThat(call(put("/api/box/files-store").contentType(MediaType.APPLICATION_JSON).content("{\"store\":\"local\"}"), token, 200)
                .path("filesStore").asText()).isEqualTo("local");
        assertThat(call(get("/api/files"), token, 200).path("source").asText()).isEqualTo("mail");
        call(put("/api/box/files-store").contentType(MediaType.APPLICATION_JSON).content("{\"store\":\"cloud\"}"), token, 400);

        // Other people's mail can't be saved.
        String other = call(post("/api/auth/sso").contentType(MediaType.APPLICATION_JSON).content(
                "{\"idToken\":\"" + TestIds.idToken("2b7c1f0e-3333-4d3a-8b44-000000000004", "someone@magizhchi.test", "Someone") + "\"}"), null, 200).path("token").asText();
        call(post("/api/messages/" + msgId + "/box"), other, 404);
        // Someone without Box gets no results and no call to Box.
        assertThat(call(get("/api/files/inside").param("q", "inheritance"), other, 200).path("connected").asBoolean()).isFalse();
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
                java.util.regex.Matcher fn = java.util.regex.Pattern.compile("filename=\"([^\"]+)\"").matcher(body);
                stored.add(new String[]{String.valueOf(1000 + uploads.size()), fn.find() ? fn.group(1) : "x"});
                reply(ex, 201, "{\"id\":" + (1000 + uploads.size()) + ",\"name\":\"x\"}");
            }
            return;
        }
        if (method.equals("GET") && path.equals("/api/conversations/77/content-search")) {
            String q = java.net.URLDecoder.decode(ex.getRequestURI().getRawQuery().replaceFirst("^q=", ""), StandardCharsets.UTF_8);
            reply(ex, 200, "{\"mode\":\"EVERYONE\",\"enabled\":true,\"pending\":0,\"terms\":" + JSON.writeValueAsString(List.of(q.split(" ")))
                    + ",\"results\":[{\"file\":{\"id\":1001,\"originalFileName\":\"notes.txt\",\"contentType\":\"text/plain\",\"fileSizeBytes\":20,\"folderPath\":\"Attachments/\",\"sentAt\":\"2026-11-07T10:00:00Z\"},"
                    + "\"matches\":[{\"page\":1,\"kind\":\"text\",\"snippet\":\"the inheritance deed for the house\"}]},"
                    + "{\"file\":{\"id\":5555,\"originalFileName\":\"Will 2019.pdf\",\"contentType\":\"application/pdf\",\"fileSizeBytes\":90000,\"folderPath\":\"Family/\",\"sentAt\":\"2026-01-02T10:00:00Z\"},"
                    + "\"matches\":[{\"page\":3,\"kind\":\"page\",\"snippet\":\"by this inheritance deed I leave\"}]}]}");
            return;
        }
        if (method.equals("GET") && path.equals("/api/conversations/77/files")) {
            StringBuilder c = new StringBuilder();
            for (int i = stored.size() - 1; i >= 0; i--) {
                String[] f = stored.get(i);
                String type = f[1].endsWith(".pdf") ? "application/pdf" : "text/plain";
                if (c.length() > 0) c.append(',');
                c.append("{\"id\":").append(f[0]).append(",\"conversationId\":77,\"originalFileName\":\"").append(f[1])
                        .append("\",\"contentType\":\"").append(type).append("\",\"fileSizeBytes\":12,\"folderPath\":\"Magizhchi MailLook/Attachments/2026-11/\",\"sentAt\":\"2026-11-08T05:00:00Z\"}");
            }
            reply(ex, 200, "{\"content\":[" + c + "],\"last\":true,\"number\":0}");
            return;
        }
        if (method.equals("GET") && path.equals("/api/files/search")) {
            String q = java.net.URLDecoder.decode(ex.getRequestURI().getRawQuery().replaceFirst("^q=", ""), StandardCharsets.UTF_8);
            StringBuilder c = new StringBuilder();
            for (String[] f : stored) {
                if (!f[1].contains(q)) continue;
                if (c.length() > 0) c.append(',');
                c.append("{\"id\":").append(f[0]).append(",\"conversationId\":77,\"originalFileName\":\"").append(f[1]).append("\",\"contentType\":\"text/plain\",\"fileSizeBytes\":12}");
            }
            c.append(c.length() > 0 ? "," : "").append("{\"id\":9,\"conversationId\":12,\"originalFileName\":\"notes-in-a-group.txt\"}");
            reply(ex, 200, "[" + c + "]");
            return;
        }
        if (method.equals("GET") && path.matches("/api/files/\\d+/download-url")) {
            reply(ex, 200, "{\"url\":\"https://s3.test/f?sig=2\"}");
            return;
        }
        if (method.equals("GET") && path.equals("/api/files/5555/preview-url")) {
            reply(ex, 200, "{\"url\":\"https://s3.test/will.pdf?sig=1\"}");
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
