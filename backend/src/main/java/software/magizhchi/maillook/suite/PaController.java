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

    public PaController(PaClient pa, MailActions actions) {
        this.pa = pa;
        this.actions = actions;
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
