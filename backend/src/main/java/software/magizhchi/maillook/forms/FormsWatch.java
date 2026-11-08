package software.magizhchi.maillook.forms;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.magizhchi.maillook.notify.PushService;

import java.sql.Timestamp;
import java.time.*;
import java.time.format.TextStyle;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps an eye on Magizhchi Forms for each person who has it: a phone alert for each approval that
 * starts waiting on them, the day's new responses as one evening alert (when they chose "daily"), the
 * counts for the sidebar badge, and the Forms line of the morning briefing.
 */
@Service
public class FormsWatch {
    private static final Logger log = LoggerFactory.getLogger(FormsWatch.class);
    static final Duration EVERY = Duration.ofMinutes(5);
    static final int EVENING = 18;

    /** What the last look found: approvals waiting, responses in the last day, forms closing within a week. */
    public record Counts(int approvals, int newResponses, List<String> closing, Instant at) {}

    private final FormsClient forms;
    private final PushService push;
    private final JdbcTemplate jdbc;
    private final Map<Long, Counts> last = new ConcurrentHashMap<>();

    public FormsWatch(FormsClient forms, PushService push, JdbcTemplate jdbc) {
        this.forms = forms;
        this.push = push;
        this.jdbc = jdbc;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 120_000)
    public void onTimer() {
        if (!forms.configured()) return;
        try {
            run(Instant.now());
        } catch (Exception e) {
            log.info("Forms pass failed: {}", e.toString());
        }
    }

    /** One pass over the people due a look. @return how many alerts went out. */
    public int run(Instant now) {
        if (!forms.configured()) return 0;
        // Those with Forms (or not yet asked) every few minutes; those without, once a week in case they start.
        List<Map<String, Object>> due = jdbc.queryForList("SELECT id, email, tz, forms_responses, forms_checked_at FROM users WHERE "
                        + "(forms_linked IS NOT FALSE AND (forms_checked_at IS NULL OR forms_checked_at < ?)) "
                        + "OR (forms_linked = FALSE AND forms_checked_at < ?) ORDER BY forms_checked_at NULLS FIRST LIMIT 40",
                Timestamp.from(now.minus(EVERY)), Timestamp.from(now.minus(Duration.ofDays(7))));
        int alerts = 0;
        for (Map<String, Object> u : due) {
            long userId = ((Number) u.get("id")).longValue();
            try {
                alerts += look(userId, String.valueOf(u.get("email")), u.get("tz") == null ? null : u.get("tz").toString(),
                        String.valueOf(u.get("forms_responses")), now);
            } catch (Exception e) {
                log.info("Forms look for user {} failed: {}", userId, e.toString());
            }
        }
        return alerts;
    }

    int look(long userId, String email, String tz, String responsesMode, Instant now) {
        return look(userId, email, tz, responsesMode, now, true);
    }

    /** {@code alert}: false for a look made only for the badge, which leaves alerts to the timer. */
    int look(long userId, String email, String tz, String responsesMode, Instant now, boolean alert) {
        FormsClient.Reply r = forms.get("/overview", Map.of("email", email, "since", now.minus(Duration.ofDays(1)).toString()));
        if (r.code() == 404) {
            jdbc.update("UPDATE users SET forms_linked = FALSE, forms_checked_at = ? WHERE id = ?", Timestamp.from(now), userId);
            last.remove(userId);
            return 0;
        }
        if (!r.ok()) return 0; // Forms down: try again next pass, without moving the clock
        JsonNode j = r.json();
        if (alert) jdbc.update("UPDATE users SET forms_linked = TRUE, forms_checked_at = ? WHERE id = ?", Timestamp.from(now), userId);
        else jdbc.update("UPDATE users SET forms_linked = TRUE WHERE id = ?", userId);
        ZoneId zone = zone(tz);
        Counts c = counts(j, now, zone);
        last.put(userId, c);
        if (!alert) return 0;

        int alerts = 0;
        boolean first = jdbc.queryForObject("SELECT COUNT(*) FROM forms_alerted WHERE user_id = ?", Long.class, userId) == 0;
        for (JsonNode a : j.path("approvals")) {
            String item = "apr:" + a.path("responseId").asText();
            if (!mark(userId, item)) continue;
            // The very first look only notes what's already waiting; alerts are for what's new from now on.
            if (first) continue;
            String who = a.path("who").asText(a.path("whoEmail").asText(""));
            push.reminder(userId, "Approval waiting: " + a.path("formTitle").asText("a form"),
                    (who.isBlank() ? "A new response" : who) + gist(a) + ". Approve or reject it in MailLook.",
                    "/forms?approval=" + a.path("responseId").asText(), "maillook-forms-approval-" + a.path("responseId").asText());
            alerts++;
        }
        if ("daily".equals(responsesMode)) {
            ZonedDateTime local = now.atZone(zone);
            if (local.getHour() >= EVENING && local.getHour() < EVENING + 4 && c.newResponses() > 0
                    && mark(userId, "day:" + local.toLocalDate())) {
                push.reminder(userId, "Forms today: " + c.newResponses() + (c.newResponses() == 1 ? " new response" : " new responses"),
                        byForm(j) + ". Tap to see the answers.", "/forms", "maillook-forms-day");
                alerts++;
            }
        }
        if (first) mark(userId, "first");
        jdbc.update("DELETE FROM forms_alerted WHERE user_id = ? AND at < ?", userId, Timestamp.from(now.minus(Duration.ofDays(60))));
        return alerts;
    }

