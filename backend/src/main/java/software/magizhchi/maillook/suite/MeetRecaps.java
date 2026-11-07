package software.magizhchi.maillook.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.magizhchi.maillook.ai.AiService;
import software.magizhchi.maillook.mail.MessageRepo;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * After a meeting in a room started from an email: record who came and who missed it, have the
 * AI write the recap from the meeting's captions, put the action items on the person's PA board,
 * and move the thread back into Needs me so the recap mail is waiting for them.
 *
 * <p>Meet says when a meeting is over (its notice is the fast path); a slow sweep of recently
 * linked rooms covers a missed notice. Both run on one worker, so a meeting is never handled twice.
 */
@Service
public class MeetRecaps {

    private static final Logger log = LoggerFactory.getLogger(MeetRecaps.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private final JdbcTemplate jdbc;
    private final MeetClient meet;
    private final PaClient pa;
    private final AiService ai;
    private final MessageRepo messages;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "meet-recaps");
        t.setDaemon(true);
        return t;
    });

    public MeetRecaps(JdbcTemplate jdbc, MeetClient meet, PaClient pa, AiService ai, MessageRepo messages) {
        this.jdbc = jdbc;
        this.meet = meet;
        this.pa = pa;
        this.ai = ai;
        this.messages = messages;
    }

    record Link(long accountId, long userId, String accountEmail, String threadKey, String cardId, String cardName, Instant createdAt) {}

    /** Meet's notice: this room just emptied. Unknown rooms are ignored. */
    public void roomEnded(String cardId) {
        worker.submit(() -> {
            for (Link l : links("l.card_id = ?", cardId.toLowerCase(Locale.ROOT))) safely(l);
        });
    }

    /** Safety net for a missed notice: recheck rooms linked in the last two weeks. */
    @Scheduled(initialDelay = 120_000, fixedDelayString = "${maillook.meet.recap-sweep-ms:900000}")
    public void sweep() {
        if (!meet.configured()) return;
        worker.submit(() -> {
            OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minusDays(14);
            for (Link l : links("l.created_at > ?", since)) safely(l);
        });
    }

    private List<Link> links(String where, Object arg) {
        return jdbc.query("SELECT l.account_id, a.user_id, a.email, l.thread_key, l.card_id, l.card_name, l.created_at " +
                        "FROM meet_links l JOIN accounts a ON a.id = l.account_id WHERE " + where,
                (rs, i) -> new Link(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getObject(7, OffsetDateTime.class).toInstant()), arg);
    }

    private void safely(Link l) {
        try {
            process(l);
        } catch (Exception e) {
            log.info("Meeting recap for room {} skipped: {}", l.cardId(), e.getMessage());
        }
    }

    /** Handles every finished, not yet recorded meeting of one linked room. Returns how many it recorded. */
    int process(Link l) {
        if (!meet.configured()) return 0;
        String since = l.createdAt().minus(Duration.ofMinutes(15)).toString();
        MeetClient.Reply r = meet.as(l.userId(), "GET", "/api/v1/cards/" + l.cardId() + "/meetings?since=" + enc(since), null);
        if (!r.ok() || r.json() == null || !r.json().isArray()) return 0;
        int recorded = 0;
        for (JsonNode s : r.json()) {
            if (!s.path("ended").asBoolean(false) || s.path("attended").size() < 2 || s.path("minutes").asLong() < 2) continue;
            Instant start = Instant.parse(s.path("startedAt").asText());
            Instant end = Instant.parse(s.path("endedAt").asText());
            Integer seen = jdbc.queryForObject("SELECT COUNT(*) FROM meet_meetings WHERE account_id = ? AND card_id = ? AND started_at = ?",
                    Integer.class, l.accountId(), l.cardId(), utc(start));
            if (seen != null && seen > 0) continue;

            ObjectNode report = JSON.createObjectNode();
            report.set("attended", s.path("attended"));
            report.set("missed", s.path("missed"));
            summarize(l, s, start, end, report);

            long id;
            try {
                jdbc.update("INSERT INTO meet_meetings (account_id, thread_key, card_id, card_name, started_at, ended_at, minutes, report) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        l.accountId(), l.threadKey(), l.cardId(), l.cardName(), utc(start), utc(end), s.path("minutes").asInt(), report.toString());
                id = jdbc.queryForObject("SELECT id FROM meet_meetings WHERE account_id = ? AND card_id = ? AND started_at = ?",
                        Long.class, l.accountId(), l.cardId(), utc(start));
            } catch (DuplicateKeyException dup) {
                continue;
            }
            recorded++;
            int tasks = addTasks(l, report, start);
            if (tasks > 0) jdbc.update("UPDATE meet_meetings SET tasks_added = ? WHERE id = ?", tasks, id);
            backToNeedsMe(l);
        }
        return recorded;
    }

    private void summarize(Link l, JsonNode s, Instant start, Instant end, ObjectNode report) {
        report.put("hasTranscript", false);
        report.putNull("summary");
        report.set("decisions", JSON.createArrayNode());
        report.set("actions", JSON.createArrayNode());
        MeetClient.Reply t = meet.as(l.userId(), "GET", "/api/v1/cards/" + l.cardId() + "/meetings/transcript?from="
                + enc(start.toString()) + "&to=" + enc(end.toString()), null);
        if (!t.ok() || t.json() == null || t.json().isEmpty()) return;
        StringBuilder text = new StringBuilder();
        for (JsonNode line : t.json()) text.append(line.path("speaker").asText("?")).append(": ").append(line.path("text").asText()).append('\n');
        report.put("hasTranscript", true);
        if (!ai.enabled(l.userId())) return;
        List<String> people = new ArrayList<>();
        s.path("attended").forEach(p -> people.add(p.path("name").asText()));
        try {
            JsonNode out = ai.meetingReport(l.userId(), l.cardName() == null ? "Meeting" : l.cardName(), String.join(", ", people), text.toString());
            if (out.hasNonNull("summary")) report.put("summary", out.path("summary").asText());
            ArrayNode decisions = JSON.createArrayNode();
            out.path("decisions").forEach(d -> { if (!d.asText().isBlank()) decisions.add(d.asText().trim()); });
            report.set("decisions", decisions);
            ArrayNode actions = JSON.createArrayNode();
            for (JsonNode a : out.path("actions")) {
                String task = a.path("task").asText("").trim();
                if (task.isEmpty() || actions.size() >= 12) continue;
                ObjectNode n = actions.addObject();
                n.put("owner", a.path("owner").asText("You").trim());
                n.put("task", task);
                String due = a.path("due").asText("");
                if (due.matches("\\d{4}-\\d{2}-\\d{2}")) n.put("due", due); else n.putNull("due");
            }
            report.set("actions", actions);
        } catch (Exception e) {
            log.info("AI recap for room {} not written: {}", l.cardId(), e.getMessage());
        }
    }

    /** One PA task per action item, owner in the title when it isn't the person themself. */
    private int addTasks(Link l, JsonNode report, Instant start) {
        if (!pa.configured() || report.path("actions").isEmpty()) return 0;
        String meeting = (l.cardName() == null ? "the meeting" : "\"" + l.cardName() + "\"") + " on " + DAY.format(start.atZone(ZONE));
        int added = 0;
        for (JsonNode a : report.path("actions")) {
            String owner = a.path("owner").asText("You");
            boolean mine = owner.isBlank() || owner.equalsIgnoreCase("You");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", clip(mine ? a.path("task").asText() : owner + ": " + a.path("task").asText(), 300));
            body.put("description", "From " + meeting + " (Magizhchi Meet).");
            if (a.hasNonNull("due")) {
                try {
                    body.put("dueAt", LocalDate.parse(a.path("due").asText()).atTime(18, 0).atZone(ZONE).toInstant().toString());
                } catch (Exception ignored) {
                }
            }
            body.put("labels", List.of("Magizhchi MailLook", "Meeting"));
            try {
                if (pa.as(l.userId(), "POST", "/api/v1/tasks", body).ok()) added++;
            } catch (Exception e) {
                log.info("PA task from meeting not added: {}", e.getMessage());
                break;
            }
        }
        return added;
    }

    /** The thread's latest inbox mail goes back to Needs me, where the recap is waiting. */
    private void backToNeedsMe(Link l) {
        List<MessageRepo.Row> thread = messages.thread(l.accountId(), l.threadKey());
        for (int i = thread.size() - 1; i >= 0; i--) {
            MessageRepo.Row m = thread.get(i);
            if ("INBOX".equals(m.folder())) {
                if (!"user".equals(m.laneSource())) messages.setLane(m.id(), "needs_me", "meet");
                return;
            }
        }
    }

    // ── reading back ──────────────────────────────────────────────

    /** The meetings held in this thread's rooms, newest first, with the recap and catch-up mail ready. */
    public List<Map<String, Object>> forThread(long accountId, String threadKey, String accountEmail) {
        return jdbc.query("SELECT id, card_id, card_name, started_at, ended_at, minutes, report, tasks_added FROM meet_meetings " +
                "WHERE account_id = ? AND thread_key = ? ORDER BY started_at DESC", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            Instant start = rs.getObject("started_at", OffsetDateTime.class).toInstant();
            String name = rs.getString("card_name");
            JsonNode report;
            try {
                report = JSON.readTree(rs.getString("report"));
            } catch (Exception e) {
                report = JSON.createObjectNode();
            }
            m.put("id", rs.getLong("id"));
            m.put("roomId", rs.getString("card_id"));
            m.put("link", meet.roomLink(rs.getString("card_id")));
            m.put("name", name);
            m.put("startedAt", start.toString());
            m.put("endedAt", rs.getObject("ended_at", OffsetDateTime.class).toInstant().toString());
            m.put("minutes", rs.getInt("minutes"));
            m.put("tasksAdded", rs.getInt("tasks_added"));
            m.put("hasTranscript", report.path("hasTranscript").asBoolean(false));
            m.put("summary", report.path("summary").isTextual() ? report.path("summary").asText() : null);
            m.put("decisions", texts(report.path("decisions")));
            List<Map<String, Object>> actions = new ArrayList<>();
            report.path("actions").forEach(a -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("owner", a.path("owner").asText("You"));
                x.put("task", a.path("task").asText());
                x.put("due", a.path("due").isTextual() ? a.path("due").asText() : null);
                actions.add(x);
            });
            m.put("actions", actions);
            m.put("attended", people(report.path("attended")));
            m.put("missed", people(report.path("missed")));
            m.put("attendeeEmails", emails(report.path("attended"), accountEmail));
            m.put("missedEmails", emails(report.path("missed"), accountEmail));
            m.put("recapBody", recapBody(name, start, rs.getInt("minutes"), report));
            m.put("catchUpBody", catchUpBody(name, start, report));
            m.put("couldBeEmail", couldBeEmail(report, rs.getInt("minutes")));
            return m;
        }, accountId, threadKey);
    }

    /**
     * "This meeting could have been an email": it had captions, and all it came to is a summary of two
     * sentences or less, with nothing decided and nothing for anyone to do.
     */
    static boolean couldBeEmail(JsonNode report, int minutes) {
        if (!report.path("hasTranscript").asBoolean(false) || minutes < 5) return false;
        if (report.path("decisions").size() > 0 || report.path("actions").size() > 0) return false;
        String s = report.path("summary").asText("").trim();
        if (s.isEmpty()) return false;
        int sentences = s.split("(?<=[.!?])\\s+").length;
        return sentences <= 2 && s.length() <= 280;
    }

    static String recapBody(String name, Instant start, int minutes, JsonNode report) {
        StringBuilder b = new StringBuilder("Thanks for joining ")
                .append(name == null ? "the meeting" : "\"" + name + "\"")
                .append(" on ").append(DAY.format(start.atZone(ZONE))).append(" (").append(minutes).append(" min).\n");
        body(b, report);
        List<String> names = new ArrayList<>();
        report.path("attended").forEach(p -> names.add(p.path("name").asText()));
        if (!names.isEmpty()) b.append("\nAttended: ").append(String.join(", ", names)).append('\n');
        return b.toString();
    }

    static String catchUpBody(String name, Instant start, JsonNode report) {
        StringBuilder b = new StringBuilder("Sorry we missed you at ")
                .append(name == null ? "the meeting" : "\"" + name + "\"")
                .append(" on ").append(DAY.format(start.atZone(ZONE))).append(". Here's what you missed.\n");
        body(b, report);
        if (!report.path("summary").isTextual() && report.path("decisions").isEmpty() && report.path("actions").isEmpty()) {
            b.append("\nHappy to catch you up on a quick call.\n");
        }
        return b.toString();
    }

    private static void body(StringBuilder b, JsonNode report) {
        if (report.path("summary").isTextual()) b.append('\n').append(report.path("summary").asText()).append('\n');
        if (!report.path("decisions").isEmpty()) {
            b.append("\nDecided\n");
            report.path("decisions").forEach(d -> b.append("- ").append(d.asText()).append('\n'));
        }
        if (!report.path("actions").isEmpty()) {
            b.append("\nNext steps\n");
            report.path("actions").forEach(a -> {
                b.append("- ").append(a.path("owner").asText("You")).append(": ").append(a.path("task").asText());
                if (a.path("due").isTextual()) {
                    try {
                        b.append(" (by ").append(DAY.format(LocalDate.parse(a.path("due").asText()))).append(')');
                    } catch (Exception ignored) {
                    }
                }
                b.append('\n');
            });
        }
    }

    private static List<String> texts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static List<Map<String, Object>> people(JsonNode arr) {
        List<Map<String, Object>> out = new ArrayList<>();
        arr.forEach(p -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("name", p.path("name").asText());
            x.put("email", p.path("email").isTextual() ? p.path("email").asText() : null);
            x.put("minutes", p.path("minutes").asLong());
            out.add(x);
        });
        return out;
    }

    /** Addresses to write to: everyone listed except the mailbox owner, deduplicated. */
    private static List<String> emails(JsonNode arr, String own) {
        List<String> out = new ArrayList<>();
        arr.forEach(p -> {
            String e = p.path("email").asText("").trim().toLowerCase(Locale.ROOT);
            if (!e.isEmpty() && e.contains("@") && !e.equalsIgnoreCase(own) && !out.contains(e)) out.add(e);
        });
        return out;
    }

    private static OffsetDateTime utc(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
