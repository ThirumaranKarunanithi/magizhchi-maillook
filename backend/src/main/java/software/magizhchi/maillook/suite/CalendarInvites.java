package software.magizhchi.maillook.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.magizhchi.maillook.common.ApiException;
import software.magizhchi.maillook.mail.MailActions;
import software.magizhchi.maillook.mail.MessageRepo;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Meeting invites in your mail (an .ics from Google Calendar, Outlook, Zoom, Teams…): see when it is,
 * whether it clashes with your PA calendar, and answer Yes / Maybe / No. The organizer's calendar
 * gets the answer, and a yes or maybe puts the meeting in Magizhchi PA.
 */
@RestController
@RequestMapping("/api/messages/{id}/invite")
public class CalendarInvites {

    private static final Logger log = LoggerFactory.getLogger(CalendarInvites.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public record RespondRequest(String answer, Boolean addToCalendar, String comment) {}

    private final MailActions actions;
    private final MessageRepo messages;
    private final PaClient pa;
    private final JdbcTemplate jdbc;

    public CalendarInvites(MailActions actions, MessageRepo messages, PaClient pa, JdbcTemplate jdbc) {
        this.actions = actions;
        this.messages = messages;
        this.pa = pa;
        this.jdbc = jdbc;
    }

    @GetMapping
    public Map<String, Object> show(@RequestAttribute long userId, @PathVariable long id, @RequestParam(required = false) String tz) {
        MessageRepo.Row msg = actions.owned(userId, id);
        Ics.Event e = event(userId, msg, zone(tz));
        if (e == null) return Map.of("found", false);
        Map<String, Object> out = describe(e, msg);
        out.put("found", true);
        // What else is on the PA calendar then.
        List<Map<String, Object>> clashes = new ArrayList<>();
        boolean checked = false;
        if (pa.configured() && !e.cancelled()) {
            try {
                PaClient.Reply r = pa.as(userId, "GET", "/api/v1/events?from=" + enc(e.start().toString()) + "&to=" + enc(e.end().toString()), null);
                if (r.ok() && r.json() != null && r.json().isArray()) {
                    checked = true;
                    for (JsonNode ev : r.json()) {
                        if ("CANCELLED".equalsIgnoreCase(ev.path("status").asText(""))) continue;
                        String title = ev.path("title").asText("");
                        // The meeting itself, already added from here or synced by PA.
                        if (e.title() != null && title.equalsIgnoreCase(e.title())) continue;
                        Map<String, Object> c = new LinkedHashMap<>();
                        c.put("title", title.isBlank() ? "Busy" : title);
                        c.put("start", ev.path("occurrenceStart").asText(ev.path("startAt").asText(null)));
                        c.put("end", ev.path("occurrenceEnd").asText(ev.path("endAt").asText(null)));
                        c.put("allDay", ev.path("allDay").asBoolean(false));
                        clashes.add(c);
                    }
                }
            } catch (ApiException ex) {
                log.info("PA calendar not read for the invite: {}", ex.getMessage());
            }
        }
        out.put("calendarChecked", checked);
        out.put("clashes", clashes);
        out.put("paConnected", pa.configured());
        messages.aiCache(msg.id(), "invite_reply").ifPresent(s -> {
            try {
                out.put("replied", JSON.readTree(s));
            } catch (Exception ignored) {
            }
        });
        return out;
    }

    @PostMapping("/respond")
    public Map<String, Object> respond(@RequestAttribute long userId, @PathVariable long id, @RequestBody RespondRequest req,
                                       @RequestParam(required = false) String tz) {
        String answer = req == null || req.answer() == null ? "" : req.answer().trim().toLowerCase(Locale.ROOT);
        if (!List.of("accepted", "tentative", "declined").contains(answer)) throw ApiException.bad("Answer yes, maybe or no.");
        MessageRepo.Row msg = actions.owned(userId, id);
        Ics.Event e = event(userId, msg, zone(tz));
        if (e == null) throw ApiException.notFound("Invite");
        if (e.cancelled()) throw ApiException.bad("This meeting was cancelled.");
        if (e.uid() == null || e.organizer() == null || e.organizer().email() == null)
            throw ApiException.bad("This invite doesn't say who to answer, so it can't be answered from here.");
        if (e.organizer().email().equalsIgnoreCase(msg.accountEmail())) throw ApiException.bad("You organised this meeting.");
        String comment = req.comment() == null ? null : req.comment().trim();
        if (comment != null && comment.length() > 1000) throw ApiException.bad("Keep the note under 1,000 characters.");

        String me = msg.accountEmail();
        String myName = jdbc.queryForList("SELECT display_name FROM accounts WHERE id = ?", String.class, msg.accountId())
                .stream().filter(s -> s != null && !s.isBlank()).findFirst().orElse(null);
        String who = myName == null ? me : myName;
        String verb = switch (answer) {
            case "accepted" -> "Accepted";
            case "tentative" -> "Tentative";
            default -> "Declined";
        };
        String title = e.title() == null ? "Meeting" : e.title();
        String text = who + (switch (answer) {
            case "accepted" -> " has accepted this invitation.";
            case "tentative" -> " might attend (tentative).";
            default -> " has declined this invitation.";
        }) + (comment == null || comment.isBlank() ? "" : "\n\n" + comment);
        actions.sendCalendarReply(userId, msg, e.organizer().email(), verb + ": " + title, text,
                Ics.reply(e, me, myName, answer, comment, Instant.now()));

        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put("answer", answer);
        saved.put("at", Instant.now().toString());
        String paEventId = null;
        String calendarNote = null;
        JsonNode previous = messages.aiCache(msg.id(), "invite_reply").map(CalendarInvites::read).orElse(null);
        String earlierPa = previous == null ? null : previous.path("paEventId").asText(null);
        if (!"declined".equals(answer) && !Boolean.FALSE.equals(req.addToCalendar()) && pa.configured()) {
            if (earlierPa != null) {
                paEventId = earlierPa;                      // already in the calendar from an earlier yes
            } else {
                try {
                    paEventId = addToPa(userId, e, msg);
                    calendarNote = "It's in your Magizhchi PA calendar.";
                } catch (ApiException ex) {
                    calendarNote = "Your answer went to " + e.organizer().email() + ", but it couldn't go in your PA calendar: " + ex.getMessage();
                }
            }
        } else if ("declined".equals(answer) && earlierPa != null) {
            try {
                PaClient.Reply r = pa.as(userId, "DELETE", "/api/v1/events/" + earlierPa, null);
                if (r.ok() || r.code() == 404) calendarNote = "It's off your Magizhchi PA calendar.";
                else paEventId = earlierPa;
            } catch (ApiException ex) {
                paEventId = earlierPa;
            }
        }
        saved.put("paEventId", paEventId);
        try {
            messages.putAiCache(msg.id(), "invite_reply", JSON.writeValueAsString(saved));
        } catch (Exception ignored) {
        }
        Map<String, Object> out = new LinkedHashMap<>(saved);
        String said = switch (answer) {
            case "accepted" -> "You said yes";
            case "tentative" -> "You said maybe";
            default -> "You said no";
        };
        out.put("message", said + " to \"" + title + "\"." + (calendarNote == null ? "" : " " + calendarNote));
        out.put("webUrl", pa.webUrl().isBlank() ? null : pa.webUrl());
        return out;
    }

    /** The cancelled meeting comes off the PA calendar, when it was put there from here. */
    @PostMapping("/remove")
    public Map<String, Object> remove(@RequestAttribute long userId, @PathVariable long id) {
        MessageRepo.Row msg = actions.owned(userId, id);
        JsonNode previous = messages.aiCache(msg.id(), "invite_reply").map(CalendarInvites::read).orElse(null);
        String eventId = previous == null ? null : previous.path("paEventId").asText(null);
        if (eventId == null) {
            // The cancellation is a new mail; the yes was on the original invite in the same thread.
            for (MessageRepo.Row t : messages.thread(msg.accountId(), msg.threadKey())) {
                JsonNode p = messages.aiCache(t.id(), "invite_reply").map(CalendarInvites::read).orElse(null);
                if (p != null && p.path("paEventId").isTextual()) {
                    eventId = p.path("paEventId").asText();
                    previous = p;
                    msg = t;
                    break;
                }
            }
        }
        if (eventId == null) throw ApiException.bad("This meeting wasn't added to your PA calendar from MailLook.");
        PaClient.Reply r = pa.as(userId, "DELETE", "/api/v1/events/" + eventId, null);
        if (!r.ok() && r.code() != 404) throw new ApiException(HttpStatus.BAD_GATEWAY, "Magizhchi PA couldn't remove it (" + r.code() + ").");
        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put("answer", previous.path("answer").asText("accepted"));
        saved.put("at", previous.path("at").asText(null));
        saved.put("paEventId", null);
        try {
            messages.putAiCache(msg.id(), "invite_reply", JSON.writeValueAsString(saved));
        } catch (Exception ignored) {
        }
        return Map.of("message", "Removed from your Magizhchi PA calendar.");
    }

    // ── helpers ───────────────────────────────────────────────────

    /** The invite in this mail: sent inside it, or as an .ics file (read once, then kept). */
    private Ics.Event event(long userId, MessageRepo.Row msg, ZoneId zone) {
        String ics = messages.calendar(msg.id()).orElse(null);
        if (ics == null && msg.attachments() != null) {
            for (Map<String, Object> a : msg.attachments()) {
                String type = String.valueOf(a.getOrDefault("contentType", "")).toLowerCase(Locale.ROOT);
                String name = String.valueOf(a.getOrDefault("name", "")).toLowerCase(Locale.ROOT);
                if (!(type.equals("text/calendar") || type.equals("application/ics") || name.endsWith(".ics"))) continue;
                Object idx = a.get("index");
                if (!(idx instanceof Number n)) continue;
                MailActions.Download d = actions.attachment(userId, msg.id(), n.intValue());
                if (d.bytes().length > 500_000) continue;
                ics = new String(d.bytes(), StandardCharsets.UTF_8);
                jdbc.update("UPDATE messages SET calendar_ics = ? WHERE id = ?", ics, msg.id());
                break;
            }
        }
        try {
            return ics == null ? null : Ics.parse(ics, zone);
        } catch (RuntimeException ex) {
            log.info("Invite in message {} couldn't be read: {}", msg.id(), ex.toString());
            return null;
        }
    }

    private Map<String, Object> describe(Ics.Event e, MessageRepo.Row msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("method", e.method());
        m.put("title", e.title());
        m.put("start", e.start().toString());
        m.put("end", e.end().toString());
        m.put("allDay", e.allDay());
        m.put("location", e.location());
        m.put("joinUrl", e.joinUrl());
        m.put("cancelled", e.cancelled());
        m.put("past", e.end().isBefore(Instant.now()));
        if (e.organizer() != null) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", e.organizer().name());
            o.put("email", e.organizer().email());
            m.put("organizer", o);
        }
        m.put("attendees", e.attendees().size());
        String mine = e.attendees().stream().filter(p -> msg.accountEmail().equalsIgnoreCase(p.email()))
                .map(Ics.Person::status).findFirst().orElse(null);
        m.put("myStatus", mine);
        boolean organizer = e.organizer() != null && msg.accountEmail().equalsIgnoreCase(e.organizer().email());
        m.put("youOrganised", organizer);
        m.put("canAnswer", !organizer && !e.cancelled() && e.uid() != null && e.organizer() != null && e.organizer().email() != null
                && ("REQUEST".equals(e.method()) || "PUBLISH".equals(e.method())) && !e.end().isBefore(Instant.now()));
        return m;
    }

