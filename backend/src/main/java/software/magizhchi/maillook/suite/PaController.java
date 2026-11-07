package software.magizhchi.maillook.suite;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.magizhchi.maillook.common.ApiException;
import software.magizhchi.maillook.mail.MailActions;
import software.magizhchi.maillook.mail.MessageParser;
import software.magizhchi.maillook.mail.MessageRepo;
import software.magizhchi.maillook.mail.NoteRepo;
import org.springframework.web.bind.annotation.RequestParam;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** MailLook → Magizhchi PA: connection status, and turning an email into a PA task or calendar event. */
@RestController
@RequestMapping("/api")
public class PaController {

    public record TaskRequest(String title, String dueAt, String priority, String notes) {}
    public record EventRequest(String title, String startAt, Integer durationMinutes, String calendarId) {}

    private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH");

    private final PaClient pa;
    private final MailActions actions;
    private final NoteRepo notes;

    public PaController(PaClient pa, MailActions actions, NoteRepo notes) {
        this.pa = pa;
        this.actions = actions;
        this.notes = notes;
    }

    /**
     * Your Magizhchi PA calendar for a stretch of days (at most 6 weeks): events, with repeats expanded,
     * and open tasks due in it.
     */
    @GetMapping("/pa/agenda")
    public Map<String, Object> agenda(@RequestAttribute long userId, @RequestParam String from, @RequestParam String to) {
        Instant a = instant(from, "start");
        Instant b = instant(to, "end");
        if (!b.isAfter(a) || Duration.between(a, b).toDays() > 42) throw ApiException.bad("Show between a day and six weeks at a time.");

        Map<String, String> colors = new LinkedHashMap<>();
        List<Map<String, Object>> cals = new ArrayList<>();
        PaClient.Reply cr = pa.as(userId, "GET", "/api/v1/calendars", null);
        if (cr.ok() && cr.json() != null) {
            for (JsonNode c : cr.json()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.path("id").asText());
                m.put("name", c.path("name").asText());
                m.put("color", c.path("color").asText(null));
                cals.add(m);
                colors.put(c.path("id").asText(), c.path("color").asText(null));
            }
        } else {
            throw failed("load your calendar", cr);
        }

