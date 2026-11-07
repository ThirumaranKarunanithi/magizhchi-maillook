package software.magizhchi.maillook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.mail.Message;
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
import software.magizhchi.maillook.suite.Ics;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 3: writing a mail from a sentence and rewriting it, asking your mail a question, mail you sent
 * that nobody answered (with a follow-up ready), the daily briefing, free times for any mail, and
 * answering a calendar invite (the organizer gets the REPLY; a yes goes in PA, a no comes out again).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${MAILLOOK_TEST_DB_URL:jdbc:h2:mem:phase3;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${MAILLOOK_TEST_DB_USER:sa}",
        "spring.datasource.password=${MAILLOOK_TEST_DB_PASSWORD:}",
        "maillook.sync.interval-ms=3600000",
        "maillook.meet.recap-sweep-ms=3600000",
        "maillook.ai.api-key="})
@AutoConfigureMockMvc
class AiPhase3Test {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("tiru@magizhchi.test", "tiru@magizhchi.test", "pw"));

    static final String ACCOUNT_ID = "2b7c1f0e-5555-4d3a-8b44-000000000031";
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final ObjectMapper JSON = new ObjectMapper();
    static final LocalDate MEETING_DAY = LocalDate.now(IST).plusDays(2);
    static final List<String> paCalls = new CopyOnWriteArrayList<>();
    static final List<JsonNode> paEvents = new CopyOnWriteArrayList<>();
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

    @Test
    void writesAsksFollowsUpBriefsAndAnswersInvites() throws Exception {
        deliver(plain("Hall booking for Saturday", "Hi sir, the hall is booked for Saturday. Advance paid: Rs 5,000. Balance on the day.", "<hall@example.com>"));
        deliver(invite());
        String token = call(post("/api/auth/sso").contentType(MediaType.APPLICATION_JSON).content(
                "{\"idToken\":\"" + TestIds.idToken(ACCOUNT_ID, "tiru@magizhchi.test", "Tiru Karuna") + "\"}"), null, 200).path("token").asText();
        long accountId = call(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(
                "{\"email\":\"tiru@magizhchi.test\",\"password\":\"pw\",\"imapHost\":\"localhost\",\"imapPort\":3143,\"smtpHost\":\"localhost\",\"smtpPort\":3025}"),
                token, 200).path("id").asLong();
        long hall = waitForMessage(token, "Hall booking for Saturday");
        long inv = waitForMessage(token, "Invitation: Placement review");

        // ── write a mail from a sentence, then make it shorter
        JsonNode w = call(post("/api/ai/write").contentType(MediaType.APPLICATION_JSON).content(
                "{\"accountId\":" + accountId + ",\"prompt\":\"ask ravi for the october fee receipts by friday\",\"to\":[\"Ravi <ravi@example.net>\"]}"), token, 200);
        assertThat(w.path("subject").asText()).isEqualTo("October fee receipts");
        assertThat(w.path("body").asText()).contains("Hi Ravi");
        assertThat(last("You write one email")).contains("ask ravi for the october fee receipts").contains("Ravi <ravi@example.net>");
        JsonNode rw = call(post("/api/ai/rewrite").contentType(MediaType.APPLICATION_JSON).content(
                "{\"text\":\"Hi Ravi, I was wondering whether you might possibly share the receipts.\",\"style\":\"shorter\"}"), token, 200);
        assertThat(rw.path("body").asText()).isEqualTo("Hi Ravi, please share the receipts.");
        assertThat(last("You rewrite an email draft")).contains("Make it shorter");
        call(post("/api/ai/rewrite").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Hi\",\"style\":\"\"}"), token, 400);

        // ── ask your mail: the question becomes a search, and the answer cites the mail
        JsonNode ask = call(get("/api/ai/ask").param("q", "How much advance did I pay for the hall?").param("tz", "Asia/Kolkata"), token, 200);
        assertThat(ask.path("plan").path("keywords").toString()).contains("hall").contains("advance");
        assertThat(ask.path("items").get(0).path("id").asLong()).isEqualTo(hall);
        assertThat(ask.path("answer").asText()).contains("5,000");
        assertThat(ask.path("sources").get(0).asLong()).isEqualTo(hall);

        // ── waiting on others: a mail sent three days ago asking for something, still unanswered
        String payload = "{\"accountId\":" + accountId + ",\"to\":[\"Ravi <ravi@example.net>\"],\"subject\":\"Fee receipts\","
                + "\"text\":\"Hi Ravi, could you share the October fee receipts?\"}";
        long sent = call(multipart("/api/send").file(new MockMultipartFile("payload", "", "application/json", payload.getBytes())), token, 200)
                .path("id").asLong();
        String thanks = "{\"accountId\":" + accountId + ",\"to\":[\"meena@example.org\"],\"subject\":\"Thanks\",\"text\":\"Thanks Meena, all done.\"}";
        long done = call(multipart("/api/send").file(new MockMultipartFile("payload", "", "application/json", thanks.getBytes())), token, 200)
                .path("id").asLong();
        jdbc.update("UPDATE messages SET received_at = ? WHERE id IN (?, ?)", OffsetDateTime.now(ZoneOffset.UTC).minusDays(3), sent, done);
        JsonNode waiting = call(get("/api/messages").param("folder", "WAITING"), token, 200).path("items");
        assertThat(waiting).hasSize(1);
        assertThat(waiting.get(0).path("id").asLong()).isEqualTo(sent);
        assertThat(waiting.get(0).path("waitingDays").asInt()).isEqualTo(3);
        assertThat(waiting.get(0).path("waitingFor").asText()).isEqualTo("the October fee receipts");
        assertThat(call(get("/api/counts"), token, 200).path("happiness").path("waitingOnOthers").asInt()).isEqualTo(1);
        assertThat(call(get("/api/messages/" + sent), token, 200).path("waitingDays").asInt()).isEqualTo(3);
        JsonNode nudge = call(post("/api/messages/" + sent + "/nudge"), token, 200);
        assertThat(nudge.path("body").asText()).contains("receipts");
        assertThat(nudge.path("byAi").asBoolean()).isTrue();
        assertThat(nudge.path("subject").asText()).isEqualTo("Re: Fee receipts");
        assertThat(nudge.path("to").get(0).asText()).contains("ravi@example.net");
        call(post("/api/messages/" + hall + "/waiting/off"), token, 400);   // only mail you sent

        // ── the daily briefing
        JsonNode b = call(get("/api/briefing").param("tz", "Asia/Kolkata"), token, 200);
        assertThat(b.path("greeting").asText()).endsWith(", Tiru");
        assertThat(b.path("byAi").asBoolean()).isTrue();
        assertThat(b.path("summary").asText()).isEqualTo("A calm day: one meeting and Priya's hall booking.");
        assertThat(b.path("needsMe").toString()).contains("Hall booking for Saturday").contains("Confirm the Saturday hall");
        assertThat(b.path("waiting").get(0).path("id").asLong()).isEqualTo(sent);
        assertThat(b.path("events").get(0).path("title").asText()).isEqualTo("Fee review");
        assertThat(b.path("paConnected").asBoolean()).isTrue();
        call(post("/api/messages/" + sent + "/waiting/off"), token, 204);
        assertThat(call(get("/api/messages").param("folder", "WAITING"), token, 200).path("items")).isEmpty();

        // ── free times for any mail, from the PA calendar
        JsonNode free = call(get("/api/meet/free").param("minutes", "45"), token, 200);
        assertThat(free.path("minutes").asInt()).isEqualTo(45);
        assertThat(free.path("slots").size()).isBetween(1, 4);
        assertThat(free.path("calendarChecked").asBoolean()).isTrue();

        // ── the invite: when, the clash, and answering it
        JsonNode m = call(get("/api/messages/" + inv), token, 200);
        assertThat(m.path("invite").asBoolean()).isTrue();
        assertThat(call(get("/api/messages/" + hall), token, 200).path("invite").asBoolean()).isFalse();
        JsonNode card = call(get("/api/messages/" + inv + "/invite").param("tz", "Asia/Kolkata"), token, 200);
        assertThat(card.path("found").asBoolean()).isTrue();
        assertThat(card.path("title").asText()).isEqualTo("Placement review");
        assertThat(Instant.parse(card.path("start").asText())).isEqualTo(MEETING_DAY.atTime(15, 0).atZone(IST).toInstant());
        assertThat(Instant.parse(card.path("end").asText())).isEqualTo(MEETING_DAY.atTime(15, 45).atZone(IST).toInstant());
        assertThat(card.path("organizer").path("email").asText()).isEqualTo("karthik@example.com");
        assertThat(card.path("joinUrl").asText()).isEqualTo("https://meet.google.com/abc-defg-hij");
        assertThat(card.path("myStatus").asText()).isEqualTo("NEEDS-ACTION");
        assertThat(card.path("canAnswer").asBoolean()).isTrue();
        assertThat(card.path("clashes").get(0).path("title").asText()).isEqualTo("Fee review");

        JsonNode yes = call(post("/api/messages/" + inv + "/invite/respond").param("tz", "Asia/Kolkata").contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"accepted\",\"comment\":\"See you there\"}"), token, 200);
        assertThat(yes.path("message").asText()).contains("You said yes").contains("PA calendar");
        assertThat(yes.path("paEventId").asText()).isEqualTo("ev-77");
        JsonNode added = paEvents.get(paEvents.size() - 1);
        assertThat(added.path("title").asText()).isEqualTo("Placement review");
        assertThat(added.path("location").asText()).isEqualTo("Room 4");
        assertThat(added.path("description").asText()).contains("https://meet.google.com/abc-defg-hij");
        MimeMessage reply = waitForSent("Accepted: Placement review");
        assertThat(reply.getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo("karthik@example.com");
        String raw = GreenMailUtil.getWholeMessage(reply);
        assertThat(raw).contains("method=REPLY").contains("METHOD:REPLY").contains("UID:review-42@example.com")
                .contains("PARTSTAT=ACCEPTED").contains("mailto:tiru@magizhchi.test").contains("COMMENT:See you there");
        assertThat(call(get("/api/messages/" + inv + "/invite"), token, 200).path("replied").path("answer").asText()).isEqualTo("accepted");

        // Changing to no takes it off the PA calendar again.
        JsonNode no = call(post("/api/messages/" + inv + "/invite/respond").contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"declined\"}"), token, 200);
        assertThat(no.path("message").asText()).contains("You said no").contains("off your Magizhchi PA calendar");
        assertThat(paCalls).contains("DELETE /api/v1/events/ev-77");
        waitForSent("Declined: Placement review");
        call(post("/api/messages/" + inv + "/invite/respond").contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"perhaps\"}"), token, 400);

        // ── the morning briefing alert settings
        JsonNode s = call(put("/api/push").contentType(MediaType.APPLICATION_JSON).content("{\"briefingHour\":7,\"tz\":\"Asia/Kolkata\"}"), token, 200);
        assertThat(s.path("briefingHour").asInt()).isEqualTo(7);
        assertThat(s.path("mode").asText()).isEqualTo("needs_me");
        call(put("/api/push").contentType(MediaType.APPLICATION_JSON).content("{\"briefingHour\":2}"), token, 400);
        assertThat(call(put("/api/push").contentType(MediaType.APPLICATION_JSON).content("{\"briefing\":false}"), token, 200)
                .path("briefingHour").isNull()).isTrue();
    }

    @Test
    void readsInvitesFromOutlookAndAllDayEvents() {
        String outlook = String.join("\r\n",
                "BEGIN:VCALENDAR", "METHOD:REQUEST", "BEGIN:VTIMEZONE", "TZID:India Standard Time", "BEGIN:STANDARD",
                "DTSTART:16010101T000000", "TZOFFSETFROM:+0530", "TZOFFSETTO:+0530", "END:STANDARD", "END:VTIMEZONE",
                "BEGIN:VEVENT", "ORGANIZER;CN=\"Sharma, Anil\":mailto:Anil@Corp.example",
                "ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;CN=Tiru:mailto:tiru@magizhchi.test",
                "DESCRIPTION:Agenda:\\n1. Budget\\, fees\\n2. Join https://teams.microsoft.com/l/meetup-join/19%3a",
                " abc",
                "UID:040000008200E00074C5B7101A82E008", "SUMMARY;LANGUAGE=en-US:Budget sync",
                "DTSTART;TZID=India Standard Time:20261120T110000", "DURATION:PT30M",
                "BEGIN:VALARM", "TRIGGER:-PT15M", "DESCRIPTION:Reminder", "END:VALARM",
                "END:VEVENT", "END:VCALENDAR");
        Ics.Event e = Ics.parse(outlook, ZoneOffset.UTC);
        assertThat(e.method()).isEqualTo("REQUEST");
        assertThat(e.title()).isEqualTo("Budget sync");
        assertThat(e.start()).isEqualTo(Instant.parse("2026-11-20T05:30:00Z"));
        assertThat(e.end()).isEqualTo(Instant.parse("2026-11-20T06:00:00Z"));
        assertThat(e.organizer().name()).isEqualTo("Sharma, Anil");
        assertThat(e.organizer().email()).isEqualTo("anil@corp.example");
        assertThat(e.description()).contains("1. Budget, fees").doesNotContain("Reminder");
        assertThat(e.joinUrl()).isEqualTo("https://teams.microsoft.com/l/meetup-join/19%3aabc");

        Ics.Event day = Ics.parse("BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:x\nSUMMARY:Annual day\nDTSTART;VALUE=DATE:20261205\nEND:VEVENT\nEND:VCALENDAR", IST);
        assertThat(day.allDay()).isTrue();
        assertThat(day.start()).isEqualTo(LocalDate.of(2026, 12, 5).atStartOfDay(IST).toInstant());
        assertThat(day.endDate()).isEqualTo(LocalDate.of(2026, 12, 6));
        assertThat(day.method()).isEqualTo("PUBLISH");

        String reply = Ics.reply(e, "tiru@magizhchi.test", "Tiru", "tentative", "A very long note that goes on and on so the folding of long lines gets tested properly here", Instant.parse("2026-11-07T00:00:00Z"));
        for (String line : reply.split("\r\n")) assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
        assertThat(reply.replace("\r\n ", "")).contains("COMMENT:A very long note that goes on and on so the folding of long lines gets tested properly here")
                .contains("PARTSTAT=TENTATIVE").contains("DTSTART:20261120T053000Z").contains("ORGANIZER;CN=\"Sharma, Anil\":mailto:anil@corp.example");
        assertThat(Ics.parse(reply, IST).method()).isEqualTo("REPLY");
    }

    // ── fake Magizhchi PA ─────────────────────────────────────────

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
        String method = ex.getRequestMethod();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        paCalls.add(method + " " + path);
        if (path.equals("/api/v1/auth/sso")) { reply(ex, 200, "{\"accessToken\":\"pa-1\",\"expiresIn\":900}"); return; }
        if (path.equals("/api/v1/assistant/status")) { reply(ex, 200, "{\"enabled\":true}"); return; }
        if (path.equals("/api/v1/calendars")) { reply(ex, 200, "[{\"id\":\"cal-1\",\"name\":\"Work\",\"color\":\"#0e7c86\",\"isDefault\":true}]"); return; }
        if (path.equals("/api/v1/tasks")) { reply(ex, 200, "[]"); return; }
        if (path.equals("/api/v1/events") && method.equals("POST")) {
            paEvents.add(JSON.readTree(body));
            reply(ex, 201, "{\"id\":\"ev-77\"}");
            return;
        }
        if (path.startsWith("/api/v1/events/") && method.equals("DELETE")) { reply(ex, 204, ""); return; }
        if (path.equals("/api/v1/events")) {
            // A meeting at 3:15 on the invite's day, and one today (for the briefing).
            Instant clash = MEETING_DAY.atTime(15, 15).atZone(IST).toInstant();
            Instant today = Instant.now().plus(3, ChronoUnit.HOURS);
            String q = ex.getRequestURI().getRawQuery();
            String from = java.net.URLDecoder.decode(q.replaceAll(".*from=([^&]+).*", "$1"), StandardCharsets.UTF_8);
            String to = java.net.URLDecoder.decode(q.replaceAll(".*to=([^&]+).*", "$1"), StandardCharsets.UTF_8);
            StringBuilder out = new StringBuilder("[");
            for (Instant at : new Instant[]{clash, today}) {
                if (at.plus(30, ChronoUnit.MINUTES).isBefore(Instant.parse(from)) || !at.isBefore(Instant.parse(to))) continue;
                if (out.length() > 1) out.append(',');
                out.append("{\"eventId\":\"e-").append(at.getEpochSecond()).append("\",\"calendarId\":\"cal-1\",\"title\":\"Fee review\",\"occurrenceStart\":\"")
                        .append(at).append("\",\"occurrenceEnd\":\"").append(at.plus(30, ChronoUnit.MINUTES)).append("\",\"allDay\":false,\"status\":\"CONFIRMED\"}");
            }
            reply(ex, 200, out.append(']').toString());
            return;
        }
        if (path.equals("/api/v1/assistant/complete")) {
            JsonNode req = JSON.readTree(body);
            String system = req.path("system").asText();
            String prompt = req.path("prompt").asText();
            prompts.add(system + "\n" + prompt);
            String text;
            if (system.contains("You write one email")) {
                text = "{\"subject\":\"October fee receipts\",\"body\":\"Hi Ravi,\\n\\nCould you send the October fee receipts by Friday?\\n\\nThanks\"}";
            } else if (system.contains("You rewrite an email draft")) {
                text = "{\"body\":\"Hi Ravi, please share the receipts.\"}";
            } else if (system.contains("question about their own email")) {
                text = "{\"keywords\":[\"hall\",\"advance\"],\"from\":null,\"to\":null,\"after\":null,\"before\":null,\"hasAttachment\":false,\"folder\":\"ALL\",\"wantsAnswer\":true}";
            } else if (system.contains("answer the mailbox owner's question")) {
                Matcher id = Pattern.compile("=== id (\\d+)").matcher(prompt);
                text = "{\"answer\":\"You paid an advance of Rs 5,000.\",\"sources\":[" + (id.find() ? id.group(1) : "") + "]}";
            } else if (system.contains("waiting for the other person")) {
                StringBuilder items = new StringBuilder();
                Matcher m = Pattern.compile("=== id (\\d+) \\| to: [^|]*\\| subject: ([^\\n]*)").matcher(prompt);
                while (m.find()) {
                    if (items.length() > 0) items.append(',');
                    boolean asks = m.group(2).startsWith("Fee");
                    items.append("{\"id\":").append(m.group(1)).append(",\"expects\":").append(asks).append(",\"why\":\"the October fee receipts\"}");
                }
                text = "{\"items\":[" + items + "]}";
            } else if (system.contains("nobody has answered")) {
                text = "{\"body\":\"Hi Ravi, a gentle reminder about the October fee receipts. Could you send them this week?\"}";
            } else if (system.contains("morning briefing")) {
                StringBuilder items = new StringBuilder();
                Matcher m = Pattern.compile("- id (\\d+) \\| from [^|]*\\|[^|]*\\| subject: ([^|]*)").matcher(prompt);
                while (m.find()) {
                    if (items.length() > 0) items.append(',');
                    items.append("{\"id\":").append(m.group(1)).append(",\"line\":\"")
                            .append(m.group(2).startsWith("Hall") ? "Confirm the Saturday hall with Priya" : "Answer the invite").append("\"}");
                }
                text = "{\"summary\":\"A calm day: one meeting and Priya's hall booking.\",\"items\":[" + items + "]}";
            } else {
                Matcher ids = Pattern.compile("- id (\\d+)").matcher(prompt);
                StringBuilder items = new StringBuilder();
                while (ids.find()) items.append(items.length() > 0 ? "," : "").append("{\"id\":").append(ids.group(1)).append(",\"lane\":\"needs_me\"}");
                text = "{\"items\":[" + items + "]}";
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

    private static String last(String systemStart) {
        for (int i = prompts.size() - 1; i >= 0; i--) if (prompts.get(i).contains(systemStart)) return prompts.get(i);
        throw new AssertionError("No AI call: " + systemStart);
    }

    private static MimeMessage plain(String subject, String text, String id) throws Exception {
        MimeMessage m = message(id);
        m.setFrom(new InternetAddress("Priya <priya@example.com>"));
        m.setSubject(subject, "UTF-8");
        m.setText(text, "UTF-8");
        return m;
    }

    /** What Google Calendar sends: the text, then the invite inline as text/calendar; method=REQUEST. */
    private static MimeMessage invite() throws Exception {
        String day = MEETING_DAY.format(DateTimeFormatter.BASIC_ISO_DATE);
        String ics = String.join("\r\n", "BEGIN:VCALENDAR", "PRODID:-//Google Inc//Google Calendar 70.9054//EN", "VERSION:2.0",
                "METHOD:REQUEST", "BEGIN:VEVENT",
                "DTSTART;TZID=Asia/Kolkata:" + day + "T150000", "DTEND;TZID=Asia/Kolkata:" + day + "T154500",
                "DTSTAMP:20261107T000000Z", "ORGANIZER;CN=Karthik:mailto:karthik@example.com", "UID:review-42@example.com",
                "ATTENDEE;CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;CN=tiru@magizhchi.test;X-NUM-GUESTS=0:mailto:tiru@magizhchi.test",
                "ATTENDEE;PARTSTAT=ACCEPTED;CN=Karthik:mailto:karthik@example.com",
                "DESCRIPTION:Quarterly placement review.\\n\\nJoin with Google Meet: https://meet.google.com/abc-defg-hij",
                "LOCATION:Room 4", "SEQUENCE:0", "STATUS:CONFIRMED", "SUMMARY:Placement review", "END:VEVENT", "END:VCALENDAR");
        MimeMessage m = message("<invite-42@example.com>");
        m.setFrom(new InternetAddress("Karthik <karthik@example.com>"));
        m.setSubject("Invitation: Placement review", "UTF-8");
        MimeMultipart alt = new MimeMultipart("alternative");
        MimeBodyPart text = new MimeBodyPart();
        text.setText("Karthik has invited you to Placement review.", "UTF-8");
        alt.addBodyPart(text);
        MimeBodyPart cal = new MimeBodyPart();
        cal.setContent(ics, "text/calendar; charset=UTF-8; method=REQUEST");
        alt.addBodyPart(cal);
        m.setContent(alt);
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
                    return subject.equals(x.getSubject());
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
        String body = res.getContentAsString(StandardCharsets.UTF_8);
        assertThat(res.getStatus()).as(body).isEqualTo(status);
        return body.isEmpty() ? json.createObjectNode() : json.readTree(body);
    }
}
