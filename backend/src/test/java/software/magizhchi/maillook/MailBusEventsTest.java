package software.magizhchi.maillook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.sun.net.httpserver.HttpServer;
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
import software.magizhchi.maillook.suite.MailBusEvents;
import software.magizhchi.suite.events.SuiteEvents;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * New inbox mail goes out on the events bus as maillook.mail.received, to the mailbox's owner: not for the first fill
 * of a mailbox, not for newsletters, not for the person's own mail. A real mail server (GreenMail) and a stand-in PA.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${MAILLOOK_TEST_DB_URL:jdbc:h2:mem:mailbus;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${MAILLOOK_TEST_DB_USER:sa}",
        "spring.datasource.password=${MAILLOOK_TEST_DB_PASSWORD:}",
        "maillook.sync.interval-ms=3600000",
        "maillook.sync.idle=false",
        "maillook.meet.recap-sweep-ms=3600000",
        "maillook.ai.api-key=",
        "maillook.web-url=https://mail.example/",
        "magizhchi.provision-secret=s3cret"})
@AutoConfigureMockMvc
class MailBusEventsTest {

    private static final String ME = "kavya@magizhchi.test";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<JsonNode> GOT = new CopyOnWriteArrayList<>();
    private static final HttpServer PA;

    static {
        try {
            PA = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            PA.createContext("/api/v1/public/suite/events", ex -> {
                GOT.add(JSON.readTree(ex.getRequestBody()));
                byte[] out = "{\"stored\":1}".getBytes();
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            PA.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void pa(DynamicPropertyRegistry r) {
        r.add("magizhchi.pa.url", () -> "http://127.0.0.1:" + PA.getAddress().getPort());
    }

    @AfterAll
    static void stopPa() {
        PA.stop(0);
    }

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser(ME, ME, "pw"));

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired SuiteEvents events;
    @Autowired MailBusEvents bus;

    @Test
    void newMailFromAPersonReachesTheOwnerButNotTheFirstFillNewslettersOrOwnMail() throws Exception {
        GreenMailUtil.sendTextEmail(ME, "old.friend@example.com", "Already here before MailLook", "Hi.", ServerSetupTest.SMTP);
        String token = call(post("/api/auth/sso").contentType(MediaType.APPLICATION_JSON).content(
                "{\"idToken\":\"" + TestIds.idToken("2b7c1f0e-5555-4d3a-8b44-000000000099", ME, "Kavya") + "\"}"), null, 200)
                .path("token").asText();
        call(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(
                "{\"email\":\"" + ME + "\",\"password\":\"pw\",\"imapHost\":\"localhost\",\"imapPort\":3143,\"smtpHost\":\"localhost\",\"smtpPort\":3025}"),
                token, 200);
        waitFor(token, "Already here before MailLook", 10_000);
        Thread.sleep(500);
        assertThat(sent()).as("the first fill of a new mailbox is not news").isEmpty();

        GreenMailUtil.sendTextEmail(ME, "arun@client.example", "Quote for 40 chairs", "Can you send the quote by Friday?", ServerSetupTest.SMTP);
        MimeMessage news = GreenMailUtil.createTextEmail(ME, "offers@shop.example", "Big Diwali sale", "50% off", ServerSetupTest.SMTP);
        news.setHeader("List-Unsubscribe", "<mailto:unsubscribe@shop.example>");
        GreenMailUtil.sendMimeMessage(news);
        GreenMailUtil.sendTextEmail(ME, ME, "Note to self", "Buy milk", ServerSetupTest.SMTP);

        call(post("/api/sync"), token, 202);
        waitFor(token, "Quote for 40 chairs", 10_000);
        waitFor(token, "Big Diwali sale", 10_000);
        waitFor(token, "Note to self", 10_000);

        List<JsonNode> got = List.of();
        for (int i = 0; i < 100 && got.isEmpty(); i++) {
            got = sent();
            if (got.isEmpty()) Thread.sleep(100);
        }
        Thread.sleep(300);
        got = sent();
        assertThat(got).extracting(e -> e.path("data").path("subject").asText()).containsExactly("Quote for 40 chairs");
        JsonNode e = got.get(0);
        assertThat(e.path("type").asText()).isEqualTo("maillook.mail.received");
        assertThat(e.path("ownerEmail").asText()).isEqualTo(ME);
        JsonNode d = e.path("data");
        assertThat(e.path("id").asText()).isEqualTo(d.path("mailId").asLong() + ":received");
        assertThat(d.path("fromEmail").asText()).isEqualTo("arun@client.example");
        assertThat(d.path("mailbox").asText()).isEqualTo(ME);
        assertThat(d.path("lane").asText()).isEqualTo("needs_me");
        assertThat(d.path("needsMe").asBoolean()).isTrue();
        assertThat(d.path("snippet").asText()).contains("quote by Friday");
        assertThat(d.path("link").asText()).isEqualTo("https://mail.example/?open=" + d.path("mailId").asLong());
    }

    @Test
    void offWithoutTheSecret() {
        SuiteEvents off = new SuiteEvents("maillook", "http://127.0.0.1:" + PA.getAddress().getPort(), "");
        try {
            assertThat(new MailBusEvents(off, null, null, "").enabled()).isFalse();
            assertThat(bus.enabled()).isTrue();
        } finally {
            off.close();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private List<JsonNode> sent() {
        events.flush();
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode batch : GOT) {
            assertThat(batch.path("app").asText()).isEqualTo("maillook");
            batch.path("events").forEach(out::add);
        }
        return out;
    }

    private void waitFor(String token, String subject, long ms) throws Exception {
        long until = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < until) {
            for (JsonNode it : call(get("/api/messages").param("folder", "INBOX").param("lane", "all"), token, 200).path("items"))
                if (subject.equals(it.path("subject").asText())) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Never arrived: " + subject);
    }

    private JsonNode call(MockHttpServletRequestBuilder req, String token, int status) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        var res = mvc.perform(req).andReturn().getResponse();
        String body = res.getContentAsString(StandardCharsets.UTF_8);
        assertThat(res.getStatus()).as(body).isEqualTo(status);
        return body.isEmpty() ? json.createObjectNode() : json.readTree(body);
    }
}
