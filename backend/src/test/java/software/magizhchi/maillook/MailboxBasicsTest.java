package software.magizhchi.maillook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Drafts, signatures, search filters, and folders beyond the Inbox (Spam, Trash, the mailbox's own). */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:basics;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "maillook.sync.interval-ms=3600000",
        "magizhchi.apps.pa.api="})
@AutoConfigureMockMvc
class MailboxBasicsTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig()
                    .withUser("owner@magizhchi.test", "owner@magizhchi.test", "pw-owner"));

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private static final String SERVERS = "\"imapHost\":\"localhost\",\"imapPort\":3143,\"smtpHost\":\"localhost\",\"smtpPort\":3025";

    @Test
    void draftsSignaturesFiltersAndFolders() throws Exception {
        try (Store store = imapStore()) {
            Folder inbox = store.getFolder("INBOX");
            append(inbox, "Accounts <accounts@vendor.example>", "Invoice for November", "Please find the invoice attached.", true, false);
            append(inbox, "Ravi <ravi@example.com>", "Lunch on Friday?", "Shall we?", false, true);
            Folder junk = store.getFolder("Junk");
            junk.create(Folder.HOLDS_MESSAGES);
            append(junk, "Prize <win@lottery.example>", "You won a car", "Click here", false, false);
            store.getFolder("Trash").create(Folder.HOLDS_MESSAGES);
            Folder projects = store.getFolder("Projects");
            projects.create(Folder.HOLDS_MESSAGES);
            append(projects, "Meena <meena@client.example>", "Acme brief", "The brief for Acme.", false, false);
        }

        String token = call(post("/api/auth/sso").contentType(MediaType.APPLICATION_JSON).content("{\"idToken\":\"" +
                TestIds.idToken("8d0f7a7e-2222-4c1e-9a55-000000000002", "owner@magizhchi.test", "Owner") + "\"}"), null, 200)
                .path("token").asText();
        long accountId = call(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"owner@magizhchi.test\",\"password\":\"pw-owner\"," + SERVERS + "}"), token, 200).path("id").asLong();
        waitForSync(token);

        // ── Spam is synced and can be read ──
        assertThat(subjects(token, "folder=SPAM")).containsExactly("You won a car");
        assertThat(subjects(token, "folder=TRASH")).isEmpty();

        // ── Search filters: operators in the box, and the chips as parameters ──
        assertThat(subjects(token, "q=" + enc("has:attachment"))).containsExactly("Invoice for November");
        assertThat(subjects(token, "hasAttachment=true")).containsExactly("Invoice for November");
        assertThat(subjects(token, "q=" + enc("is:unread"))).containsExactly("Invoice for November");
        assertThat(subjects(token, "unread=true")).containsExactly("Invoice for November");
        assertThat(subjects(token, "from=ravi")).containsExactly("Lunch on Friday?");
        assertThat(subjects(token, "q=" + enc("from:vendor november"))).containsExactly("Invoice for November");
        assertThat(subjects(token, "q=" + enc("from:vendor lunch"))).isEmpty();
        assertThat(subjects(token, "after=" + Instant.now().plus(1, ChronoUnit.DAYS))).isEmpty();
        assertThat(subjects(token, "q=" + enc("newer_than:7d"))).hasSize(2);
        assertThat(subjects(token, "q=" + enc("older_than:7d"))).isEmpty();
        call(get("/api/messages?after=yesterday"), token, 400);

        // ── The mailbox's own folders: listed, opened, synced from then on ──
        JsonNode folders = call(get("/api/folders"), token, 200).path(0).path("folders");
        List<String> paths = new ArrayList<>();
        folders.forEach(f -> paths.add(f.path("path").asText()));
        assertThat(paths).contains("Projects").doesNotContain("INBOX", "Junk", "Trash");
        call(get(java.net.URI.create("/api/messages?folder=" + enc("F:Projects"))), token, 404);                 // not opened yet
        JsonNode opened = call(post("/api/folders/open").contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":" + accountId + ",\"path\":\"Projects\"}"), token, 200);
        assertThat(opened.path("code").asText()).isEqualTo("F:Projects");
        call(post("/api/folders/open").contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":" + accountId + ",\"path\":\"Nope\"}"), token, 404);
        waitFor(token, "folder=" + enc("F:Projects"), 1);
        assertThat(subjects(token, "folder=" + enc("F:Projects"))).containsExactly("Acme brief");

        // ── Moves: not spam, to a folder, to Trash and back ──
        long spam = first(token, "folder=SPAM");
        call(post("/api/messages/" + spam + "/move").contentType(MediaType.APPLICATION_JSON).content("{\"folder\":\"INBOX\"}"), token, 204);
        assertThat(subjects(token, "folder=SPAM")).isEmpty();
        assertThat(subjects(token, "lane=all")).contains("You won a car");
        assertThat(subjects(token, "lane=fyi")).contains("You won a car");                 // it got a lane on the way in

        long lunch = idOf(token, "lane=all", "Lunch on Friday?");
        call(post("/api/messages/" + lunch + "/move").contentType(MediaType.APPLICATION_JSON).content("{\"folder\":\"F:Projects\"}"), token, 204);
        assertThat(subjects(token, "folder=" + enc("F:Projects"))).containsExactlyInAnyOrder("Acme brief", "Lunch on Friday?");
        call(post("/api/messages/" + lunch + "/move").contentType(MediaType.APPLICATION_JSON).content("{\"folder\":\"SENT\"}"), token, 400);
        call(post("/api/messages/" + lunch + "/move").contentType(MediaType.APPLICATION_JSON).content("{\"folder\":\"F:Gone\"}"), token, 409);

        long invoice = idOf(token, "lane=all", "Invoice for November");
        call(post("/api/messages/" + invoice + "/trash"), token, 204);
        assertThat(subjects(token, "folder=TRASH")).containsExactly("Invoice for November");
        call(post("/api/messages/" + invoice + "/move").contentType(MediaType.APPLICATION_JSON).content("{\"folder\":\"inbox\"}"), token, 204);
        assertThat(subjects(token, "folder=TRASH")).isEmpty();

        // A sync now agrees with what we did (nothing doubles up, nothing comes back).
        call(post("/api/sync"), token, 202);
        waitForSync(token);
        assertThat(subjects(token, "folder=SPAM")).isEmpty();
        assertThat(subjects(token, "folder=" + enc("F:Projects"))).hasSize(2);
        assertThat(subjects(token, "lane=all")).containsExactlyInAnyOrder("You won a car", "Invoice for November");

        // ── Signature ──
        JsonNode acc = call(patch("/api/accounts/" + accountId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"signature\":\"-- \\nOwner\\r\\nMagizhchi Academy\"}"), token, 200);
        assertThat(acc.path("signature").asText()).isEqualTo("Owner\nMagizhchi Academy");
        assertThat(call(get("/api/accounts"), token, 200).path(0).path("signature").asText()).isEqualTo("Owner\nMagizhchi Academy");
        call(patch("/api/accounts/" + accountId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"signature\":\"" + "x".repeat(2001) + "\"}"), token, 400);
        call(patch("/api/accounts/" + accountId).contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Owner\"}"), token, 200);
        assertThat(call(get("/api/accounts"), token, 200).path(0).path("signature").asText()).isEqualTo("Owner\nMagizhchi Academy");
        call(patch("/api/accounts/" + accountId).contentType(MediaType.APPLICATION_JSON).content("{\"signature\":\"  \"}"), token, 200);
        assertThat(call(get("/api/accounts"), token, 200).path(0).path("signature").isNull()).isTrue();

        // ── Royal signature: a design and its banner; the plain copy is made from the same details ──
        var banner = new java.awt.image.BufferedImage(1560, 450, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var pngOut = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(banner, "png", pngOut);
        String design = "{\"template\":\"gold-crest\",\"name\":\"Owner Name\",\"title\":\"Founder\",\"company\":\"Magizhchi Academy\","
                + "\"phone\":\"+91 98400 12345\",\"email\":\"owner@magizhchi.test\",\"website\":\"magizhchi.software\",\"tagline\":\"Learn with joy\"}";
        call(multipart(org.springframework.http.HttpMethod.PUT, "/api/accounts/" + accountId + "/signature-design")
                .file(new MockMultipartFile("image", "sig.png", "image/png", "not a png".getBytes()))
                .param("design", design), token, 400);
        call(multipart(org.springframework.http.HttpMethod.PUT, "/api/accounts/" + accountId + "/signature-design")
                .file(new MockMultipartFile("image", "sig.png", "image/png", pngOut.toByteArray()))
                .param("design", design.replace("gold-crest", "rainbow")), token, 400);
        JsonNode royal = call(multipart(org.springframework.http.HttpMethod.PUT, "/api/accounts/" + accountId + "/signature-design")
                .file(new MockMultipartFile("image", "sig.png", "image/png", pngOut.toByteArray()))
                .param("design", design), token, 200);
        assertThat(royal.path("signature").asText())
                .isEqualTo("Owner Name\nFounder, Magizhchi Academy\n+91 98400 12345 · owner@magizhchi.test · magizhchi.software\nLearn with joy");
        assertThat(royal.path("signatureDesign").path("template").asText()).isEqualTo("gold-crest");
        assertThat(royal.path("signatureImage").path("width").asInt()).isEqualTo(520);
        String picture = royal.path("signatureImage").path("url").asText();
        assertThat(picture).contains("/api/sig/");
        assertThat(royal.path("signatureHtml").asText()).contains(picture).contains("tel:+919840012345").contains("https://magizhchi.software");
        assertThat(call(get("/api/accounts"), token, 200).path(0).path("signatureDesign").path("name").asText()).isEqualTo("Owner Name");
        // Anyone reading the mail can load the picture, without signing in.
        var pic = mvc.perform(get(java.net.URI.create(picture).getPath())).andReturn().getResponse();
        assertThat(pic.getStatus()).isEqualTo(200);
        assertThat(pic.getContentType()).isEqualTo("image/png");
        assertThat(pic.getContentAsByteArray()).isEqualTo(pngOut.toByteArray());
        assertThat(mvc.perform(get("/api/sig/0123456789abcdef0123456789abcdef")).andReturn().getResponse().getStatus()).isEqualTo(404);

        // Sent mail carries the designed signature as HTML, and its text copy in the plain part.
        String sigText = royal.path("signature").asText();
        String royalPayload = json.writeValueAsString(java.util.Map.of("accountId", accountId, "to", List.of("owner@magizhchi.test"),
                "subject", "Royal hello", "text", "Hello there,\n\n-- \n" + sigText));
        call(multipart("/api/send").file(new MockMultipartFile("payload", "", "application/json", royalPayload.getBytes(StandardCharsets.UTF_8))), token, 200);
        MimeMessage royalMail = java.util.Arrays.stream(greenMail.getReceivedMessages()).filter(m -> {
            try { return "Royal hello".equals(m.getSubject()); } catch (Exception e) { return false; }
        }).findFirst().orElseThrow();
        var rawOut = new java.io.ByteArrayOutputStream();
        royalMail.writeTo(rawOut);
        String whole = rawOut.toString(StandardCharsets.UTF_8);
        assertThat(whole).contains("text/html").contains("text/plain");
        assertThat(whole.replaceAll("=\\r?\\n", "").replace("=3D", "=")).contains("/api/sig/" + picture.substring(picture.lastIndexOf('/') + 1));

        // A photo and a logo, a layout, WhatsApp, social handles and a booking link.
        var face = new java.awt.image.BufferedImage(400, 400, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var faceOut = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(face, "jpg", faceOut);
        String rich = design.replace("\"template\":\"gold-crest\"", "\"template\":\"peacock-royal\",\"layout\":\"card\","
                + "\"photoCrop\":{\"x\":0.2,\"y\":-0.1,\"zoom\":1.5},\"whatsapp\":\"+91 98400 12345\",\"linkedin\":\"@owner-name\","
                + "\"instagram\":\"https://instagram.com/magizhchi\",\"booking\":\"meet.magizhchi.software/book/owner\"");
        JsonNode withArt = call(multipart(org.springframework.http.HttpMethod.PUT, "/api/accounts/" + accountId + "/signature-design")
                .file(new MockMultipartFile("image", "sig.png", "image/png", pngOut.toByteArray()))
                .file(new MockMultipartFile("photo", "me.jpg", "image/jpeg", faceOut.toByteArray()))
                .file(new MockMultipartFile("logo", "logo.png", "image/png", pngOut.toByteArray()))
                .param("design", rich), token, 200);
        JsonNode rd = withArt.path("signatureDesign");
        assertThat(rd.path("layout").asText()).isEqualTo("card");
        assertThat(rd.path("photo").asText()).hasSize(32);
        assertThat(rd.path("logo").asText()).hasSize(32);
        assertThat(rd.path("photoCrop").path("zoom").asDouble()).isEqualTo(1.5);
        assertThat(rd.path("linkedin").asText()).isEqualTo("https://www.linkedin.com/in/owner-name");
        assertThat(rd.path("booking").asText()).isEqualTo("https://meet.magizhchi.software/book/owner");
        String richHtml = withArt.path("signatureHtml").asText();
        assertThat(richHtml).contains("https://wa.me/919840012345").contains("/api/sig/icon/linkedin/1f8a8a")
                .contains("https://instagram.com/magizhchi").contains("Book a meeting");
        assertThat(withArt.path("signature").asText()).contains("Book a meeting: https://meet.magizhchi.software/book/owner");
        // The photo comes back for the designer; the icons load without signing in.
        assertThat(mvc.perform(get("/api/sig/" + rd.path("photo").asText())).andReturn().getResponse().getContentAsByteArray())
                .isEqualTo(faceOut.toByteArray());
        var iconRes = mvc.perform(get("/api/sig/icon/linkedin/1f8a8a")).andReturn().getResponse();
        assertThat(iconRes.getStatus()).isEqualTo(200);
        assertThat(iconRes.getContentType()).isEqualTo("image/png");
        assertThat(mvc.perform(get("/api/sig/icon/nope/1f8a8a")).andReturn().getResponse().getStatus()).isEqualTo(404);
        // Saving again without new uploads keeps the same photo and logo; a token from elsewhere is dropped.
        String resave = json.writeValueAsString(((com.fasterxml.jackson.databind.node.ObjectNode) rd.deepCopy()).put("tagline", "Still here"));
        JsonNode kept = call(multipart(org.springframework.http.HttpMethod.PUT, "/api/accounts/" + accountId + "/signature-design")
                .file(new MockMultipartFile("image", "sig.png", "image/png", pngOut.toByteArray()))
                .param("design", resave), token, 200);
        assertThat(kept.path("signatureDesign").path("photo").asText()).isEqualTo(rd.path("photo").asText());
        assertThat(kept.path("signatureDesign").path("logo").asText()).isEqualTo(rd.path("logo").asText());
        String forged = resave.replace(rd.path("logo").asText(), "0123456789abcdef0123456789abcdef");
        JsonNode dropped = call(multipart(org.springframework.http.HttpMethod.PUT, "/api/accounts/" + accountId + "/signature-design")
                .file(new MockMultipartFile("image", "sig.png", "image/png", pngOut.toByteArray()))
                .param("design", forged), token, 200);
        assertThat(dropped.path("signatureDesign").has("logo")).isFalse();
        call(multipart(org.springframework.http.HttpMethod.PUT, "/api/accounts/" + accountId + "/signature-design")
                .file(new MockMultipartFile("image", "sig.png", "image/png", pngOut.toByteArray()))
                .param("design", design.replace("\"template\":\"gold-crest\"", "\"template\":\"gold-crest\",\"layout\":\"poster\"")), token, 400);

        // Back to plain text: the design goes, its text copy stays.
        JsonNode plainAgain = call(delete("/api/accounts/" + accountId + "/signature-design"), token, 200);
        assertThat(plainAgain.path("signatureDesign").isNull()).isTrue();
        assertThat(plainAgain.path("signature").asText()).isEqualTo(dropped.path("signature").asText());

        // Typing a signature again replaces the design.
        call(multipart(org.springframework.http.HttpMethod.PUT, "/api/accounts/" + accountId + "/signature-design")
                .file(new MockMultipartFile("image", "sig.png", "image/png", pngOut.toByteArray()))
                .param("design", design), token, 200);
        call(patch("/api/accounts/" + accountId).contentType(MediaType.APPLICATION_JSON).content("{\"signature\":\"Owner\"}"), token, 200);
        assertThat(call(get("/api/accounts"), token, 200).path(0).path("signatureDesign").isNull()).isTrue();
        call(patch("/api/accounts/" + accountId).contentType(MediaType.APPLICATION_JSON).content("{\"signature\":\"  \"}"), token, 200);

        // ── Drafts: saved, saved again, listed, sent, gone ──
        JsonNode saved = call(put("/api/drafts").contentType(MediaType.APPLICATION_JSON).content(
                "{\"accountId\":" + accountId + ",\"to\":[\"priya@example.com\"],\"subject\":\"Batch timings\",\"body\":\"Hi Priya,\"}"), token, 200);
        long draftId = saved.path("id").asLong();
        assertThat(saved.path("savedAt").asText()).isNotBlank();
        JsonNode again = call(put("/api/drafts").contentType(MediaType.APPLICATION_JSON).content("{\"id\":" + draftId + ",\"accountId\":" + accountId +
                ",\"to\":[\"priya@example.com\",\"ravi@example.com\"],\"subject\":\"Batch timings\",\"body\":\"Hi Priya, Saturday at 6.\"}"), token, 200);
        assertThat(again.path("id").asLong()).isEqualTo(draftId);
        JsonNode drafts = call(get("/api/drafts"), token, 200);
        assertThat(drafts.size()).isEqualTo(1);
        assertThat(drafts.path(0).path("body").asText()).isEqualTo("Hi Priya, Saturday at 6.");
        assertThat(drafts.path(0).path("to").size()).isEqualTo(2);
        call(put("/api/drafts").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"poem\"}"), token, 400);
        call(put("/api/drafts").contentType(MediaType.APPLICATION_JSON).content("{\"accountId\":999999}"), token, 404);

        String payload = "{\"accountId\":" + accountId + ",\"to\":[\"priya@example.com\"],\"subject\":\"Batch timings\"," +
                "\"text\":\"Hi Priya, Saturday at 6.\",\"draftId\":" + draftId + "}";
        call(multipart("/api/send").file(new MockMultipartFile("payload", "", "application/json", payload.getBytes())), token, 200);
        assertThat(call(get("/api/drafts"), token, 200).size()).isZero();

        // Discarding one.
        long other = call(put("/api/drafts").contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"half a thought\"}"), token, 200).path("id").asLong();
        call(delete("/api/drafts/" + other), token, 204);
        call(get("/api/drafts/" + other), token, 404);
    }

    // ── helpers ───────────────────────────────────────────────────

    private static Store imapStore() throws Exception {
        Store s = Session.getInstance(new Properties()).getStore("imap");
        s.connect("localhost", 3143, "owner@magizhchi.test", "pw-owner");
        return s;
    }

    private static void append(Folder folder, String from, String subject, String body, boolean attachment, boolean seen) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress(from));
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse("owner@magizhchi.test"));
        m.setSubject(subject);
        if (attachment) {
            MimeBodyPart text = new MimeBodyPart();
            text.setText(body);
            MimeBodyPart file = new MimeBodyPart();
            file.setContent("Total: Rs 4,000", "text/plain");
            file.setFileName("invoice.txt");
            file.setDisposition(jakarta.mail.Part.ATTACHMENT);
            m.setContent(new MimeMultipart(text, file));
        } else {
            m.setText(body);
        }
        m.setSentDate(new Date());
        m.saveChanges();
        if (seen) m.setFlag(Flags.Flag.SEEN, true);
        folder.appendMessages(new Message[]{m});
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private JsonNode call(MockHttpServletRequestBuilder req, String token, int status) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        var res = mvc.perform(req).andReturn().getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(status);
        String body = res.getContentAsString(StandardCharsets.UTF_8);
        return body.isEmpty() ? json.createObjectNode() : json.readTree(body);
    }

    private JsonNode items(String token, String query) throws Exception {
        return call(get(java.net.URI.create("/api/messages?" + query)), token, 200).path("items");
    }

    private List<String> subjects(String token, String query) throws Exception {
        List<String> out = new ArrayList<>();
        items(token, query).forEach(n -> out.add(n.path("subject").asText()));
        return out;
    }

    private long first(String token, String query) throws Exception {
        return items(token, query).path(0).path("id").asLong();
    }

    private long idOf(String token, String query, String subject) throws Exception {
        for (JsonNode n : items(token, query)) if (subject.equals(n.path("subject").asText())) return n.path("id").asLong();
        throw new AssertionError("No message " + subject);
    }

    private void waitForSync(String token) throws Exception {
        for (int i = 0; i < 100; i++) {
            JsonNode a = call(get("/api/accounts"), token, 200).path(0);
            if ("ok".equals(a.path("status").asText())) return;
            if ("error".equals(a.path("status").asText())) throw new AssertionError("Sync failed: " + a.path("lastError").asText());
            Thread.sleep(100);
        }
        throw new AssertionError("Sync didn't finish");
    }

    private void waitFor(String token, String query, int n) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (items(token, query).size() >= n) return;
            if (i % 10 == 0) call(post("/api/sync"), token, 202);
            Thread.sleep(100);
        }
        throw new AssertionError("Nothing arrived in " + query);
    }
}