        PaClient.Reply er = pa.as(userId, "GET", "/api/v1/events?from=" + enc(a.toString()) + "&to=" + enc(b.toString()), null);
        if (!er.ok() || er.json() == null) throw failed("load your calendar", er);
        List<Map<String, Object>> events = new ArrayList<>();
        for (JsonNode e : er.json()) {
            if ("CANCELLED".equalsIgnoreCase(e.path("status").asText(""))) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.path("eventId").asText());
            m.put("calendarId", e.path("calendarId").asText(null));
            m.put("title", e.path("title").asText("(no title)"));
            m.put("location", e.path("location").asText(null));
            m.put("description", clip(e.path("description").asText(""), 600));
            m.put("start", e.path("occurrenceStart").asText(null));
            m.put("end", e.path("occurrenceEnd").asText(null));
            m.put("allDay", e.path("allDay").asBoolean(false));
            m.put("recurring", e.path("recurring").asBoolean(false));
            m.put("response", e.path("attendeeResponse").asText(null));
            m.put("organizer", e.path("organizerName").asText(null));
            String color = e.path("color").asText(null);
            m.put("color", color == null || color.isBlank() ? colors.get(e.path("calendarId").asText()) : color);
            events.add(m);
        }

        // Tasks are a nice-to-have here: if PA can't list them, the calendar still shows.
        List<Map<String, Object>> tasks = new ArrayList<>();
        PaClient.Reply tr = pa.as(userId, "GET", "/api/v1/tasks", null);
        if (tr.ok() && tr.json() != null) {
            for (JsonNode t : tr.json()) {
                if ("DONE".equalsIgnoreCase(t.path("status").asText(""))) continue;
                String due = t.path("dueAt").asText(null);
                if (due == null || due.isBlank()) continue;
                Instant d;
                try {
                    d = Instant.parse(due);
                } catch (DateTimeParseException ex) {
                    continue;
                }
                if (d.isBefore(a) || !d.isBefore(b)) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", t.path("id").asText());
                m.put("title", t.path("title").asText("(untitled task)"));
                m.put("dueAt", d.toString());
                m.put("priority", t.path("priority").asText(null));
                m.put("status", t.path("status").asText(null));
                tasks.add(m);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("calendars", cals);
        out.put("events", events);
        out.put("tasks", tasks);
        out.put("webUrl", pa.webUrl().isBlank() ? null : pa.webUrl());
        return out;
    }

    /** Copies the note on this conversation into Magizhchi PA Notes (updating the copy made before). */
    @PostMapping("/messages/{id}/pa/note")
    public Map<String, Object> note(@RequestAttribute long userId, @PathVariable long id) {
        MessageRepo.Row msg = actions.owned(userId, id);
        NoteRepo.Note n = notes.find(msg.accountId(), msg.threadKey())
                .orElseThrow(() -> ApiException.bad("Write a note first, then send it to Magizhchi PA."));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", clip("Note: " + defaultTitle(msg), 300));
        body.put("content", noteHtml(n.body(), msg));
        body.put("labels", List.of("Magizhchi MailLook"));
        PaClient.Reply r = null;
        if (n.paNoteId() != null) {
            r = pa.as(userId, "PUT", "/api/v1/notes/" + enc(n.paNoteId()), body);
            if (r.code() == 404) r = null;             // they deleted it in PA: make a new one
        }
        if (r == null) r = pa.as(userId, "POST", "/api/v1/notes", body);
        if (!r.ok()) throw failed("save the note", r);
        String paId = r.json() == null ? null : r.json().path("id").asText(null);
        if (paId != null) notes.setPaNote(msg.accountId(), msg.threadKey(), paId);
        return done(r, "Your note is in Magizhchi PA Notes.");
    }

    private static String noteHtml(String note, MessageRepo.Row m) {
        StringBuilder sb = new StringBuilder();
        for (String para : note.split("\\n\\s*\\n")) {
            sb.append("<p>").append(esc(para.trim()).replace("\n", "<br>")).append("</p>");
        }
        sb.append("<p><em>About the email “").append(esc(m.subject() == null ? "(no subject)" : m.subject()))
                .append("” from ").append(esc(blank(m.fromName()) ? String.valueOf(m.fromEmail()) : m.fromName()))
                .append(" in ").append(esc(m.accountEmail())).append(". Added from Magizhchi MailLook.</em></p>");
        return sb.toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @GetMapping("/pa/status")
    public Map<String, Object> status(@RequestAttribute long userId) {
        return pa.status(userId);
    }

    @GetMapping("/pa/calendars")
    public List<Map<String, Object>> calendars(@RequestAttribute long userId) {
        PaClient.Reply r = pa.as(userId, "GET", "/api/v1/calendars", null);
        if (!r.ok() || r.json() == null) throw failed("load your calendars", r);
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode c : r.json()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.path("id").asText());
            m.put("name", c.path("name").asText());
            m.put("color", c.path("color").asText(null));
            m.put("isDefault", c.path("isDefault").asBoolean(false));
            out.add(m);
        }
        return out;
    }

    @PostMapping("/messages/{id}/pa/task")
    public Map<String, Object> task(@RequestAttribute long userId, @PathVariable long id, @RequestBody(required = false) TaskRequest req) {
        MessageRepo.Row msg = actions.owned(userId, id);
        TaskRequest t = req == null ? new TaskRequest(null, null, null, null) : req;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", clip(blank(t.title()) ? defaultTitle(msg) : t.title().trim(), 300));
        body.put("description", clip(describe(msg, t.notes()), 5000));
        if (!blank(t.dueAt())) body.put("dueAt", instant(t.dueAt(), "due date").toString());
        if (!blank(t.priority())) {
            String p = t.priority().trim().toUpperCase();
            if (!PRIORITIES.contains(p)) throw ApiException.bad("Priority must be low, medium or high.");
            body.put("priority", p);
        }
        body.put("labels", List.of("Magizhchi MailLook"));
        PaClient.Reply r = pa.as(userId, "POST", "/api/v1/tasks", body);
        if (!r.ok()) throw failed("add the task", r);
        return done(r, "Task \"" + body.get("title") + "\" is on your Magizhchi PA board.");
    }

    @PostMapping("/messages/{id}/pa/event")
    public Map<String, Object> event(@RequestAttribute long userId, @PathVariable long id, @RequestBody EventRequest req) {
        if (req == null || blank(req.startAt())) throw ApiException.bad("Pick when the event starts.");
        MessageRepo.Row msg = actions.owned(userId, id);
        Instant start = instant(req.startAt(), "start time");
        int minutes = req.durationMinutes() == null ? 30 : req.durationMinutes();
        if (minutes < 5 || minutes > 24 * 60) throw ApiException.bad("Length must be between 5 minutes and a day.");
        String calendarId = blank(req.calendarId()) ? defaultCalendar(userId) : req.calendarId().trim();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("calendarId", calendarId);
        body.put("title", clip(blank(req.title()) ? defaultTitle(msg) : req.title().trim(), 300));
        body.put("description", clip(describe(msg, null), 5000));
        body.put("startAt", start.toString());
        body.put("endAt", start.plusSeconds(60L * minutes).toString());
        body.put("allDay", false);
        body.put("reminderMinutes", List.of(30));
        PaClient.Reply r = pa.as(userId, "POST", "/api/v1/events", body);
        if (!r.ok()) throw failed("add the event", r);
        return done(r, "Event \"" + body.get("title") + "\" is in your Magizhchi PA calendar.");
    }

    // ── helpers ───────────────────────────────────────────────────

    private String defaultCalendar(long userId) {
        List<Map<String, Object>> cals = calendars(userId);
        if (cals.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "You have no calendar in Magizhchi PA yet.");
        return cals.stream().filter(c -> Boolean.TRUE.equals(c.get("isDefault"))).findFirst().orElse(cals.get(0)).get("id").toString();
    }

    private Map<String, Object> done(PaClient.Reply r, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "done");
        m.put("id", r.json() == null ? null : r.json().path("id").asText(null));
        m.put("message", message);
        m.put("webUrl", pa.webUrl().isBlank() ? null : pa.webUrl());
        return m;
    }

    private static ApiException failed(String what, PaClient.Reply r) {
        if (r.code() == 0) return new ApiException(HttpStatus.BAD_GATEWAY, "Can't reach Magizhchi PA right now, so it couldn't " + what + ". Try again in a minute.");
        String said = r.message();
        return new ApiException(HttpStatus.BAD_GATEWAY, "Magizhchi PA couldn't " + what + " (" + r.code() + ")" + (said == null ? "." : ": " + said));
    }

    private static String defaultTitle(MessageRepo.Row m) {
        String s = m.subject() == null ? "" : m.subject().replaceAll("(?i)^((re|fwd?)\\s*:\\s*)+", "").trim();
        if (!s.isEmpty()) return s;
        return "Email from " + (blank(m.fromName()) ? m.fromEmail() : m.fromName());
    }

    private static String describe(MessageRepo.Row m, String notes) {
        StringBuilder sb = new StringBuilder();
        if (!blank(notes)) sb.append(notes.trim()).append("\n\n");
        sb.append("From: ").append(blank(m.fromName()) ? "" : m.fromName() + " ").append('<').append(m.fromEmail()).append(">\n");
        sb.append("Subject: ").append(m.subject() == null ? "" : m.subject()).append('\n');
        sb.append("Received: ").append(m.receivedAt() == null ? "" : m.receivedAt().toInstant()).append('\n');
        sb.append("Mailbox: ").append(m.accountEmail()).append("\n\n");
        String body = MessageParser.ownText(m.bodyText());
        sb.append(body.length() > 3000 ? body.substring(0, 3000) + "…" : body);
        sb.append("\n\n(Added from Magizhchi MailLook)");
        return sb.toString();
    }

    private static Instant instant(String s, String what) {
        try {
            return Instant.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.bad("The " + what + " isn't a valid date and time.");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
