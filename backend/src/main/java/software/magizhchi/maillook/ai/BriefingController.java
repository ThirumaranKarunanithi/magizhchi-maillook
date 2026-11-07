package software.magizhchi.maillook.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.magizhchi.maillook.common.ApiException;
import software.magizhchi.maillook.mail.LaneClassifier;
import software.magizhchi.maillook.mail.MailSearch;
import software.magizhchi.maillook.mail.MessageParser;
import software.magizhchi.maillook.mail.MessageRepo;
import software.magizhchi.maillook.mail.Waiting;
import software.magizhchi.maillook.suite.PaClient;
import software.magizhchi.maillook.suite.PaController;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The daily briefing: who is waiting on you and what each one needs, who you're waiting on,
 * today's meetings and tasks from Magizhchi PA, and a short AI overview of the day.
 */
@org.springframework.web.bind.annotation.RequestMapping("/api")
@RestController
public class BriefingController {

    private static final Logger log = LoggerFactory.getLogger(BriefingController.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final long CACHE_MS = 2 * 3600_000L;

    private record Cached(JsonNode ai, long until) {}

    private final MessageRepo messages;
    private final Waiting waiting;
    private final AiService ai;
    private final PaClient pa;
    private final PaController paController;
    private final JdbcTemplate jdbc;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public BriefingController(MessageRepo messages, Waiting waiting, AiService ai, PaClient pa, PaController paController, JdbcTemplate jdbc) {
        this.messages = messages;
        this.waiting = waiting;
        this.ai = ai;
        this.pa = pa;
        this.paController = paController;
        this.jdbc = jdbc;
    }

    @GetMapping("/briefing")
    public Map<String, Object> briefing(@RequestAttribute long userId,
                                        @RequestParam(required = false) Long accountId,
                                        @RequestParam(required = false) String tz,
                                        @RequestParam(defaultValue = "false") boolean refresh) {
        ZoneId zone = AiController.zone(tz);
        Instant now = Instant.now();
        ZonedDateTime local = now.atZone(zone);
        LocalDate today = local.toLocalDate();
        Instant dayStart = today.atStartOfDay(zone).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant();

        // Who is waiting on you: unread Needs me from the last two weeks, plus mail back from snooze.
        Set<Long> seen = new HashSet<>();
        List<MessageRepo.Row> needs = new ArrayList<>();
        for (MessageRepo.Row b : messages.back(userId, accountId, LaneClassifier.NEEDS_ME,
                OffsetDateTime.ofInstant(now.minus(Duration.ofDays(14)), ZoneOffset.UTC))) {
            if (seen.add(b.id())) needs.add(b);
        }
        MailSearch unread = new MailSearch(null, null, false, true, false, now.minus(Duration.ofDays(14)), null);
        for (MessageRepo.Row r : messages.list(userId, "INBOX", LaneClassifier.NEEDS_ME, accountId, unread, null, null, 12)) {
            if (seen.add(r.id())) needs.add(r);
        }
        long needsTotal = messages.unseenInLane(userId, accountId, LaneClassifier.NEEDS_ME, now.minus(Duration.ofDays(30)), now.plus(Duration.ofDays(1)));
        if (needs.size() > 8) needs = needs.subList(0, 8);

        List<Waiting.Item> waits = waiting.list(userId, accountId, now, false);

        // Today's meetings and tasks from PA.
        List<Map<String, Object>> events = new ArrayList<>();
        List<Map<String, Object>> tasks = new ArrayList<>();
        boolean paRead = false;
        if (pa.configured()) {
            try {
                Map<String, Object> agenda = paController.agenda(userId, dayStart.toString(), dayEnd.toString());
                events = castList(agenda.get("events"));
                tasks = castList(agenda.get("tasks"));
                paRead = true;
            } catch (ApiException e) {
                log.info("PA agenda not read for the briefing: {}", e.getMessage());
            }
        }
        Integer snoozedToday = jdbc.queryForObject("SELECT COUNT(*) FROM messages m JOIN accounts a ON a.id = m.account_id " +
                        "WHERE a.user_id = ? AND m.snoozed_until >= ? AND m.snoozed_until < ?" + (accountId == null ? "" : " AND m.account_id = " + accountId),
                Integer.class, userId, OffsetDateTime.ofInstant(now, ZoneOffset.UTC), OffsetDateTime.ofInstant(dayEnd, ZoneOffset.UTC));

        String name = jdbc.queryForList("SELECT name FROM users WHERE id = ?", String.class, userId).stream()
                .filter(s -> s != null && !s.isBlank()).findFirst().orElse(null);
        String first = name == null ? null : name.trim().split("\\s+")[0];

        // The AI overview, worked out once per change in what's on the list (and at most every two hours).
        JsonNode overview = null;
        if (ai.enabled(userId) && (!needs.isEmpty() || !events.isEmpty() || !waits.isEmpty() || !tasks.isEmpty())) {
            StringBuilder sig = new StringBuilder(userId + ":" + accountId + ":" + today);
            needs.forEach(r -> sig.append(',').append(r.id()));
            sig.append('|');
            events.forEach(e -> sig.append(',').append(e.get("id")));
            sig.append('|').append(waits.size()).append('|').append(tasks.size());
            String key = sig.toString();
            Cached c = cache.get(key);
            if (!refresh && c != null && c.until() > System.currentTimeMillis()) {
                overview = c.ai();
            } else {
                try {
                    overview = ai.dailyBriefing(userId, first, material(needs, waits, events, tasks, zone, local));
                    cache.entrySet().removeIf(e -> e.getKey().startsWith(userId + ":") || e.getValue().until() < System.currentTimeMillis());
                    cache.put(key, new Cached(overview, System.currentTimeMillis() + CACHE_MS));
                } catch (ApiException e) {
                    log.info("Briefing overview skipped: {}", e.getMessage());
                }
            }
        }
        Map<Long, String> lines = new LinkedHashMap<>();
        if (overview != null) {
            for (JsonNode it : overview.path("items")) {
                long id = it.path("id").asLong(-1);
                if (it.path("line").isTextual()) lines.put(id, it.path("line").asText());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        int h = local.getHour();
        out.put("greeting", (h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening") + (first == null ? "" : ", " + first));
        out.put("date", today.toString());
        String summary = overview == null ? null : overview.path("summary").asText(null);
        out.put("summary", summary != null && !summary.isBlank() ? summary : plain(needsTotal, waits.size(), events, tasks, zone));
        out.put("byAi", summary != null && !summary.isBlank());
        out.put("needsMeTotal", needsTotal);
        out.put("needsMe", needs.stream().map(r -> {
            Map<String, Object> m = r.summary();
            m.put("line", lines.get(r.id()));
            return m;
        }).toList());
        out.put("waitingTotal", waits.size());
        out.put("waiting", waits.stream().limit(5).map(Waiting.Item::summary).toList());
        out.put("events", events);
        out.put("tasks", tasks);
        out.put("snoozedToday", snoozedToday == null ? 0 : snoozedToday);
        out.put("paConnected", paRead);
        out.put("webUrl", pa.webUrl().isBlank() ? null : pa.webUrl());
        return out;
    }

    private static String material(List<MessageRepo.Row> needs, List<Waiting.Item> waits, List<Map<String, Object>> events,
                                   List<Map<String, Object>> tasks, ZoneId zone, ZonedDateTime local) {
        StringBuilder sb = new StringBuilder("Now: ").append(local.toLocalDate()).append(' ').append(local.getDayOfWeek())
                .append(' ').append(TIME.format(local)).append("\n\nNeeds you:\n");
        if (needs.isEmpty()) sb.append("(nobody)\n");
        for (MessageRepo.Row r : needs) {
            long days = Duration.between(r.receivedAt().toInstant(), Instant.now()).toDays();
            sb.append("- id ").append(r.id()).append(" | from ").append(r.fromName() == null ? r.fromEmail() : r.fromName())
                    .append(" | ").append(days == 0 ? "today" : days + " days ago").append(" | subject: ").append(r.subject())
                    .append(" | ").append(cut(MessageParser.ownText(r.bodyText()), 400).replace('\n', ' ')).append('\n');
        }
        sb.append("\nYou're waiting for answers to:\n");
        if (waits.isEmpty()) sb.append("(nothing)\n");
        for (Waiting.Item w : waits.stream().limit(5).toList()) {
            sb.append("- ").append(Waiting.who(w.row())).append(", ").append(w.days()).append(" days: ").append(w.row().subject()).append('\n');
        }
        sb.append("\nToday's calendar:\n");
        if (events.isEmpty()) sb.append("(no meetings)\n");
        for (Map<String, Object> e : events) {
            sb.append("- ").append(Boolean.TRUE.equals(e.get("allDay")) ? "all day" : time(e.get("start"), zone))
                    .append(": ").append(e.get("title")).append(e.get("location") == null ? "" : " @ " + e.get("location")).append('\n');
        }
        sb.append("\nTasks due today:\n");
        if (tasks.isEmpty()) sb.append("(none)\n");
        for (Map<String, Object> t : tasks) sb.append("- ").append(t.get("title")).append('\n');
        return sb.toString();
    }

    /** Without AI: the same facts in one plain sentence. */
    static String plain(long needs, int waits, List<Map<String, Object>> events, List<Map<String, Object>> tasks, ZoneId zone) {
        List<String> parts = new ArrayList<>();
        parts.add(needs == 0 ? "Nobody is waiting on you" : needs == 1 ? "1 person is waiting on you" : needs + " people are waiting on you");
        if (!events.isEmpty()) {
            Object firstStart = events.stream().filter(e -> !Boolean.TRUE.equals(e.get("allDay"))).map(e -> e.get("start")).findFirst().orElse(null);
            parts.add(events.size() + (events.size() == 1 ? " meeting today" : " meetings today")
                    + (firstStart == null ? "" : " (first at " + time(firstStart, zone) + ")"));
        }
        if (!tasks.isEmpty()) parts.add(tasks.size() + (tasks.size() == 1 ? " task is due" : " tasks are due"));
        if (waits > 0) parts.add("you're waiting on " + waits + (waits == 1 ? " reply" : " replies"));
        String s = parts.size() == 1 ? parts.get(0) : String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
        return s + ".";
    }

    private static String time(Object iso, ZoneId zone) {
        try {
            return TIME.format(Instant.parse(String.valueOf(iso)).atZone(zone));
        } catch (Exception e) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object o) {
        return o instanceof List<?> l ? new ArrayList<>((List<Map<String, Object>>) l) : new ArrayList<>();
    }

    private static String cut(String s, int n) {
        return s == null ? "" : s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
