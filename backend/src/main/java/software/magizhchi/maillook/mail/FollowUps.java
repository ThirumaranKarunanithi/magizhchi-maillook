package software.magizhchi.maillook.mail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** "Remind me if nobody replies by …" on mail you sent. */
@Repository
public class FollowUps {

    public record Due(long id, long userId, long messageId) {}

    private final JdbcTemplate jdbc;

    public FollowUps(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void set(long userId, long messageId, OffsetDateTime at) {
        int n = jdbc.update("UPDATE followups SET due_at = ? WHERE message_id = ?", at, messageId);
        if (n == 0) jdbc.update("INSERT INTO followups (user_id, message_id, due_at) VALUES (?, ?, ?)", userId, messageId, at);
    }

    public void cancel(long messageId) {
        jdbc.update("DELETE FROM followups WHERE message_id = ?", messageId);
    }

    public Optional<Instant> due(long messageId) {
        List<OffsetDateTime> d = jdbc.queryForList("SELECT due_at FROM followups WHERE message_id = ?", OffsetDateTime.class, messageId);
        return d.stream().findFirst().map(OffsetDateTime::toInstant);
    }

    public List<Due> dueBy(Instant now) {
        return jdbc.query("SELECT id, user_id, message_id FROM followups WHERE due_at <= ? ORDER BY due_at LIMIT 200",
                (rs, i) -> new Due(rs.getLong(1), rs.getLong(2), rs.getLong(3)), OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC));
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM followups WHERE id = ?", id);
    }
}
