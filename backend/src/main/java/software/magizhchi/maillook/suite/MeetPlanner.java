package software.magizhchi.maillook.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Before the meeting: spot "can we talk?" and offer free times from the person's PA calendar,
 * book the time the other side picks, brief the person just before it starts, and post the
 * thread's open questions into the room as the agenda. Every step waits for a tap.
 */
@RestController
@RequestMapping("/api")
public class MeetPlanner {

    private static final Logger log = LoggerFactory.getLogger(MeetPlanner.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    static final LocalTime DAY_START = LocalTime.of(10, 0);
    static final LocalTime DAY_END = LocalTime.of(18, 0);
    private static final DateTimeFormatter SLOT = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH);
    private static final Pattern UUID_RE = Pattern.compile("^[0-9a-fA-F-]{36}$");

    public record OfferRequest(List<String> slots, Integer minutes, String topic) {}
    public record AgendaRequest(String roomId, List<String> items) {}

    private final MeetClient meet;
    private final PaClient pa;
    private final AiService ai;
    private final MailActions actions;
    private final MessageRepo messages;
    private final MeetRecaps recaps;
    private final JdbcTemplate jdbc;

    public MeetPlanner(MeetClient meet, PaClient pa, AiService ai, MailActions actions, MessageRepo messages,
                       MeetRecaps recaps, JdbcTemplate jdbc) {
        this.meet = meet;
        this.pa = pa;
        this.ai = ai;
        this.actions = actions;
        this.messages = messages;
        this.recaps = recaps;
        this.jdbc = jdbc;
    }

    // ── find a time ───────────────────────────────────────────────

    /**
     * "Can we talk?" → three free times. After You offered times, "Tuesday works" → that slot,
     * ready to book. {@code kind} is request, picked or none.
     */
    @GetMapping("/messages/{id}/meet/times")
    public Map<String, Object> times(@RequestAttribute long userId, @PathVariable long id) {
        MessageRepo.Row msg = actions.owned(userId, id);
        Map<String, Object> none = Map.of("kind", "none");
        if (!meet.configured() || !ai.enabled(userId) || !"needs_me".equals(msg.lane())) return none;
        if (msg.fromEmail() == null || msg.accountEmail().equalsIgnoreCase(msg.fromEmail())) return none;
        Integer booked = jdbc.queryForObject("SELECT COUNT(*) FROM meet_links WHERE account_id = ? AND thread_key = ? AND starts_at > ?",
                Integer.class, msg.accountId(), msg.threadKey(), OffsetDateTime.now(ZoneOffset.UTC));
        if (booked != null && booked > 0) return none;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("personEmail", msg.fromEmail());
        out.put("personName", blank(msg.fromName()) ? msg.fromEmail() : msg.fromName());
        try {
            Offer offer = offer(msg);
            if (offer != null && msg.id() > offer.offeredOn()) {
                StringBuilder list = new StringBuilder();
                for (int i = 0; i < offer.slots().size(); i++) {
                    list.append(i + 1).append(". ").append(label(offer.slots().get(i))).append('\n');
                }
                JsonNode a = ai.meetingRequest(msg, list.toString());
                int pick = a.path("pick").asInt(0);
                if ("picked".equals(a.path("kind").asText()) && pick >= 1 && pick <= offer.slots().size()) {
                    Instant start = offer.slots().get(pick - 1);
                    if (start.isAfter(Instant.now())) {
                        out.put("kind", "picked");
                        out.put("why", a.path("why").asText(null));
                        out.put("minutes", offer.minutes());
                        out.put("topic", offer.topic());
                        out.put("slot", slot(start, offer.minutes()));
                        return out;
                    }
                }
                return none;
            }
            JsonNode a = ai.meetingRequest(msg, null);
            if (!"request".equals(a.path("kind").asText())) return none;
            int minutes = Math.max(15, Math.min(120, a.path("minutes").asInt(30)));
            minutes = (int) Math.round(minutes / 15.0) * 15;
            List<Instant[]> busy = new ArrayList<>();
            boolean checked = busy(userId, busy);
            List<Map<String, Object>> slots = new ArrayList<>();
            for (Instant s : freeSlots(Instant.now(), busy, minutes, 3)) slots.add(slot(s, minutes));
            if (slots.isEmpty()) return none;
            out.put("kind", "request");
            out.put("why", a.path("why").asText(null));
            out.put("minutes", minutes);
            out.put("topic", a.path("topic").isTextual() ? a.path("topic").asText() : null);
            out.put("calendarChecked", checked);
            out.put("slots", slots);
            return out;
        } catch (ApiException e) {
            return none;
        }
    }

