package software.magizhchi.maillook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Search finds mail older than what sync keeps: the mailbox keeps only its newest 3 here, the principal's
 * mail is older, and searching "principal" brings it in from the server (as Gmail would find it).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${MAILLOOK_TEST_DB_URL:jdbc:h2:mem:serversearch;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${MAILLOOK_TEST_DB_USER:sa}",
        "spring.datasource.password=${MAILLOOK_TEST_DB_PASSWORD:}",
        "maillook.sync.interval-ms=3600000",
        "maillook.sync.initial-inbox=3",
        "maillook.meet.recap-sweep-ms=3600000",
        "maillook.ai.api-key="})
@AutoConfigureMockMvc
class ServerSearchTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("karni@magizhchi.test", "karni@magizhchi.test", "pw"));

    static final String ACCOUNT_ID = "2b7c1f0e-5555-4d3a-8b44-000000000077";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void searchReachesMailOlderThanWhatSyncKeeps() throws Exception {
        deliver(mail("Principal csi <principal.csi@csacoimbatore.com>", "Joining Confirmation - Student ID S2605375", "Welcome to the school.", 1));
        deliver(mail("Principal csi <principal.csi@csacoimbatore.com>", "Request for Medical Leave", "Please send the certificate.", 2));
        deliver(mail("Sebi Jose <sebi@csacoimbatore.com>", "Regarding Medical Leave", "The principal has approved the leave.", 3));
        deliver(mail("Neel <neel@example.com>", "Weekend plans", "Cricket on Saturday?", 4));
        deliver(mail("NASA Space Apps <apps@example.org>", "Project submitted", "Your project is in.", 5));
        deliver(mail("Notion <news@example.org>", "Same memory, five steps", "Try it today.", 6));

        String token = call(post("/api/auth/sso").contentType(MediaType.APPLICATION_JSON).content(
                "{\"idToken\":\"" + TestIds.idToken(ACCOUNT_ID, "karni@magizhchi.test", "Karnishh") + "\"}"), null, 200).path("token").asText();
        long accountId = call(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(
                "{\"email\":\"karni@magizhchi.test\",\"password\":\"pw\",\"imapHost\":\"localhost\",\"imapPort\":3143,\"smtpHost\":\"localhost\",\"smtpPort\":3025}"),
                token, 200).path("id").asLong();
        waitFor(token, "Same memory, five steps");
        assertThat(subjects(token, "all", "")).hasSize(3);            // sync kept only the newest 3

        // What's kept has nothing from the principal yet …
        assertThat(subjects(token, "all", "principal")).isEmpty();
        // Words match where a word starts, as in Gmail: "cricket" finds Neel's mail, "icket" (inside it) doesn't.
        assertThat(subjects(token, "all", "cricket")).containsExactly("Weekend plans");
        assertThat(subjects(token, "all", "icket")).isEmpty();
        assertThat(subjects(token, "all", "neel")).containsExactly("Weekend plans");   // the sender: any part

        // … the server does: its matches come in. (GreenMail matches a sender only by the whole address; Gmail
        // and other real servers match part of it too, so "principal" alone finds the sender there.)
        JsonNode r = call(post("/api/messages/server-search").param("q", "principal.csi@csacoimbatore.com")
                .param("folder", "INBOX").param("lane", "all"), token, 200);
        assertThat(r.path("searched").asBoolean()).isTrue();
        assertThat(r.path("added").asInt()).isEqualTo(2);
        // A word in the body is found on the server as well (Sebi's mail mentions the principal).
        assertThat(call(post("/api/messages/server-search").param("q", "principal").param("folder", "INBOX").param("lane", "all"),
                token, 200).path("added").asInt()).isEqualTo(1);
        assertThat(subjects(token, "all", "principal")).containsExactlyInAnyOrder(
                "Joining Confirmation - Student ID S2605375", "Request for Medical Leave", "Regarding Medical Leave");

        // Old mail found by search never lands in Needs me.
        for (JsonNode it : list(token, "needs_me", "")) assertThat(it.path("subject").asText()).doesNotContain("Medical Leave");

        // A sender search works the same way, and the same search again doesn't ask the server again.
        assertThat(call(post("/api/messages/server-search").param("q", "principal").param("folder", "INBOX").param("lane", "all"),
                token, 200).path("added").asInt()).isZero();
        assertThat(call(post("/api/messages/server-search").param("q", "from:neel").param("folder", "INBOX").param("lane", "all")
                .param("accountId", String.valueOf(accountId)), token, 200).path("added").asInt()).isZero();   // Neel's is kept already
        assertThat(call(post("/api/messages/server-search").param("q", "is:unread").param("folder", "INBOX"), token, 200)
                .path("searched").asBoolean()).isFalse();             // nothing to look for on the server
    }

    private List<String> subjects(String token, String lane, String q) throws Exception {
        List<String> out = new ArrayList<>();
        for (JsonNode it : list(token, lane, q)) out.add(it.path("subject").asText());
        return out;
    }

    private JsonNode list(String token, String lane, String q) throws Exception {
        return call(get("/api/messages").param("folder", "INBOX").param("lane", lane).param("q", q), token, 200).path("items");
    }

    private void waitFor(String token, String subject) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (subjects(token, "all", "").contains(subject)) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Mail never synced: " + subject);
    }

    private static MimeMessage mail(String from, String subject, String text, int day) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress(from));
        m.setRecipients(jakarta.mail.Message.RecipientType.TO, "karni@magizhchi.test");
        m.setSubject(subject, "UTF-8");
        m.setText(text, "UTF-8");
        m.setSentDate(new Date(1_772_000_000_000L + day * 86_400_000L));
        m.setHeader("Message-ID", "<search-" + day + "@example.com>");
        return m;
    }

    private static void deliver(MimeMessage m) throws Exception {
        m.saveChanges();
        m.setHeader("Message-ID", m.getHeader("Message-ID")[0]);
        greenMail.getUserManager().getUserByEmail("karni@magizhchi.test").deliver(m);
    }

    private JsonNode call(MockHttpServletRequestBuilder req, String token, int status) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        var res = mvc.perform(req).andReturn().getResponse();
        String body = res.getContentAsString(StandardCharsets.UTF_8);
        assertThat(res.getStatus()).as(body).isEqualTo(status);
        return body.isEmpty() ? json.createObjectNode() : json.readTree(body);
    }
}
