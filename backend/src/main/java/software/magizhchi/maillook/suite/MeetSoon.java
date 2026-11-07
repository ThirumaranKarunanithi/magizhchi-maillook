package software.magizhchi.maillook.suite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import software.magizhchi.maillook.mail.MessageRepo;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Mail whose Magizhchi Meet meeting is about to start: from 30 minutes before until 15 minutes in,
 * the thread's latest inbox mail sits at the top of Needs me with a "Starts in 12 min" badge.
 */
@Component
public class MeetSoon {

    static final Duration AHEAD = Duration.ofMinutes(30);
    static final Duration GRACE = Duration.ofMinutes(15);

    public record Pinned(MessageRepo.Row row, Instant startsAt) {}

    private final JdbcTemplate jdbc;
    private final MessageRepo messages;

    public MeetSoon(JdbcTemplate jdbc, MessageRepo messages) {
        this.jdbc = jdbc;
        this.messages = messages;
    }

    /** Soonest first. {@code accountId} narrows to one mailbox when the list does. */
    public List<Pinned> soon(long userId, Long accountId, Instant now) {
        record Link(long accountId, String threadKey, Instant startsAt) {}
        List<Link> links = jdbc.query("SELECT l.account_id, l.thread_key, MIN(l.starts_at) AS starts FROM meet_links l " +
                        "JOIN accounts a ON a.id = l.account_id WHERE a.user_id = ? AND l.starts_at BETWEEN ? AND ? " +
                        "GROUP BY l.account_id, l.thread_key ORDER BY starts",
                (rs, i) -> new Link(rs.getLong(1), rs.getString(2), rs.getObject(3, OffsetDateTime.class).toInstant()),
                userId, OffsetDateTime.ofInstant(now.minus(GRACE), ZoneOffset.UTC), OffsetDateTime.ofInstant(now.plus(AHEAD), ZoneOffset.UTC));
        List<Pinned> out = new ArrayList<>();
        for (Link l : links) {
            if (accountId != null && accountId != l.accountId()) continue;
            List<MessageRepo.Row> thread = messages.thread(l.accountId(), l.threadKey());
            for (int i = thread.size() - 1; i >= 0; i--) {
                if ("INBOX".equals(thread.get(i).folder())) {
                    out.add(new Pinned(thread.get(i), l.startsAt()));
                    break;
                }
            }
        }
        return out;
    }
}
