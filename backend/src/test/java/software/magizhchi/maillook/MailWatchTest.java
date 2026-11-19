package software.magizhchi.maillook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import software.magizhchi.maillook.mail.MailWatch;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** New mail shows within seconds: the Inbox is watched (IMAP IDLE), long before the hourly sync here would run. */
@SpringBootTest(properties = {
        "spring.datasource.url=${MAILLOOK_TEST_DB_URL:jdbc:h2:mem:mailwatch;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${MAILLOOK_TEST_DB_USER:sa}",
        "spring.datasource.password=${MAILLOOK_TEST_DB_PASSWORD:}",
        "maillook.sync.interval-ms=3600000",
        "maillook.sync.idle=true",
        "maillook.meet.recap-sweep-ms=3600000",
        "maillook.ai.api-key="})
@AutoConfigureMockMvc
class MailWatchTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("otp@magizhchi.test", "otp@magizhchi.test", "pw"));

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MailWatch watch;

    @Test
    void newMailArrivesWithinSecondsWithoutWaitingForTheTimedSync() throws Exception {
        GreenMailUtil.sendTextEmail("otp@magizhchi.test", "bank@example.com", "Welcome", "Hello.", ServerSetupTest.SMTP);
        String token = call(post("/api/auth/sso").contentType(MediaType.APPLICATION_JSON).content(
                "{\"idToken\":\"" + TestIds.idToken("2b7c1f0e-5555-4d3a-8b44-000000000088", "otp@magizhchi.test", "Otp") + "\"}"), null, 200)
                .path("token").asText();
        call(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(
                "{\"email\":\"otp@magizhchi.test\",\"password\":\"pw\",\"imapHost\":\"localhost\",\"imapPort\":3143,\"smtpHost\":\"localhost\",\"smtpPort\":3025}"),
                token, 200);
        waitFor(token, "Welcome", 10_000);

        watch.refresh();
        for (int i = 0; i < 100 && watch.watching() == 0; i++) Thread.sleep(100);
        assertThat(watch.watching()).isEqualTo(1);
        Thread.sleep(3_000);   // the first sync after watching starts has long finished: only IDLE can bring the next mail

        long sent = System.currentTimeMillis();
        GreenMailUtil.sendTextEmail("otp@magizhchi.test", "bank@example.com", "Your OTP is 482913", "Valid for 5 minutes.", ServerSetupTest.SMTP);
        waitFor(token, "Your OTP is 482913", 10_000);
        assertThat(System.currentTimeMillis() - sent).isLessThan(10_000);   // the timed sync is an hour away here
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
