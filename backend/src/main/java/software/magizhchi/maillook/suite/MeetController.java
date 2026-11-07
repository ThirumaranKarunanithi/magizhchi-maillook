package software.magizhchi.maillook.suite;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.magizhchi.maillook.ai.AiService;
import software.magizhchi.maillook.common.ApiException;
import software.magizhchi.maillook.mail.MailActions;
import software.magizhchi.maillook.mail.MessageRepo;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MailLook → Magizhchi Meet: start a room from an email ("Meet now"), show the Meet rooms a mail
 * points at (join card), and the two classroom actions: move a batch's weekly time, and let a
 * student attend today's class online. MailLook never emails invitees or writes PA events itself:
 * Meet does both. It only drafts the person's own reply in the thread.
 */
@RestController
@RequestMapping("/api")
public class MeetController {

    private static final Logger log = LoggerFactory.getLogger(MeetController.class);

    public record StartRequest(String name, List<String> emails, String startAt, Integer minutes) {}
    public record RescheduleRequest(String roomId, String newTime) {}
    public record AddRequest(String roomId, String email) {}

    private static final Pattern HHMM = Pattern.compile("^([01]?\\d|2[0-3]):([0-5]\\d)$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern UUID_RE = Pattern.compile("^[0-9a-fA-F-]{36}$");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH);
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Kolkata");

    private final MeetClient meet;
    private final MailActions actions;
    private final MessageRepo messages;
    private final AiService ai;
    private final JdbcTemplate jdbc;
    private final MeetRecaps recaps;
    private final SuiteTokens tokens;

    public MeetController(MeetClient meet, MailActions actions, MessageRepo messages, AiService ai, JdbcTemplate jdbc,
                          MeetRecaps recaps, SuiteTokens tokens) {
        this.recaps = recaps;
        this.tokens = tokens;
        this.meet = meet;
        this.actions = actions;
        this.messages = messages;
        this.ai = ai;
        this.jdbc = jdbc;
    }

    // ── status and rooms ──────────────────────────────────────────

    @GetMapping("/meet/status")
    public Map<String, Object> status(@RequestAttribute long userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", meet.configured());
        m.put("webUrl", meet.configured() ? meet.webUrl() : null);
        if (!meet.configured()) {
            m.put("connected", false);
            m.put("message", "Magizhchi Meet isn't set up on this server.");
            return m;
        }
        try {
            MeetClient.Reply r = meet.as(userId, "GET", "/api/v1/auth/me", null);
            m.put("connected", r.ok());
            m.put("message", r.ok() ? "Connected to Magizhchi Meet." : "Magizhchi Meet answered " + r.code() + ".");
        } catch (ApiException e) {
            m.put("connected", false);
            m.put("message", e.getMessage());
        }
        return m;
    }

    /** Rooms this person runs (admin or owner), for the classroom pickers. Quick huddles are left out. */
    @GetMapping("/meet/rooms")
    public List<Map<String, Object>> rooms(@RequestAttribute long userId) {
        JsonNode cards = meet.call(userId, "GET", "/api/v1/cards", null, "list your rooms");
        List<Map<String, Object>> out = new ArrayList<>();
        if (cards == null || !cards.isArray()) return out;
        for (JsonNode c : cards) {
            String role = c.path("myRole").asText("");
            if (!(role.equals("SUPER_ADMIN") || role.equals("ADMIN")) || c.path("ephemeral").asBoolean(false)) continue;
            out.add(room(c));
        }
        return out;
    }

    // ── join card ─────────────────────────────────────────────────

    /** The Meet rooms this mail points at: links in its body, plus rooms started from its thread. */
    @GetMapping("/messages/{id}/meet")
    public Map<String, Object> forMessage(@RequestAttribute long userId, @PathVariable long id) {
        MessageRepo.Row msg = actions.owned(userId, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", meet.configured());
        List<Map<String, Object>> found = new ArrayList<>();
        out.put("rooms", found);
        out.put("meetings", recaps.forThread(msg.accountId(), msg.threadKey(), msg.accountEmail()));
        if (!meet.configured()) return out;

        Set<String> ids = new LinkedHashSet<>();
        jdbc.queryForList("SELECT card_id FROM meet_links WHERE account_id = ? AND thread_key = ? ORDER BY created_at DESC",
                String.class, msg.accountId(), msg.threadKey()).forEach(ids::add);
        String text = (msg.bodyHtml() == null ? "" : msg.bodyHtml()) + "\n" + (msg.bodyText() == null ? "" : msg.bodyText());
        Matcher m = Pattern.compile(Pattern.quote(meet.webUrl()) + "/room/([0-9a-fA-F-]{36})").matcher(text);
        while (m.find() && ids.size() < 3) ids.add(m.group(1).toLowerCase(Locale.ROOT));

        for (String cardId : ids) {
            Map<String, Object> r;
            try {
                MeetClient.Reply reply = meet.as(userId, "GET", "/api/v1/cards/" + cardId, null);
                if (reply.ok() && reply.json() != null) {
                    r = room(reply.json());
                } else {
                    // Not a member yet (or the room is gone): the link still works for joining.
                    r = new LinkedHashMap<>();
                    r.put("id", cardId);
                    r.put("name", nameFromLink(msg, cardId));
                    r.put("link", meet.roomLink(cardId));
                    r.put("known", false);
                }
            } catch (ApiException e) {
                r = new LinkedHashMap<>(Map.of("id", cardId, "link", meet.roomLink(cardId), "known", false));
            }
            found.add(r);
        }
        return out;
    }

    // ── after the meeting ─────────────────────────────────────────

    /**
     * Meet's "a meeting just ended" notice (no MailLook session: it carries a suite-signed token for
     * the room instead). The recap work happens in the background; rooms MailLook never linked are ignored.
     */
    @PostMapping("/hooks/meet")
    public ResponseEntity<Map<String, Object>> meetingEnded(@RequestHeader(value = "Authorization", required = false) String auth) {
        String token = auth == null ? null : auth.replaceFirst("(?i)^Bearer\\s+", "");
        return tokens.verifyMeetNotice(token)
                .map(card -> {
                    recaps.roomEnded(card);
                    return ResponseEntity.accepted().body(Map.<String, Object>of("ok", true));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not a Magizhchi Meet notice.")));
    }

    // ── Meet now ──────────────────────────────────────────────────

    @PostMapping("/messages/{id}/meet")
    public Map<String, Object> start(@RequestAttribute long userId, @PathVariable long id, @RequestBody StartRequest req) {
        MessageRepo.Row msg = actions.owned(userId, id);
        if (req == null) throw ApiException.bad("Say who to meet.");
        List<String> emails = new ArrayList<>();
        for (String e : req.emails() == null ? List.<String>of() : req.emails()) {
            String x = e == null ? "" : e.trim().toLowerCase(Locale.ROOT);
            if (x.isEmpty() || x.equalsIgnoreCase(msg.accountEmail()) || emails.contains(x)) continue;
            if (!EMAIL.matcher(x).matches()) throw ApiException.bad("\"" + e + "\" isn't an email address.");
            emails.add(x);
        }
        if (emails.isEmpty()) throw ApiException.bad("Add at least one person to meet.");
        if (emails.size() > 50) throw ApiException.bad("That's a lot of people: invite up to 50 from here.");
        String name = blank(req.name()) ? defaultName(msg) : req.name().trim();
        if (name.length() > 120) name = name.substring(0, 120);
        Instant startAt = blank(req.startAt()) ? null : instant(req.startAt());
        int minutes = req.minutes() == null ? 30 : req.minutes();
        if (minutes < 5 || minutes > 8 * 60) throw ApiException.bad("Length must be between 5 minutes and 8 hours.");
        if (startAt != null && startAt.isBefore(Instant.now().minus(Duration.ofMinutes(5)))) throw ApiException.bad("That time has already passed.");

        // Order matters: room, then time, then people, so each person gets ONE email from Meet,
        // and it already carries the calendar file for the time.
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("name", name);
        create.put("secure", false);                // guests join from any browser
        create.put("ephemeral", startAt == null);   // an instant call tidies itself away
        JsonNode card = meet.call(userId, "POST", "/api/v1/cards", create, "create the room");
        String cardId = card == null ? "" : card.path("id").asText("");
        if (cardId.isBlank()) throw new ApiException(HttpStatus.BAD_GATEWAY, "Magizhchi Meet made the room but sent no id back.");
        if (startAt != null) {
            Map<String, Object> sched = new LinkedHashMap<>();
            sched.put("openAt", startAt.toString());
            sched.put("lockAt", startAt.plus(Duration.ofMinutes(minutes)).toString());
            meet.call(userId, "PUT", "/api/v1/cards/" + cardId + "/schedule", sched, "set the room's time");
        }
        List<String> invited = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String e : emails) {
            MeetClient.Reply r = meet.as(userId, "POST", "/api/v1/cards/" + cardId + "/members", Map.of("email", e, "role", "MEMBER"));
            if (r.ok()) invited.add(e);
            else {
                failed.add(e);
                log.info("Meet invite for {} into {} failed: {}", e, cardId, r.code());
            }
        }
        try {
            jdbc.update("INSERT INTO meet_links (account_id, thread_key, card_id, card_name, starts_at) VALUES (?, ?, ?, ?, ?)",
                    msg.accountId(), msg.threadKey(), cardId, name, startAt == null ? null : OffsetDateTime.ofInstant(startAt, ZoneOffset.UTC));
        } catch (DuplicateKeyException ignored) {
        }
        // A booked time settles any times offered earlier in this thread.
        if (startAt != null) jdbc.update("DELETE FROM meet_offers WHERE account_id = ? AND thread_key = ?", msg.accountId(), msg.threadKey());

        String link = meet.roomLink(cardId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", cardId);
        out.put("name", name);
        out.put("link", link);
        out.put("startAt", startAt == null ? null : startAt.toString());
        out.put("invited", invited);
        out.put("failed", failed);
        out.put("replyBody", startAt == null
                ? "I've started a Magizhchi Meet call for us. Join here:\n" + link + "\n"
                : "Let's meet on " + DATE_TIME.format(startAt.atZone(DEFAULT_ZONE)) + " (" + minutes + " min) on Magizhchi Meet.\nJoin here: " + link
                  + "\n\nYou'll also get a calendar invite from Magizhchi Meet.\n");
        out.put("message", (startAt == null ? "Room is open. " : "Meeting set for " + DATE_TIME.format(startAt.atZone(DEFAULT_ZONE)) + ". ")
                + (invited.size() == 1 ? "1 person invited" : invited.size() + " people invited")
                + (failed.isEmpty() ? "." : "; couldn't invite " + String.join(", ", failed) + "."));
        return out;
    }

    // ── classroom actions ─────────────────────────────────────────

    /** The AI's reading of this mail as a classroom request, checked against the rooms this person runs. */
    @GetMapping("/messages/{id}/meet/suggestion")
    public Map<String, Object> suggestion(@RequestAttribute long userId, @PathVariable long id) {
        MessageRepo.Row msg = actions.owned(userId, id);
        Map<String, Object> none = Map.of("kind", "none");
        if (!meet.configured() || !ai.enabled(userId) || !"needs_me".equals(msg.lane())) return none;
        if (msg.accountEmail().equalsIgnoreCase(msg.fromEmail() == null ? "" : msg.fromEmail())) return none;
        List<Map<String, Object>> rooms;
        try {
            rooms = rooms(userId);
        } catch (ApiException e) {
            return none;
        }
        if (rooms.isEmpty()) return none;
        StringBuilder list = new StringBuilder();
        for (Map<String, Object> r : rooms) {
            list.append("- id ").append(r.get("id")).append(" | ").append(r.get("name"));
            if (r.get("repeat") != null) {
                list.append(" | ").append(r.get("repeat")).append(r.get("days") == null ? "" : " " + r.get("days"))
                        .append(' ').append(nz(r.get("openTime"))).append('-').append(nz(r.get("lockTime")))
                        .append(' ').append(nz(r.get("zone")));
            }
            list.append('\n');
        }
        JsonNode a;
        try {
            a = ai.classroom(msg, list.toString());
        } catch (ApiException e) {
            return none;
        }
        String kind = a.path("kind").asText("none");
        String roomId = a.path("roomId").asText("");
        Map<String, Object> room = rooms.stream().filter(r -> roomId.equalsIgnoreCase(String.valueOf(r.get("id")))).findFirst().orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("why", a.path("why").asText(null));
        out.put("rooms", rooms);
        out.put("room", room);
        out.put("personEmail", msg.fromEmail());
        out.put("personName", blank(msg.fromName()) ? msg.fromEmail() : msg.fromName());
        if ("reschedule".equals(kind)) {
            String t = a.path("newTime").asText("");
            if (!HHMM.matcher(t).matches()) return none;
            out.put("newTime", normal(t));
            // Only rooms that meet on a repeating schedule can be "moved"; let the person pick one.
            if (room != null && room.get("repeat") == null) out.put("room", null);
            return out;
        }
        if ("attend_online".equals(kind)) return out;
        return none;
    }

    @PostMapping("/messages/{id}/meet/reschedule")
    public Map<String, Object> reschedule(@RequestAttribute long userId, @PathVariable long id, @RequestBody RescheduleRequest req) {
        MessageRepo.Row msg = actions.owned(userId, id);
        if (req == null || blank(req.roomId()) || !UUID_RE.matcher(req.roomId().trim()).matches()) throw ApiException.bad("Pick the class room to move.");
        if (blank(req.newTime()) || !HHMM.matcher(req.newTime().trim()).matches()) throw ApiException.bad("Pick the new start time.");
        String cardId = req.roomId().trim();
        JsonNode card = meet.call(userId, "GET", "/api/v1/cards/" + cardId, null, "read the room");
        String repeat = card.path("scheduleRepeat").asText("");
        if (repeat.isBlank() || card.path("openTime").asText("").isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "\"" + card.path("name").asText("This room") + "\" doesn't meet at a regular time, so there's nothing to move.");
        }
        // Meet's schedule save resets whatever it isn't sent, so send the room's own rule, days and zone back.
        String zone = card.path("scheduleZone").asText("");
        if (zone.isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Magizhchi Meet didn't say which time zone this room uses, so MailLook won't change it (that would reset the zone). Update Magizhchi Meet, or change the time in Meet.");
        }
        LocalTime oldOpen = LocalTime.parse(normal(card.path("openTime").asText()));
        LocalTime newOpen = LocalTime.parse(normal(req.newTime().trim()));
        String lockRaw = card.path("lockTime").asText("");
        Duration length = lockRaw.isBlank() ? Duration.ofHours(1) : Duration.between(oldOpen, LocalTime.parse(normal(lockRaw)));
        if (length.isNegative() || length.isZero()) length = length.plusHours(24);
        LocalTime newLock = newOpen.plus(length);

        Map<String, Object> sched = new LinkedHashMap<>();
        sched.put("repeat", repeat);
        sched.put("days", card.path("repeatDays").isNull() ? null : card.path("repeatDays").asText(null));
        sched.put("openTime", newOpen.toString());
        sched.put("lockTime", newLock.toString());
        sched.put("zone", zone);
        meet.call(userId, "PUT", "/api/v1/cards/" + cardId + "/schedule", sched, "change the room's time");

        String name = card.path("name").asText("the class");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roomId", cardId);
        out.put("from", TIME.format(oldOpen));
        out.put("to", TIME.format(newOpen));
        out.put("message", "\"" + name + "\" now starts at " + TIME.format(newOpen) + ". Magizhchi Meet is telling everyone in the room.");
        out.put("replyBody", "Done: " + name + " now starts at " + TIME.format(newOpen) + " (until " + TIME.format(newLock) + ") from the next class."
                + "\nEveryone in the batch gets the new time from Magizhchi Meet.\n");
        return out;
    }

    @PostMapping("/messages/{id}/meet/add")
    public Map<String, Object> addPerson(@RequestAttribute long userId, @PathVariable long id, @RequestBody AddRequest req) {
        MessageRepo.Row msg = actions.owned(userId, id);
        if (req == null || blank(req.roomId()) || !UUID_RE.matcher(req.roomId().trim()).matches()) throw ApiException.bad("Pick the class room.");
        String email = blank(req.email()) ? msg.fromEmail() : req.email().trim().toLowerCase(Locale.ROOT);
        if (email == null || !EMAIL.matcher(email).matches()) throw ApiException.bad("That isn't an email address.");
        String cardId = req.roomId().trim();
        JsonNode card = meet.call(userId, "GET", "/api/v1/cards/" + cardId, null, "read the room");
        JsonNode added = meet.call(userId, "POST", "/api/v1/cards/" + cardId + "/members", Map.of("email", email, "role", "MEMBER"), "add them to the room");
        String name = card.path("name").asText("the class");
        String link = meet.roomLink(cardId);
        String who = blank(msg.fromName()) || !email.equalsIgnoreCase(msg.fromEmail()) ? email : firstName(msg.fromName());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roomId", cardId);
        out.put("status", added == null ? "ADDED" : added.path("status").asText("ADDED"));
        out.put("link", link);
        out.put("message", who + " can join \"" + name + "\" online. Magizhchi Meet has emailed them the link.");
        out.put("replyBody", "Sure, join today's class online here:\n" + link + "\n\nTake care.\n");
        return out;
    }

    // ── helpers ───────────────────────────────────────────────────

    private Map<String, Object> room(JsonNode c) {
        Map<String, Object> m = new LinkedHashMap<>();
        String id = c.path("id").asText();
        m.put("id", id);
        m.put("name", c.path("name").asText(""));
        m.put("type", c.path("type").asText(null));
        m.put("link", meet.roomLink(id));
        m.put("known", true);
        m.put("locked", c.path("locked").asBoolean(false));
        m.put("live", c.path("liveCount").asInt(0));
        m.put("repeat", text(c, "scheduleRepeat"));
        m.put("days", text(c, "repeatDays"));
        m.put("openTime", text(c, "openTime"));
        m.put("lockTime", text(c, "lockTime"));
        m.put("zone", text(c, "scheduleZone"));
        m.put("startsAt", text(c, "scheduledOpenAt"));
        m.put("endsAt", text(c, "scheduledLockAt"));
        return m;
    }

    private String nameFromLink(MessageRepo.Row msg, String cardId) {
        List<String> names = jdbc.queryForList("SELECT card_name FROM meet_links WHERE account_id = ? AND card_id = ?", String.class, msg.accountId(), cardId);
        if (!names.isEmpty() && names.get(0) != null) return names.get(0);
        String s = msg.subject() == null ? "" : msg.subject().replaceAll("(?i)^(invitation to|you're invited to|added to)\\s*", "");
        return s.isBlank() ? "Magizhchi Meet room" : s.replaceAll("\\s*·\\s*Magizhchi Meet\\s*$", "").replaceAll("^[“\"]|[”\"]$", "");
    }

    private static String text(JsonNode c, String f) {
        JsonNode v = c.get(f);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    private static String defaultName(MessageRepo.Row m) {
        String s = m.subject() == null ? "" : m.subject().replaceAll("(?i)^((re|fwd?)\\s*:\\s*)+", "").trim();
        if (!s.isEmpty()) return s;
        return "Call with " + (blank(m.fromName()) ? m.fromEmail() : m.fromName());
    }

    private static String firstName(String full) {
        String f = full.trim().split("\\s+")[0];
        return f.isEmpty() ? full : f;
    }

    /** "9:5" → "09:05". */
    private static String normal(String hhmm) {
        String[] p = hhmm.trim().split(":");
        return String.format("%02d:%02d", Integer.parseInt(p[0]), Integer.parseInt(p[1].substring(0, 2)));
    }

    private static Instant instant(String s) {
        try {
            return Instant.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.bad("That start time isn't a valid date and time.");
        }
    }

    private static String nz(Object o) {
        return o == null ? "" : o.toString();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
