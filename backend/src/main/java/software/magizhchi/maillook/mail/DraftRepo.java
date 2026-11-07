package software.magizhchi.maillook.mail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Unsent mail, kept in MailLook so closing the composer (or the tab) never loses it. */
@Repository
public class DraftRepo {

    public record Draft(long id, long userId, Long accountId, String mode, Long replyToMessageId,
                        List<String> to, List<String> cc, List<String> bcc,
                        String subject, String body, String inviteCardId, OffsetDateTime updatedAt) {

        public Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("accountId", accountId);
            m.put("mode", mode);
            m.put("replyToMessageId", replyToMessageId);
            m.put("to", to);
            m.put("cc", cc);
            m.put("bcc", bcc);
            m.put("subject", subject);
            m.put("body", body);
            m.put("inviteCardId", inviteCardId);
            m.put("updatedAt", updatedAt == null ? null : updatedAt.toInstant().toString());
            return m;
        }
    }

    private final JdbcTemplate jdbc;

    public DraftRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static String join(List<String> list) {
        if (list == null) return null;
        String s = String.join(", ", list.stream().map(String::trim).filter(x -> !x.isEmpty()).toList());
        return s.isEmpty() ? null : s;
    }

    static List<String> split(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    private Draft map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Draft(rs.getLong("id"), rs.getLong("user_id"), (Long) rs.getObject("account_id", Long.class),
                rs.getString("mode"), (Long) rs.getObject("reply_to_message_id", Long.class),
                split(rs.getString("to_list")), split(rs.getString("cc_list")), split(rs.getString("bcc_list")),
                rs.getString("subject"), rs.getString("body"), rs.getString("invite_card_id"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    public List<Draft> forUser(long userId, int limit) {
        return jdbc.query("SELECT * FROM drafts WHERE user_id = ? ORDER BY updated_at DESC, id DESC LIMIT ?",
                (rs, i) -> map(rs), userId, limit);
    }

    public Optional<Draft> find(long userId, long id) {
        return jdbc.query("SELECT * FROM drafts WHERE id = ? AND user_id = ?", (rs, i) -> map(rs), id, userId).stream().findFirst();
    }

    public long count(long userId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM drafts WHERE user_id = ?", Long.class, userId);
        return n == null ? 0 : n;
    }

    public long insert(long userId, Draft d, Instant at) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO drafts (user_id, account_id, mode, reply_to_message_id, to_list, cc_list, bcc_list, subject, body, invite_card_id, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", new String[]{"id"});
            ps.setLong(1, userId);
            ps.setObject(2, d.accountId());
            ps.setString(3, d.mode());
            ps.setObject(4, d.replyToMessageId());
            ps.setString(5, join(d.to()));
            ps.setString(6, join(d.cc()));
            ps.setString(7, join(d.bcc()));
            ps.setString(8, d.subject());
            ps.setString(9, d.body());
            ps.setString(10, d.inviteCardId());
            ps.setTimestamp(11, Timestamp.from(at));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public boolean update(long userId, long id, Draft d, Instant at) {
        return jdbc.update("UPDATE drafts SET account_id = ?, mode = ?, reply_to_message_id = ?, to_list = ?, cc_list = ?, bcc_list = ?, " +
                        "subject = ?, body = ?, invite_card_id = ?, updated_at = ? WHERE id = ? AND user_id = ?",
                d.accountId(), d.mode(), d.replyToMessageId(), join(d.to()), join(d.cc()), join(d.bcc()),
                d.subject(), d.body(), d.inviteCardId(), Timestamp.from(at), id, userId) > 0;
    }

    public boolean delete(long userId, long id) {
        return jdbc.update("DELETE FROM drafts WHERE id = ? AND user_id = ?", id, userId) > 0;
    }
}
