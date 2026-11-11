package software.magizhchi.maillook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Magizhchi PA's chat inside MailLook: words become app actions (find and open a mail, archive it, snooze
 * it, reply, compose, go to a page), anything else goes to PA's own assistant, and its held mail can be
 * sent or discarded from the chat. Speech goes to PA to become text.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${MAILLOOK_TEST_DB_URL:jdbc:h2:mem:pachat;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${MAILLOOK_TEST_DB_USER:sa}",
        "spring.datasource.password=${MAILLOOK_TEST_DB_PASSWORD:}",
        "maillook.sync.interval-ms=3600000",
        "maillook.meet.recap-sweep-ms=3600000",
        "maillook.ai.api-key="})
@AutoConfigureMockMvc
class PaChatTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("tiru@magizhchi.test", "tiru@magizhchi.test", "pw"));

    static final String ACCOUNT_ID = "2b7c1f0e-5555-4d3a-8b44-000000000077";
    static final String DECISION = "8f0c7a52-1d3e-4b8a-9c11-2f6e5d4c3b2a";
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<String> prompts = new CopyOnWriteArrayList<>();
    static final List<String> paCalls = new CopyOnWriteArrayList<>();
    static final List<JsonNode> paChats = new CopyOnWriteArrayList<>();
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

    @Test
    void chatWorksTheAppAndHandsTheRestToPa() throws Exception {
        deliver("Action needed: LiveKit usage invoice", "Your LiveKit invoice for August is USD 406.91.", "Ranjit Singh <ar@livekit.io>", "<lk-1@livekit.io>");
        deliver("Hall booking for Saturday", "The hall is booked for Saturday.", "Priya <priya@example.com>", "<hall-1@example.com>");
        String token = call(post("/api/auth/sso").contentType(MediaType.APPLICATION_JSON).content(
                "{\"idToken\":\"" + TestIds.idToken(ACCOUNT_ID, "tiru@magizhchi.test", "Tiru Karuna") + "\"}"), null, 200).path("token").asText();
        call(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(
                "{\"email\":\"tiru@magizhchi.test\",\"password\":\"pw\",\"imapHost\":\"localhost\",\"imapPort\":3143,\"smtpHost\":\"localhost\",\"smtpPort\":3025}"),
                token, 200);
        long invoice = waitForMessage(token, "Action needed: LiveKit usage invoice");
        long hall = waitForMessage(token, "Hall booking for Saturday");

        // ── "open the livekit invoice and archive it": found by its words, and "it" is the mail just opened
        JsonNode r = chat(token, "open the livekit invoice and archive it", null);
        assertThat(r.path("by").asText()).isEqualTo("maillook");
        assertThat(r.path("reply").asText()).isEqualTo("Opened it and archived it.");
        JsonNode acts = r.path("actions");
        assertThat(acts.size()).isEqualTo(2);
        assertThat(acts.get(0).path("type").asText()).isEqualTo("open");
        assertThat(acts.get(0).path("messageId").asLong()).isEqualTo(invoice);
        assertThat(acts.get(0).path("from").asText()).isEqualTo("Ranjit Singh");
        assertThat(acts.get(1).path("act").asText()).isEqualTo("archive");
        assertThat(acts.get(1).path("messageId").asLong()).isEqualTo(invoice);
        assertThat(last()).contains("No mail is open.").contains("The person says: open the livekit invoice and archive it")
                .contains("Their mailboxes: tiru@magizhchi.test");

        // ── the open mail is the one on screen, and its text goes to the AI so "what does it say" works
        JsonNode snooze = chat(token, "snooze this till tomorrow 9am", hall);
        assertThat(last()).contains("The open mail: from Priya <priya@example.com>, subject \"Hall booking for Saturday\"")
                .contains("The hall is booked for Saturday.");
        JsonNode s = snooze.path("actions").get(0);
        assertThat(s.path("act").asText()).isEqualTo("snooze");
        assertThat(s.path("messageId").asLong()).isEqualTo(hall);
        String nine = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1).atTime(9, 0).atZone(ZoneId.of("Asia/Kolkata")).toInstant().toString();
        assertThat(s.path("at").asText()).isEqualTo(nine);
        // Nothing open and "this": it says so instead of guessing.
        JsonNode nothing = chat(token, "snooze this till tomorrow 9am", null);
        assertThat(nothing.path("actions")).isEmpty();
        assertThat(nothing.path("reply").asText()).contains("Open a mail first");

        // ── pages, replies and new mail: names become addresses from the people they write to
        JsonNode go = chat(token, "show me my bills and go to nowhere", null);
        assertThat(go.path("actions").size()).isEqualTo(1);
        assertThat(go.path("actions").get(0).path("to").asText()).isEqualTo("bills");
        JsonNode rep = chat(token, "reply to priya that I'll call at 5", null);
        assertThat(rep.path("actions").get(0).path("type").asText()).isEqualTo("reply");
        assertThat(rep.path("actions").get(0).path("messageId").asLong()).isEqualTo(hall);
        assertThat(rep.path("actions").get(0).path("body").asText()).isEqualTo("Hi Priya, I'll call you at 5.");
        JsonNode mail = chat(token, "write to priya and to Somebody Unknown about the hall", null);
        JsonNode c = mail.path("actions").get(0);
        assertThat(c.path("type").asText()).isEqualTo("compose");
        assertThat(c.path("to").toString()).isEqualTo("[\"priya@example.com\"]");
        assertThat(mail.path("reply").asText()).contains("I don't know Somebody Unknown's address");

        // ── the calendar is PA's: the whole message and the chat so far go to PA's own assistant
        JsonNode cal = call(post("/api/assistant/chat").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of(
                "message", "what's on my calendar tomorrow? and mail ravi the hall details",
                "history", List.of(Map.of("role", "user", "content", "hi"), Map.of("role", "assistant", "content", "Vanakkam!")),
                "tz", "Asia/Kolkata", "voice", true))), token, 200);
        assertThat(cal.path("by").asText()).isEqualTo("pa");
        assertThat(cal.path("reply").asText()).isEqualTo("Tomorrow you have Fee review at 3 pm. The mail to Ravi is ready.");
        JsonNode held = cal.path("paActions").get(0);
        assertThat(held.path("type").asText()).isEqualTo("CONFIRM_MAIL");
        assertThat(held.path("decisionId").asText()).isEqualTo(DECISION);
        assertThat(held.path("attachments").get(0).asText()).isEqualTo("hall.pdf");
        JsonNode sentToPa = paChats.get(paChats.size() - 1);
        assertThat(sentToPa.path("history").size()).isEqualTo(2);
        assertThat(sentToPa.path("clientZone").asText()).isEqualTo("Asia/Kolkata");
        assertThat(sentToPa.path("voice").asBoolean()).isTrue();
        assertThat(call(post("/api/assistant/decisions/" + DECISION + "/approve"), token, 200).path("status").asText()).isEqualTo("SENT");
        assertThat(paCalls).contains("POST /api/v1/decisions/" + DECISION + "/approve");
        call(post("/api/assistant/decisions/not-a-uuid/approve"), token, 400);
        call(post("/api/assistant/decisions/" + DECISION + "/maybe"), token, 400);

        // ── speaking: PA turns the recording into text
        String wav = java.util.Base64.getEncoder().encodeToString("RIFF0000WAVEfmt fake".getBytes(StandardCharsets.UTF_8));
        JsonNode heard = call(post("/api/assistant/transcribe").contentType(MediaType.APPLICATION_JSON)
                .content("{\"audio\":\"" + wav + "\",\"mimeType\":\"audio/webm;codecs=opus\"}"), token, 200);
        assertThat(heard.path("text").asText()).isEqualTo("open my bills");
        call(post("/api/assistant/transcribe").contentType(MediaType.APPLICATION_JSON).content("{\"audio\":\"\"}"), token, 400);
        call(post("/api/assistant/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"  \"}"), token, 400);

        // ── the AI's JSON wrapped in words and ```json fences still reads
        JsonNode fenced = chat(token, "i fixed the hall thing so mark it as read", null);
        assertThat(fenced.path("reply").asText()).isEqualTo("Marked it read.");
        assertThat(fenced.path("actions").get(0).path("act").asText()).isEqualTo("read");
        assertThat(fenced.path("actions").get(0).path("messageId").asLong()).isEqualTo(hall);
        // ── an answer with no JSON at all gets one more go, asking for the JSON only
        JsonNode prose = chat(token, "flag the hall mail", null);
        assertThat(prose.path("reply").asText()).isEqualTo("Flagged it.");
        assertThat(prose.path("actions").get(0).path("act").asText()).isEqualTo("flag");

        // ── PA sees the list on screen and the newest mail in each lane, and points at mails by id
        Map<String, Object> ctx = new java.util.HashMap<>();
        ctx.put("page", "mail");
        ctx.put("view", "All mail");
        ctx.put("listIds", List.of(hall, invoice, 999_999L));
        JsonNode byId = call(post("/api/assistant/chat").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of(
                "message", "mark as read that hall mail in the list", "context", ctx, "tz", "Asia/Kolkata"))), token, 200);
        String shown = last();
        assertThat(shown).contains("The list on screen").contains("id " + hall + " ").contains("Hall booking for Saturday").doesNotContain("999999");
        assertThat(shown).contains("Newest in ");
        assertThat(byId.path("reply").asText()).isEqualTo("Marked the hall mail read.");
        assertThat(byId.path("actions").get(0).path("act").asText()).isEqualTo("read");
        assertThat(byId.path("actions").get(0).path("messageId").asLong()).isEqualTo(hall);
        // "the last mail in <lane>": the newest one in that lane, as listed
        JsonNode latest = chat(token, "open the last mail in my top lane", null);
        java.util.regex.Matcher top = java.util.regex.Pattern.compile("\\(lane \\w+\\), newest first:\\n  id (\\d+) ").matcher(last());
        assertThat(top.find()).isTrue();
        assertThat(latest.path("actions").get(0).path("messageId").asLong()).isEqualTo(Long.parseLong(top.group(1)));
        // nothing found: say so, without the AI's "Archived it" and without blaming "no mail open"
        JsonNode none = chat(token, "archive the unicorn mail", null);
        assertThat(none.path("actions").size()).isZero();
        assertThat(none.path("reply").asText()).isEqualTo("I couldn't find that mail.");
    }

    private JsonNode chat(String token, String message, Long open) throws Exception {
        Map<String, Object> ctx = new java.util.HashMap<>();
        ctx.put("page", "mail");
        ctx.put("view", "Needs me");
        ctx.put("openMessageId", open);
        return call(post("/api/assistant/chat").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of(
                "message", message, "context", ctx, "tz", "Asia/Kolkata"))), token, 200);
    }

    // ── a pretend Magizhchi PA ────────────────────────────────────

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
        paCalls.add(ex.getRequestMethod() + " " + path);
        if (path.equals("/api/v1/auth/sso")) { reply(ex, 200, "{\"accessToken\":\"pa-1\",\"expiresIn\":900}"); return; }
        if (path.equals("/api/v1/assistant/status")) { reply(ex, 200, "{\"enabled\":true}"); return; }
        if (path.equals("/api/v1/assistant/complete")) {
            JsonNode req = JSON.readTree(body);
            String system = req.path("system").asText();
            String prompt = req.path("prompt").asText();
            prompts.add(prompt);
            String text = "{\"say\":\"\",\"actions\":[],\"askPa\":false}";
            if (system.contains("working inside Magizhchi MailLook")) {
                String said = prompt.substring(prompt.indexOf("The person says: ") + 17);
                int again = said.indexOf("\n\nAnswer with the JSON object only");
                boolean retried = again >= 0;
                if (retried) said = said.substring(0, again);
                String hallRead = "{\"say\":\"Marked it read.\",\"actions\":[{\"type\":\"mail\",\"act\":\"read\",\"find\":{\"words\":[\"hall\"],"
                        + "\"from\":null,\"folder\":\"ALL\"}}],\"askPa\":false}";
                if (said.equals("i fixed the hall thing so mark it as read")) {
                    // the model wrapped its JSON in a sentence and code fences
                    reply(ex, 200, JSON.writeValueAsString(Map.of("text", "Sure, here's the plan:\n```json\n" + hallRead + "\n```\nDone.")));
                    return;
                }
                if (said.equals("mark as read that hall mail in the list")) {
                    // the model points at the mail by the id it was shown in the list
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^The list on screen.*\\n(?:  id .*\\n)*?  id (\\d+) [^\\n]*Hall booking").matcher(prompt);
                    String id = m.find() ? m.group(1) : "0";
                    reply(ex, 200, JSON.writeValueAsString(Map.of("text", "{\"say\":\"Marked the hall mail read.\",\"actions\":[{\"type\":\"mail\","
                            + "\"act\":\"read\",\"id\":" + id + "}],\"askPa\":false}")));
                    return;
                }
                if (said.equals("open the last mail in my top lane")) {
                    // no words, just a lane: the newest mail there
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\(lane (\\w+)\\)").matcher(prompt);
                    String lane = m.find() ? m.group(1) : "needs_me";
                    reply(ex, 200, JSON.writeValueAsString(Map.of("text", "{\"say\":\"Opened it.\",\"actions\":[{\"type\":\"open\","
                            + "\"find\":{\"words\":[],\"from\":null,\"lane\":\"" + lane + "\"}}],\"askPa\":false}")));
                    return;
                }
                if (said.equals("archive the unicorn mail")) {
                    reply(ex, 200, JSON.writeValueAsString(Map.of("text", "{\"say\":\"Archived the unicorn mail.\",\"actions\":[{\"type\":\"mail\","
                            + "\"act\":\"archive\",\"find\":{\"words\":[\"unicorn\"],\"from\":null}}],\"askPa\":false}")));
                    return;
                }
                if (said.equals("flag the hall mail")) {
                    // prose first, the JSON only when asked again
                    String flag = hallRead.replace("\"read\"", "\"flag\"").replace("Marked it read.", "Flagged it.");
                    reply(ex, 200, JSON.writeValueAsString(Map.of("text", retried ? flag : "I'll flag the hall mail for you.")));
                    return;
                }
                String tomorrow = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1) + "T09:00";
                text = switch (said) {
                    case "open the livekit invoice and archive it" -> "{\"say\":\"Opened it and archived it.\",\"actions\":["
                            + "{\"type\":\"open\",\"find\":{\"words\":[\"livekit\",\"invoice\"],\"from\":null,\"folder\":\"ALL\"}},"
                            + "{\"type\":\"mail\",\"act\":\"archive\",\"which\":\"open\"}],\"askPa\":false}";
                    case "snooze this till tomorrow 9am" -> "{\"say\":\"\",\"actions\":[{\"type\":\"mail\",\"act\":\"snooze\",\"which\":\"open\",\"until\":\""
                            + tomorrow + "\"}],\"askPa\":false}";
                    case "show me my bills and go to nowhere" -> "{\"say\":\"Here are your bills.\",\"actions\":[{\"type\":\"go\",\"to\":\"bills\"},"
                            + "{\"type\":\"go\",\"to\":\"nowhere\"}],\"askPa\":false}";
                    case "reply to priya that I'll call at 5" -> "{\"say\":\"Reply ready.\",\"actions\":[{\"type\":\"reply\",\"find\":{\"words\":[],"
                            + "\"from\":\"priya\"},\"all\":false,\"body\":\"Hi Priya, I'll call you at 5.\"}],\"askPa\":false}";
                    case "write to priya and to Somebody Unknown about the hall" -> "{\"say\":\"Draft ready.\",\"actions\":[{\"type\":\"compose\","
                            + "\"to\":[\"priya@example.com\",\"Somebody Unknown\"],\"subject\":\"The hall\",\"body\":\"Hi\"}],\"askPa\":false}";
                    default -> "{\"say\":\"\",\"actions\":[],\"askPa\":true}";
                };
            }
            reply(ex, 200, JSON.writeValueAsString(Map.of("text", text)));
            return;
        }
        if (path.equals("/api/v1/assistant/chat")) {
            paChats.add(JSON.readTree(body));
            reply(ex, 200, "{\"reply\":\"Tomorrow you have Fee review at 3 pm. The mail to Ravi is ready.\",\"actions\":["
                    + "{\"type\":\"CONFIRM_MAIL\",\"summary\":\"Mail to Ravi\",\"decisionId\":\"" + DECISION + "\",\"email\":\"ravi@example.net\","
                    + "\"subject\":\"Hall details\",\"body\":\"Hi Ravi\",\"attachments\":[\"hall.pdf\"]}]}");
            return;
        }
        if (path.equals("/api/v1/decisions/" + DECISION + "/approve")) { reply(ex, 200, "{\"status\":\"SENT\"}"); return; }
        if (path.equals("/api/v1/assistant/transcribe")) {
            JsonNode req = JSON.readTree(body);
            reply(ex, req.path("mimeType").asText().equals("audio/webm") ? 200 : 400, "{\"text\":\"open my bills\"}");
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

    private static String last() {
        for (int i = prompts.size() - 1; i >= 0; i--) if (prompts.get(i).contains("The person says: ")) return prompts.get(i);
        throw new AssertionError("No chat prompt");
    }

    private static void deliver(String subject, String text, String from, String id) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties())) {
            @Override
            protected void updateMessageID() throws jakarta.mail.MessagingException {
                setHeader("Message-ID", id);
            }
        };
        m.setFrom(new InternetAddress(from));
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse("tiru@magizhchi.test"));
        m.setSentDate(Date.from(Instant.now()));
        m.setSubject(subject, "UTF-8");
        m.setText(text, "UTF-8");
        m.saveChanges();
        greenMail.getUserManager().getUserByEmail("tiru@magizhchi.test").deliver(m);
    }

    private long waitForMessage(String token, String subject) throws Exception {
        for (int i = 0; i < 100; i++) {
            for (JsonNode it : call(get("/api/messages").param("view", "all"), token, 200).path("items")) {
                if (subject.equals(it.path("subject").asText())) return it.path("id").asLong();
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
