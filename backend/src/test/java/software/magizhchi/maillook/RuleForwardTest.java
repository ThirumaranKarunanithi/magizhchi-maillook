package software.magizhchi.maillook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * "Job applications go to the manager and HR": an automation forwards each NEW mail it catches, with its
 * attachments, once. Mail already there (first fill, Run now) is filed but never forwarded.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${MAILLOOK_TEST_DB_URL:jdbc:h2:mem:ruleforward;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${MAILLOOK_TEST_DB_USER:sa}",
        "spring.datasource.password=${MAILLOOK_TEST_DB_PASSWORD:}",
        "maillook.sync.interval-ms=3600000",
        "maillook.meet.recap-sweep-ms=3600000",
        "maillook.ai.api-key="})
@AutoConfigureMockMvc
class RuleForwardTest {

    static final String ME = "office@magizhchi.test";
    static final String HR = "hr@magizhchi.test";
    static final String BOSS = "manager@magizhchi.test";

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser(ME, ME, "pw").withUser(HR, HR, "pw").withUser(BOSS, BOSS, "pw"));

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void newMatchingMailIsForwardedOnceWithItsAttachments() throws Exception {
        deliver("Old application for Sales executive", "old-cv.pdf");   // there before the mailbox is added
        String token = call(post("/api/auth/sso").contentType(MediaType.APPLICATION_JSON).content(
                "{\"idToken\":\"" + TestIds.idToken("2b7c1f0e-5555-4d3a-8b44-000000000091", ME, "Office") + "\"}"), null, 200)
                .path("token").asText();
        call(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(
                "{\"email\":\"" + ME + "\",\"password\":\"pw\",\"imapHost\":\"localhost\",\"imapPort\":3143,\"smtpHost\":\"localhost\",\"smtpPort\":3025}"),
                token, 200);
        waitFor(token, "Old application for Sales executive");

        // Forwarding to one of their own mailboxes would loop: refused.
        String lane = call(post("/api/sections").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Job applications\"}"), token, 200)
                .path("lane").asText();
        call(post("/api/rules").contentType(MediaType.APPLICATION_JSON).content(
                "{\"subject\":\"application for\",\"lane\":\"" + lane + "\",\"forwardTo\":[\"" + ME + "\"]}"), token, 400);

        JsonNode rule = call(post("/api/rules").contentType(MediaType.APPLICATION_JSON).content(
                "{\"subject\":\"application for\",\"lane\":\"" + lane + "\",\"applyNow\":true,\"forwardTo\":[\"" + HR + ", " + BOSS + "\"]}"),
                token, 200);
        assertThat(rule.path("applied").asInt()).isEqualTo(1);   // the old one is filed…
        JsonNode rules = call(get("/api/rules"), token, 200);
        assertThat(rules.toString()).contains(HR).contains(BOSS);

        deliver("New application for Sales executive", "cv.pdf");
        call(post("/api/sync"), token, 202);
        waitFor(token, "New application for Sales executive");

        MimeMessage toHr = waitForInbox(HR, "Fwd: New application for Sales executive");
        assertThat(attachments(toHr)).containsExactly("cv.pdf");
        assertThat(text(toHr)).contains("Forwarded message").contains("candidate@example.com").contains("I would like to apply");
        waitForInbox(BOSS, "Fwd: New application for Sales executive");

        // …but never forwarded, and a later sync doesn't forward the new one twice.
        call(post("/api/sync"), token, 202);
        Thread.sleep(2_000);
        assertThat(subjects(HR)).containsExactly("Fwd: New application for Sales executive");

        // Taking the forward off leaves the rest of the rule.
        long id = rule.path("rule").path("id").asLong();
        JsonNode off = call(put("/api/rules/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"forwardTo\":[]}"), token, 200);
        assertThat(off.path("forwardTo").size()).isZero();
        assertThat(off.path("lane").asText()).isEqualTo(lane);
    }

    private void deliver(String subject, String file) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress("candidate@example.com", "Candidate"));
        m.setRecipients(MimeMessage.RecipientType.TO, ME);
        m.setSubject(subject, "UTF-8");
        MimeMultipart mp = new MimeMultipart();
        MimeBodyPart t = new MimeBodyPart();
        t.setText("Dear Sir, I would like to apply. My CV is attached.", "UTF-8");
        mp.addBodyPart(t);
        MimeBodyPart f = new MimeBodyPart();
        f.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource("%PDF-1.4 cv".getBytes(), "application/pdf")));
        f.setFileName(file);
        mp.addBodyPart(f);
        m.setContent(mp);
        m.setSentDate(new Date());
        m.saveChanges();
        greenMail.getUserManager().getUserByEmail(ME).deliver(m);
    }

    /** What is in this person's own inbox on the test server. */
    private List<MimeMessage> inbox(String user) throws Exception {
        var u = greenMail.getUserManager().getUserByEmail(user);
        List<MimeMessage> out = new ArrayList<>();
        for (var sm : greenMail.getManagers().getImapHostManager().getInbox(u).getMessages()) out.add(sm.getMimeMessage());
        return out;
    }

    private List<String> subjects(String user) throws Exception {
        List<String> out = new ArrayList<>();
        for (MimeMessage m : inbox(user)) out.add(m.getSubject());
        return out;
    }

    private MimeMessage waitForInbox(String user, String subject) throws Exception {
        for (int i = 0; i < 150; i++) {
            for (MimeMessage m : inbox(user)) if (subject.equals(m.getSubject())) return m;
            Thread.sleep(100);
        }
        throw new AssertionError("No forward reached " + user + ": " + subjects(user));
    }

    private static List<String> attachments(Part p) throws Exception {
        List<String> out = new ArrayList<>();
        if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            for (int i = 0; i < mp.getCount(); i++) out.addAll(attachments(mp.getBodyPart(i)));
        } else if (p.getFileName() != null) {
            out.add(p.getFileName());
        }
        return out;
    }

    private static String text(Part p) throws Exception {
        if (p.isMimeType("text/plain")) return (String) p.getContent();
        if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < mp.getCount(); i++) b.append(text(mp.getBodyPart(i)));
            return b.toString();
        }
        return "";
    }

    private void waitFor(String token, String subject) throws Exception {
        for (int i = 0; i < 150; i++) {
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
