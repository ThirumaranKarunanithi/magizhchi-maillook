package software.magizhchi.maillook.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.magizhchi.maillook.mail.FollowUps;
import software.magizhchi.maillook.mail.LaneClassifier;
import software.magizhchi.maillook.mail.MessageRepo;
import software.magizhchi.maillook.mail.Waiting;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Once a minute: snoozed mail whose time came goes back on top of its lane (unread), and mail you sent
 * with "remind me if nobody replies" comes back in Needs me when nobody else wrote in that thread.
 * Both ring the person's browsers and phones. In the morning, at the hour they chose, the day's
 * briefing rings too.
 */
@Service
public class Reminders {

    private static final Logger log = LoggerFactory.getLogger(Reminders.class);

    private final JdbcTemplate jdbc;
    private final MessageRepo messages;
    private final FollowUps followUps;
    private final PushService push;
    private final Waiting waiting;

    public Reminders(JdbcTemplate jdbc, MessageRepo messages, FollowUps followUps, PushService push, Waiting waiting) {
        this.waiting = waiting;
        this.jdbc = jdbc;
        this.messages = messages;
        this.followUps = followUps;
        this.push = push;
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void onTimer() {
        try {
            run(Instant.now());
        } catch (Exception e) {
            log.warn("Reminders failed: {}", e.toString());
        }
    }

    /** @return how many mails came back. */
    public int run(Instant now) {
        OffsetDateTime at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        int back = 0;

        List<Map<String, Object>> woke = jdbc.queryForList(
                "SELECT m.id, a.user_id, m.subject, m.from_name, m.from_email FROM messages m JOIN accounts a ON a.id = m.account_id " +
                        "WHERE m.snoozed_until IS NOT NULL AND m.snoozed_until <= ? ORDER BY m.snoozed_until LIMIT 500", at);
        for (Map<String, Object> w : woke) {
            long id = ((Number) w.get("id")).longValue();
            int n = jdbc.update("UPDATE messages SET snoozed_until = NULL, back_at = ?, back_reason = 'snooze', seen = FALSE " +
                    "WHERE id = ? AND snoozed_until IS NOT NULL", at, id);
            if (n == 0) continue;
            back++;
            String who = str(w.get("from_name"), str(w.get("from_email"), "Someone"));
            push.reminder(((Number) w.get("user_id")).longValue(), "Back from snooze: " + who,
                    str(w.get("subject"), "(no subject)"), "/?open=" + id, "maillook-snooze-" + id);
        }

        for (FollowUps.Due d : followUps.dueBy(now)) {
            try {
                MessageRepo.Row sent = messages.find(d.userId(), d.messageId()).orElse(null);
                if (sent != null && !answered(sent)) {
                    jdbc.update("UPDATE messages SET back_at = ?, back_reason = 'no_reply' WHERE id = ?", at, sent.id());
                    back++;
                    String to = sent.to() == null || sent.to().isEmpty() ? "them" : first(sent.to().get(0));
                    push.reminder(d.userId(), "No reply yet from " + to,
                            sent.subject() == null || sent.subject().isBlank() ? "(no subject)" : sent.subject(),
                            "/?open=" + sent.id(), "maillook-noreply-" + sent.id());
                }
            } finally {
                followUps.delete(d.id());
            }
        }
        briefings(now);
        try {
            push.forgetOldAlerts(now);
        } catch (Exception e) {
            // best effort; tomorrow's run tries again
        }
        return back;
    }

    /**
     * The morning briefing alert, once a day, in the first three hours after the hour the person chose
     * (so a late deploy doesn't ring at midnight).
     * @return how many people got it.
     */
    public int briefings(Instant now) {
        // People with a browser or phone that allowed alerts, or the desktop app running in the last two days.
        List<Map<String, Object>> due = jdbc.queryForList(
                "SELECT u.id, u.tz, u.briefing_hour, u.briefing_sent_on FROM users u WHERE u.briefing_hour IS NOT NULL AND u.tz IS NOT NULL " +
                        "AND u.notify <> 'off' AND (EXISTS (SELECT 1 FROM push_subscriptions p WHERE p.user_id = u.id) OR u.desktop_seen_at > ?)",
                OffsetDateTime.ofInstant(now.minus(java.time.Duration.ofDays(2)), ZoneOffset.UTC));
        int sent = 0;
        for (Map<String, Object> u : due) {
            try {
                long userId = ((Number) u.get("id")).longValue();
                ZonedDateTime local = now.atZone(ZoneId.of(u.get("tz").toString()));
                int hour = ((Number) u.get("briefing_hour")).intValue();
                LocalDate last = u.get("briefing_sent_on") == null ? null : LocalDate.parse(u.get("briefing_sent_on").toString().substring(0, 10));
                if (local.getHour() < hour || local.getHour() >= hour + 3 || local.toLocalDate().equals(last)) continue;
                jdbc.update("UPDATE users SET briefing_sent_on = ? WHERE id = ?", local.toLocalDate(), userId);
                long needs = messages.unseenInLane(userId, null, LaneClassifier.NEEDS_ME, now.minus(Duration.ofDays(30)), now.plus(Duration.ofDays(1)));
                int waits = waiting.list(userId, null, now, false).size();
                String body = needs == 0 ? "Nobody is waiting on you" : needs == 1 ? "1 person is waiting on you" : needs + " people are waiting on you";
                if (waits > 0) body += " · you're waiting on " + waits + (waits == 1 ? " reply" : " replies");
                push.reminder(userId, "Your morning briefing", body + ". Tap for today's plan.", "/today", "maillook-briefing");
                sent++;
            } catch (Exception e) {
                log.info("Briefing alert skipped: {}", e.toString());
            }
        }
        return sent;
    }

    /** Did anyone other than this person write in the thread after the mail was sent? */
    private boolean answered(MessageRepo.Row sent) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM messages t WHERE t.account_id = ? AND t.thread_key = ? AND t.received_at > ? " +
                        "AND t.from_email IS NOT NULL AND LOWER(t.from_email) NOT IN (SELECT LOWER(x.email) FROM accounts x WHERE x.user_id = ?)",
                Integer.class, sent.accountId(), sent.threadKey(), sent.receivedAt(), sent.userId());
        return n != null && n > 0;
    }

    /** "Priya Raman <priya@x.com>" → "Priya Raman". */
    private static String first(String address) {
        String s = address.trim();
        int lt = s.indexOf('<');
        if (lt > 0) return s.substring(0, lt).replace("\"", "").trim();
        return s.replace("<", "").replace(">", "");
    }

    private static String str(Object o, String fallback) {
        return o == null || o.toString().isBlank() ? fallback : o.toString();
    }
}