    /** Remember the times offered in this thread and draft the reply that proposes them. */
    @PostMapping("/messages/{id}/meet/offer")
    public Map<String, Object> offer(@RequestAttribute long userId, @PathVariable long id, @RequestBody OfferRequest req) {
        MessageRepo.Row msg = actions.owned(userId, id);
        if (req == null || req.slots() == null || req.slots().isEmpty() || req.slots().size() > 5)
            throw ApiException.bad("Pick one to five times to offer.");
        int minutes = req.minutes() == null ? 30 : req.minutes();
        if (minutes < 15 || minutes > 240) throw ApiException.bad("Length must be between 15 minutes and 4 hours.");
        List<Instant> slots = new ArrayList<>();
        for (String s : req.slots()) {
            Instant i = instant(s);
            if (i.isBefore(Instant.now())) throw ApiException.bad("One of those times has already passed.");
            slots.add(i);
        }
        slots.sort(null);
        String topic = blank(req.topic()) ? null : clip(req.topic().trim(), 300);
        List<String> iso = slots.stream().map(Instant::toString).toList();
        String json;
        try {
            json = JSON.writeValueAsString(iso);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        jdbc.update("DELETE FROM meet_offers WHERE account_id = ? AND thread_key = ?", msg.accountId(), msg.threadKey());
        jdbc.update("INSERT INTO meet_offers (account_id, thread_key, slots, minutes, topic, offered_on) VALUES (?, ?, ?, ?, ?, ?)",
                msg.accountId(), msg.threadKey(), json, minutes, topic, msg.id());

        StringBuilder body = new StringBuilder("Happy to talk").append(minutes < 60 ? " (" + minutes + " min)" : "")
                .append(". Do any of these work for you?\n\n");
        for (Instant s : slots) body.append("- ").append(label(s)).append('\n');
        body.append("\nTimes are India time (IST). Pick one and I'll send a Magizhchi Meet link.\n");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("replyBody", body.toString());
        out.put("message", "Times saved. When they pick one, MailLook will offer to book it.");
        return out;
    }

    /** Busy intervals from the person's PA calendar for the next week. False when PA couldn't be read. */
    private boolean busy(long userId, List<Instant[]> into) {
        if (!pa.configured()) return false;
        Instant from = Instant.now();
        Instant to = from.plus(Duration.ofDays(8));
        try {
            PaClient.Reply r = pa.as(userId, "GET", "/api/v1/events?from=" + enc(from.toString()) + "&to=" + enc(to.toString()), null);
            if (!r.ok() || r.json() == null || !r.json().isArray()) return false;
            for (JsonNode e : r.json()) {
                if ("CANCELLED".equalsIgnoreCase(e.path("status").asText(""))) continue;
                try {
                    Instant s = Instant.parse(e.path("occurrenceStart").asText());
                    Instant f = Instant.parse(e.path("occurrenceEnd").asText());
                    if (e.path("allDay").asBoolean(false)) {
                        // An all-day entry (a holiday, a trip) blocks the whole working day.
                        LocalDate d = s.atZone(ZONE).toLocalDate();
                        s = d.atTime(DAY_START).atZone(ZONE).toInstant();
                        f = d.atTime(DAY_END).atZone(ZONE).toInstant();
                    }
                    into.add(new Instant[]{s, f});
                } catch (Exception ignored) {
                }
            }
            return true;
        } catch (ApiException e) {
            log.info("PA calendar not read for free times: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Up to {@code count} free starts of {@code minutes} between 10 AM and 6 PM India time,
     * Monday to Saturday, at least two hours from now, on the half hour. One per day first, so the
     * other side gets a real choice, then the earliest days' second options.
     */
    static List<Instant> freeSlots(Instant now, List<Instant[]> busy, int minutes, int count) {
        Instant earliest = now.plus(Duration.ofHours(2));
        List<List<Instant>> byDay = new ArrayList<>();
        LocalDate day = now.atZone(ZONE).toLocalDate();
        for (int d = 0; d < 10 && byDay.size() < count + 2; d++, day = day.plusDays(1)) {
            if (day.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            List<Instant> free = new ArrayList<>();
            for (LocalTime t = DAY_START; !t.plusMinutes(minutes).isAfter(DAY_END) && t.isAfter(LocalTime.of(9, 59)); t = t.plusMinutes(30)) {
                Instant s = ZonedDateTime.of(day, t, ZONE).toInstant();
                Instant e = s.plus(Duration.ofMinutes(minutes));
                if (s.isBefore(earliest)) continue;
                boolean clash = busy.stream().anyMatch(b -> s.isBefore(b[1]) && e.isAfter(b[0]));
                if (!clash) free.add(s);
                if (t.equals(LocalTime.of(23, 30))) break;
            }
            if (!free.isEmpty()) byDay.add(free);
        }
        List<Instant> out = new ArrayList<>();
        for (int round = 0; out.size() < count && round < 3; round++) {
            for (List<Instant> free : byDay) {
                if (out.size() >= count) break;
                // Second round: a later option on the same day (afternoon if the first was morning).
                int idx = round == 0 ? 0 : Math.min(free.size() - 1, round * Math.max(1, free.size() / 2));
                Instant pick = free.get(idx);
                if (!out.contains(pick)) out.add(pick);
            }
        }
        out.sort(null);
        return out;
    }

    // ── briefing and agenda ───────────────────────────────────────

    /** What to know before the meeting: the thread in two sentences, what's still open, its files, last time's notes. */
    @GetMapping("/messages/{id}/meet/briefing")
    public Map<String, Object> briefing(@RequestAttribute long userId, @PathVariable long id) {
        MessageRepo.Row msg = actions.owned(userId, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", null);
        out.put("questions", List.of());
        if (ai.enabled(userId)) {
            try {
                JsonNode b = ai.briefing(msg);
                out.put("summary", b.path("summary").isTextual() ? b.path("summary").asText() : null);
                List<String> q = new ArrayList<>();
                b.path("questions").forEach(n -> { if (!n.asText().isBlank() && q.size() < 6) q.add(n.asText().trim()); });
                out.put("questions", q);
            } catch (ApiException e) {
                out.put("aiError", e.getMessage());
            }
        }
        List<MessageRepo.Row> thread = messages.thread(msg.accountId(), msg.threadKey());
        Set<String> inBox = new HashSet<>();
        jdbc.queryForList("SELECT b.message_id, b.att_index FROM box_saves b JOIN messages m ON m.id = b.message_id " +
                "WHERE m.account_id = ? AND m.thread_key = ?", msg.accountId(), msg.threadKey())
                .forEach(r -> inBox.add(r.get("message_id") + ":" + r.get("att_index")));
        List<Map<String, Object>> files = new ArrayList<>();
        for (MessageRepo.Row r : thread.isEmpty() ? List.of(msg) : thread) {
            if (r.attachments() == null) continue;
            for (Map<String, Object> a : r.attachments()) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("messageId", r.id());
                f.put("index", a.get("index"));
                f.put("name", a.get("name"));
                f.put("size", a.get("size"));
                f.put("inBox", a.get("index") instanceof Number n && inBox.contains(r.id() + ":" + n.intValue()));
                files.add(f);
            }
        }
        out.put("attachments", files);
        List<Map<String, Object>> held = recaps.forThread(msg.accountId(), msg.threadKey(), msg.accountEmail());
        out.put("lastMeeting", held.isEmpty() ? null : held.get(0));
        return out;
    }

    /** Post the chosen open questions into the room's chat as the agenda, before anyone joins. */
    @PostMapping("/messages/{id}/meet/agenda")
    public Map<String, Object> agenda(@RequestAttribute long userId, @PathVariable long id, @RequestBody AgendaRequest req) {
        MessageRepo.Row msg = actions.owned(userId, id);
        if (req == null || req.roomId() == null || !UUID_RE.matcher(req.roomId()).matches()) throw ApiException.bad("Pick the room.");
        List<String> items = req.items() == null ? List.of() : req.items().stream()
                .filter(s -> s != null && !s.isBlank()).map(s -> clip(s.trim(), 300)).limit(10).toList();
        if (items.isEmpty()) throw ApiException.bad("Pick at least one point for the agenda.");
        String subject = blank(msg.subject()) ? "" : " (from \"" + clip(msg.subject().replaceAll("(?i)^((re|fwd?)\\s*:\\s*)+", "").trim(), 120) + "\")";
        StringBuilder text = new StringBuilder("Agenda").append(subject).append('\n');
        for (int i = 0; i < items.size(); i++) text.append(i + 1).append(". ").append(items.get(i)).append('\n');
        meet.call(userId, "POST", "/api/v1/cards/" + req.roomId().toLowerCase(Locale.ROOT) + "/chat",
                Map.of("text", text.toString().trim()), "post the agenda");
        return Map.of("message", "Agenda posted in the room's chat.", "text", text.toString().trim());
    }

    // ── helpers ───────────────────────────────────────────────────

    record Offer(List<Instant> slots, int minutes, String topic, long offeredOn) {}

    private Offer offer(MessageRepo.Row msg) {
        List<Offer> found = jdbc.query("SELECT slots, minutes, topic, offered_on FROM meet_offers WHERE account_id = ? AND thread_key = ?",
                (rs, i) -> {
                    List<Instant> slots = new ArrayList<>();
                    try {
                        JSON.readTree(rs.getString("slots")).forEach(n -> slots.add(Instant.parse(n.asText())));
                    } catch (Exception ignored) {
                    }
                    return new Offer(slots, rs.getInt("minutes"), rs.getString("topic"), rs.getLong("offered_on"));
                }, msg.accountId(), msg.threadKey());
        return found.isEmpty() || found.get(0).slots().isEmpty() ? null : found.get(0);
    }

    static String label(Instant s) {
        return SLOT.format(s.atZone(ZONE));
    }

    private static Map<String, Object> slot(Instant start, int minutes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("start", start.toString());
        m.put("end", start.plus(Duration.ofMinutes(minutes)).toString());
        m.put("label", label(start));
        return m;
    }

    private static Instant instant(String s) {
        try {
            return Instant.parse(s.trim());
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.bad("That time isn't a valid date and time.");
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