    private boolean mark(long userId, String item) {
        try {
            return jdbc.update("INSERT INTO forms_alerted (user_id, item) VALUES (?, ?)", userId, item) > 0;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    static Counts counts(JsonNode j, Instant now, ZoneId zone) {
        int fresh = 0;
        for (JsonNode f : j.path("forms")) fresh += f.path("newResponses").asInt(0);
        List<String> closing = new ArrayList<>();
        for (JsonNode f : j.path("forms")) {
            String at = f.path("closesAt").asText("");
            if (at.isBlank() || !"published".equals(f.path("status").asText())) continue;
            try {
                Instant c = Instant.parse(at);
                if (c.isAfter(now) && c.isBefore(now.plus(Duration.ofDays(7))))
                    closing.add(f.path("title").asText("A form") + " closes " + day(c, now, zone));
            } catch (Exception ignored) {
                // a schedule we can't read says nothing
            }
        }
        return new Counts(j.path("approvals").size(), fresh, closing, now);
    }

    static String day(Instant at, Instant now, ZoneId zone) {
        LocalDate d = at.atZone(zone).toLocalDate();
        LocalDate today = now.atZone(zone).toLocalDate();
        if (d.equals(today)) return "today";
        if (d.equals(today.plusDays(1))) return "tomorrow";
        return d.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    private static String gist(JsonNode a) {
        JsonNode lines = a.path("answers");
        StringBuilder s = new StringBuilder();
        for (int i = 0, n = 0; i < lines.size() && n < 2; i++) {
            String t = lines.get(i).path("text").asText("");
            String label = lines.get(i).path("label").asText("");
            if (t.isBlank() || t.contains("@") || label.toLowerCase(Locale.ROOT).contains("name")) continue;
            s.append(n == 0 ? " · " : ", ").append(t.length() > 40 ? t.substring(0, 40) + "…" : t);
            n++;
        }
        return s.toString();
    }

    private static String byForm(JsonNode j) {
        List<String> parts = new ArrayList<>();
        for (JsonNode f : j.path("forms")) {
            int n = f.path("newResponses").asInt(0);
            if (n > 0) parts.add(f.path("title").asText("Form") + " " + n);
            if (parts.size() == 3) break;
        }
        return String.join(" · ", parts);
    }

    private static ZoneId zone(String tz) {
        try {
            return tz == null ? ZoneId.of("Asia/Kolkata") : ZoneId.of(tz);
        } catch (Exception e) {
            return ZoneId.of("Asia/Kolkata");
        }
    }

    /** For the sidebar badge: what the last look found (none yet: nothing). */
    public Optional<Counts> counts(long userId) {
        return Optional.ofNullable(last.get(userId));
    }

    /** "Forms: 12 new responses · 3 approvals waiting · Admission form closes Friday", or null when there's nothing to say. */
    public String briefingLine(long userId, String email, String tz, Instant now) {
        if (!forms.configured()) return null;
        Boolean linked = jdbc.queryForObject("SELECT forms_linked FROM users WHERE id = ?", Boolean.class, userId);
        if (Boolean.FALSE.equals(linked)) return null;
        FormsClient.Reply r = forms.get("/overview", Map.of("email", email, "since", now.minus(Duration.ofDays(1)).toString()));
        if (!r.ok()) return null;
        Counts c = counts(r.json(), now, zone(tz));
        last.put(userId, c);
        return line(c);
    }

    static String line(Counts c) {
        List<String> parts = new ArrayList<>();
        if (c.newResponses() > 0) parts.add(c.newResponses() + (c.newResponses() == 1 ? " new response" : " new responses"));
        if (c.approvals() > 0) parts.add(c.approvals() + (c.approvals() == 1 ? " approval waiting" : " approvals waiting"));
        if (!c.closing().isEmpty()) parts.add(c.closing().get(0));
        return parts.isEmpty() ? null : "Forms: " + String.join(" · ", parts);
    }
}
