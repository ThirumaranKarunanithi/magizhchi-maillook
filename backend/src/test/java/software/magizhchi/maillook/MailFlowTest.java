package software.magizhchi.maillook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Arrays;
import java.util.Date;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * End to end against a real (in-memory) IMAP + SMTP server: sign in, sync, lanes, thread, reply,
 * Sent copy, archive, sender rules, unsubscribe, bulk add, incremental sync.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${MAILLOOK_TEST_DB_URL:jdbc:h2:mem:flow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${MAILLOOK_TEST_DB_USER:sa}",
        "spring.datasource.password=${MAILLOOK_TEST_DB_PASSWORD:}",
        "maillook.sync.interval-ms=3600000",
        "magizhchi.apps.pa.api="})
@AutoConfigureMockMvc
class MailFlowTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig()
                    .withUser("owner@magizhchi.test", "owner@magizhchi.test", "pw-owner")
                    .withUser("info@magizhchi.test", "info@magizhchi.test", "pw-info"));

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private static final String SERVERS = "\"imapHost\":\"localhost\",\"imapPort\":3143,\"smtpHost\":\"localhost\",\"smtpPort\":3025";

    @Test
    void fullFlow() throws Exception {
        deliver("owner@magizhchi.test", "Priya <priya@example.com>", "owner@magizhchi.test", null, "Can we move the batch to 6pm?",
                "Hi, can we move Saturday's batch to 6pm? Please confirm.", "<q1@example.com>", null);
        deliver("owner@magizhchi.test", "Deals <deals@news.example.com>", "owner@magizhchi.test", null, "50% off this week",
                "Big sale", "<n1@news.example.com>", "<mailto:unsub@news.example.com?subject=stop>");
        deliver("owner@magizhchi.test", "Bank <noreply@bank.example.com>", "owner@magizhchi.test", null, "Debit alert",
                "Rs 500 debited", "<b1@bank.example.com>", null);
        deliver("owner@magizhchi.test", "Ravi <ravi@example.com>", "team@example.com", "owner@magizhchi.test", "Team update",
                "FYI the lab is closed Monday", "<c1@example.com>", null);

        // Sign in with Magizhchi ID (a token signed with the suite secret, as ID itself signs it).
        String idToken = TestIds.idToken("8d0f7a7e-1111-4c1e-9a55-000000000001", "owner@magizhchi.test", "Owner");
        JsonNode login = call(post("/api/auth/sso").contentType(MediaType.APPLICATION_JSON)
                .content("{\"idToken\":\"" + idToken + "\"}"), null, 200);
        String token = login.path("token").asText();
        assertThat(token).isNotBlank();

        // A token signed with any other secret is refused.
        call(post("/api/auth/sso").contentType(MediaType.APPLICATION_JSON)
                .content("{\"idToken\":\"" + TestIds.forgedToken("owner@magizhchi.test") + "\"}"), null, 401);

        // Then add the mailbox (its own password checked live over IMAP); a wrong password is refused.
        call(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"owner@magizhchi.test\",\"password\":\"nope\"," + SERVERS + "}"), token, 422);   // wrong mailbox password: not a MailLook sign-out
        call(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"owner@magizhchi.test\",\"password\":\"pw-owner\"," + SERVERS + "}"), token, 200);

        waitForSync(token, 1);

        assertThat(subjects(token, "needs_me")).containsExactly("Can we move the batch to 6pm?");
        assertThat(subjects(token, "can_wait")).containsExactly("50% off this week");
        assertThat(subjects(token, "fyi")).containsExactlyInAnyOrder("Debit alert", "Team update");

        JsonNode counts = call(get("/api/counts"), token, 200);
        assertThat(counts.at("/lanes/needs_me/unread").asInt()).isEqualTo(1);
        assertThat(counts.at("/happiness/score").asInt()).isBetween(0, 100);

        // Read the message, reply to it.
        long priyaId = list(token, "needs_me").path(0).path("id").asLong();
        JsonNode detail = call(get("/api/messages/" + priyaId), token, 200);
        assertThat(detail.path("bodyText").asText()).contains("6pm");
        assertThat(detail.path("thread").size()).isEqualTo(1);
        call(post("/api/messages/" + priyaId + "/seen").contentType(MediaType.APPLICATION_JSON).content("{\"seen\":true}"), token, 204);

        String payload = "{\"accountId\":" + detail.path("accountId").asLong() + ",\"to\":[\"priya@example.com\"],\"subject\":\"Re: Can we move the batch to 6pm?\"," +
                "\"text\":\"Yes, 6pm works.\",\"replyToMessageId\":" + priyaId + "}";
        call(multipart("/api/send")
                .file(new MockMultipartFile("payload", "", "application/json", payload.getBytes()))
                .file(new MockMultipartFile("files", "notes.txt", "text/plain", "hello".getBytes())), token, 200);

        MimeMessage out = Arrays.stream(greenMail.getReceivedMessages())
                .filter(m -> {
                    try { return "Re: Can we move the batch to 6pm?".equals(m.getSubject()); } catch (Exception e) { return false; }
                }).findFirst().orElseThrow();
        assertThat(out.getHeader("In-Reply-To")[0]).isEqualTo("<q1@example.com>");

        JsonNode sent = call(get("/api/messages?folder=SENT"), token, 200).path("items");
        assertThat(sent.size()).isEqualTo(1);
        detail = call(get("/api/messages/" + priyaId), token, 200);
        assertThat(detail.path("thread").size()).isEqualTo(2);

        // Done = archive: it leaves the lane and the server's INBOX.
        call(post("/api/messages/" + priyaId + "/archive"), token, 204);
        assertThat(subjects(token, "needs_me")).isEmpty();
        assertThat(call(get("/api/messages?folder=ARCHIVE"), token, 200).path("items").size()).isEqualTo(1);

        // Teach a sender rule.
        long bankId = findId(token, "fyi", "Debit alert");
        call(post("/api/messages/" + bankId + "/lane").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lane\":\"needs_me\",\"rememberSender\":true}"), token, 204);
        assertThat(subjects(token, "needs_me")).containsExactly("Debit alert");

        // Unsubscribe via mailto sends the request mail.
        JsonNode subs = call(get("/api/subscriptions"), token, 200);
        assertThat(subs.size()).isEqualTo(1);
        JsonNode un = call(post("/api/messages/" + subs.path(0).path("latestMessageId").asLong() + "/unsubscribe"), token, 200);
        assertThat(un.path("status").asText()).isEqualTo("done");
        assertThat(Arrays.stream(greenMail.getReceivedMessages()).anyMatch(m -> {
            try { return "stop".equals(m.getSubject()); } catch (Exception e) { return false; }
        })).isTrue();
        assertThat(call(get("/api/subscriptions"), token, 200).size()).isZero();

        // Bulk add a second mailbox (one good, one bad password).
        JsonNode bulk = call(post("/api/accounts/bulk").contentType(MediaType.APPLICATION_JSON).content(
                "{\"accounts\":[{\"email\":\"info@magizhchi.test\",\"password\":\"pw-info\"," + SERVERS + "}," +
                        "{\"email\":\"nobody@magizhchi.test\",\"password\":\"x\"," + SERVERS + "}]}"), token, 200);
        assertThat(bulk.at("/results/0/ok").asBoolean()).isTrue();
        assertThat(bulk.at("/results/1/ok").asBoolean()).isFalse();
        assertThat(call(get("/api/accounts"), token, 200).size()).isEqualTo(2);

        // Incremental sync picks up only the new mail.
        deliver("owner@magizhchi.test", "Priya <priya@example.com>", "owner@magizhchi.test", null, "Re: Can we move the batch to 6pm?",
                "Thanks!", "<q2@example.com>", null, "<q1@example.com>");
        call(post("/api/sync"), token, 202);
        waitForCount(token, 4); // 3 left in INBOX after the archive + 1 new
        JsonNode all = list(token, "all");
        assertThat(all.size()).isEqualTo(4);
        long thanks = findId(token, "all", "Re: Can we move the batch to 6pm?");
        assertThat(call(get("/api/messages/" + thanks), token, 200).path("thread").size()).isEqualTo(3);
    }

    // ── helpers ───────────────────────────────────────────────────

    private void deliver(String mailbox, String from, String to, String cc, String subject, String body,
                         String messageId, String listUnsubscribe, String... inReplyTo) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties())) {
            @Override
            protected void updateMessageID() throws jakarta.mail.MessagingException {
                setHeader("Message-ID", messageId);
            }
        };
        m.setFrom(new InternetAddress(from));
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        if (cc != null) m.setRecipients(Message.RecipientType.CC, InternetAddress.parse(cc));
        m.setSubject(subject);
        m.setText(body);
        m.setSentDate(new Date());
        if (listUnsubscribe != null) m.setHeader("List-Unsubscribe", listUnsubscribe);
        if (inReplyTo.length > 0) {
            m.setHeader("In-Reply-To", inReplyTo[0]);
            m.setHeader("References", inReplyTo[0]);
        }
        m.saveChanges();
        greenMail.getUserManager().getUserByEmail(mailbox).deliver(m);
    }

    private JsonNode call(MockHttpServletRequestBuilder req, String token, int status) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        var res = mvc.perform(req).andReturn().getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(status);
        String body = res.getContentAsString();
        return body.isEmpty() ? json.createObjectNode() : json.readTree(body);
    }

    private JsonNode list(String token, String lane) throws Exception {
        return call(get("/api/messages?lane=" + lane), token, 200).path("items");
    }

    private java.util.List<String> subjects(String token, String lane) throws Exception {
        java.util.List<String> out = new java.util.ArrayList<>();
        list(token, lane).forEach(n -> out.add(n.path("subject").asText()));
        return out;
    }

    private long findId(String token, String lane, String subject) throws Exception {
        for (JsonNode n : list(token, lane)) {
            if (subject.equals(n.path("subject").asText()) && n.path("folder").asText().equals("INBOX")
                    && !n.path("fromEmail").asText().equals("owner@magizhchi.test")) {
                if (n.path("snippet").asText().equals("Thanks!") || !subject.startsWith("Re:")) return n.path("id").asLong();
            }
        }
        throw new AssertionError("No message " + subject);
    }

    private void waitForSync(String token, int accounts) throws Exception {
        for (int i = 0; i < 100; i++) {
            JsonNode list = call(get("/api/accounts"), token, 200);
            long done = 0;
            for (JsonNode a : list) if ("ok".equals(a.path("status").asText())) done++;
            if (done >= accounts) return;
            if (list.size() > 0 && "error".equals(list.path(0).path("status").asText())) {
                throw new AssertionError("Sync failed: " + list.path(0).path("lastError").asText());
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Sync didn't finish");
    }

    private void waitForCount(String token, int n) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (list(token, "all").size() >= n) return;
            Thread.sleep(100);
        }
    }
}