    private String addToPa(long userId, Ics.Event e, MessageRepo.Row msg) {
        PaClient.Reply cals = pa.as(userId, "GET", "/api/v1/calendars", null);
        if (!cals.ok() || cals.json() == null || !cals.json().isArray() || cals.json().isEmpty())
            throw new ApiException(HttpStatus.CONFLICT, "you have no calendar in Magizhchi PA yet.");
        String calendarId = null;
        for (JsonNode c : cals.json()) if (c.path("isDefault").asBoolean(false)) calendarId = c.path("id").asText();
        if (calendarId == null) calendarId = cals.json().get(0).path("id").asText();

        StringBuilder desc = new StringBuilder();
        if (e.joinUrl() != null) desc.append("Join: ").append(e.joinUrl()).append("\n\n");
        if (e.organizer() != null) {
            desc.append("Organizer: ").append(e.organizer().name() == null ? "" : e.organizer().name() + " ")
                    .append('<').append(e.organizer().email()).append(">\n");
        }
        if (e.description() != null && !e.description().isBlank()) desc.append('\n').append(clip(e.description(), 3000)).append('\n');
        desc.append("\n(Added from a Magizhchi MailLook invite to ").append(msg.accountEmail()).append(')');

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("calendarId", calendarId);
        body.put("title", clip(e.title() == null ? "Meeting" : e.title(), 300));
        body.put("description", clip(desc.toString(), 5000));
        if (e.location() != null) body.put("location", clip(e.location(), 300));
        body.put("startAt", e.start().toString());
        body.put("endAt", e.end().toString());
        body.put("allDay", e.allDay());
        body.put("reminderMinutes", List.of(e.allDay() ? 600 : 15));
        PaClient.Reply r = pa.as(userId, "POST", "/api/v1/events", body);
        if (!r.ok()) throw new ApiException(HttpStatus.BAD_GATEWAY, "Magizhchi PA answered " + r.code() + (r.message() == null ? "." : ": " + r.message()));
        return r.json() == null ? null : r.json().path("id").asText(null);
    }

    private static JsonNode read(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static ZoneId zone(String tz) {
        try {
            return tz == null || tz.isBlank() ? ZoneId.of("Asia/Kolkata") : ZoneId.of(tz);
        } catch (DateTimeException e) {
            return ZoneId.of("Asia/Kolkata");
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
