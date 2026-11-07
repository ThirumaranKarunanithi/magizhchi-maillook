package software.magizhchi.maillook.mail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import software.magizhchi.maillook.common.Json;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public class MessageRepo {

    /** A row about to be stored. */
    public record NewMessage(long accountId, String folder, Long uid, String messageId, String threadKey,
                             String fromName, String fromEmail, List<String> to, List<String> cc,
                             String subject, String snippet, String bodyText, String bodyHtml,
                             List<Map<String, Object>> attachments, OffsetDateTime receivedAt,
                             boolean seen, boolean flagged, String lane, String laneSource,
                             String listUnsubscribe, boolean listUnsubscribePost) {}

    /** A stored row (bodies included only when asked for). */
    public record Row(long id, long accountId, String accountEmail, String accountColor, long userId,
                      String folder, Long uid, String messageId, String threadKey,
                      String fromName, String fromEmail, List<String> to, List<String> cc,
                      String subject, String snippet, String bodyText, String bodyHtml,
                      List<Map<String, Object>> attachments, OffsetDateTime receivedAt,
                      boolean seen, boolean flagged, String lane, String laneSource,
                      String listUnsubscribe, boolean listUnsubscribePost, boolean unsubscribed, int threadCount) {

        public Map<String, Object> summary() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("accountId", accountId);
            m.put("accountEmail", accountEmail);
            m.put("accountColor", accountColor);
            m.put("folder", folder);
            m.put("fromName", fromName);
            m.put("fromEmail", fromEmail);
            m.put("to", to);
            m.put("cc", cc);
            m.put("subject", subject);
            m.put("snippet", snippet);
            m.put("receivedAt", receivedAt == null ? null : receivedAt.toInstant().toString());
            m.put("seen", seen);
            m.put("flagged", flagged);
            m.put("lane", lane);
            m.put("laneSource", laneSource);
            m.put("hasAttachments", attachments != null && !attachments.isEmpty());
            // Names of the first few files, so the list can show them without opening the mail.
            m.put("attachmentNames", attachments == null ? List.of() : attachments.stream().limit(3)
                    .map(a -> String.valueOf(a.getOrDefault("name", "attachment"))).toList());
            m.put("attachmentCount", attachments == null ? 0 : attachments.size());
            m.put("threadKey", threadKey);
            m.put("threadCount", Math.max(1, threadCount));
            m.put("canUnsubscribe", listUnsubscribe != null && !unsubscribed);
            return m;
        }
    }

    private static final String COLS =
            "m.id, m.account_id, a.email AS account_email, a.color AS account_color, a.user_id, m.folder, m.uid, m.message_id, " +
            "m.thread_key, m.from_name, m.from_email, m.to_list, m.cc_list, m.subject, m.snippet, m.attachments, m.received_at, " +
            "m.seen, m.flagged, m.lane, m.lane_source, m.list_unsubscribe, m.list_unsubscribe_post, m.unsubscribed, " +
            "(SELECT COUNT(*) FROM messages t WHERE t.account_id = m.account_id AND t.thread_key = m.thread_key) AS thread_count";

    private static RowMapper<Row> mapper(boolean bodies) {
        return (rs, i) -> new Row(
                rs.getLong("id"), rs.getLong("account_id"), rs.getString("account_email"), rs.getString("account_color"),
                rs.getLong("user_id"), rs.getString("folder"), (Long) rs.getObject("uid", Long.class),
                rs.getString("message_id"), rs.getString("thread_key"), rs.getString("from_name"), rs.getString("from_email"),
                Json.strings(rs.getString("to_list")), Json.strings(rs.getString("cc_list")), rs.getString("subject"),
                rs.getString("snippet"), bodies ? rs.getString("body_text") : null, bodies ? rs.getString("body_html") : null,
                Json.maps(rs.getString("attachments")), rs.getObject("received_at", OffsetDateTime.class),
                rs.getBoolean("seen"), rs.getBoolean("flagged"), rs.getString("lane"), rs.getString("lane_source"),
                rs.getString("list_unsubscribe"), rs.getBoolean("list_unsubscribe_post"), rs.getBoolean("unsubscribed"),
                rs.getInt("thread_count"));
    }

    private final JdbcTemplate jdbc;

    public MessageRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── writes ────────────────────────────────────────────────────

    /**
     * Stores a message unless the folder already has one with the same Message-ID (e.g. the copy we
     * saved ourselves after sending); in that case the existing row just learns its UID.
     * @return the new row id, or null when it was a duplicate.
     */
    public Long insert(NewMessage n) {
        if (n.messageId() != null) {
            List<Long> existing = jdbc.queryForList(
                    "SELECT id FROM messages WHERE account_id = ? AND folder = ? AND message_id = ?",
                    Long.class, n.accountId(), n.folder(), n.messageId());
            if (!existing.isEmpty()) {
                if (n.uid() != null) jdbc.update("UPDATE messages SET uid = ? WHERE id = ? AND uid IS NULL", n.uid(), existing.get(0));
                return null;
            }
        }
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO messages (account_id, folder, uid, message_id, thread_key, from_name, from_email, to_list, cc_list, " +
                    "subject, snippet, body_text, body_html, attachments, received_at, seen, flagged, lane, lane_source, " +
                    "list_unsubscribe, list_unsubscribe_post) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setLong(i++, n.accountId());
            ps.setString(i++, n.folder());
            if (n.uid() == null) ps.setNull(i++, Types.BIGINT); else ps.setLong(i++, n.uid());
            ps.setString(i++, cut(n.messageId(), 998));
            ps.setString(i++, cut(n.threadKey(), 998));
            ps.setString(i++, cut(n.fromName(), 320));
            ps.setString(i++, cut(n.fromEmail(), 320));
            ps.setString(i++, Json.write(n.to()));
            ps.setString(i++, Json.write(n.cc()));
            ps.setString(i++, cut(n.subject(), 2000));
            ps.setString(i++, cut(n.snippet(), 400));
            ps.setString(i++, n.bodyText());
            ps.setString(i++, n.bodyHtml());
            ps.setString(i++, Json.write(n.attachments()));
            ps.setObject(i++, n.receivedAt());
            ps.setBoolean(i++, n.seen());
            ps.setBoolean(i++, n.flagged());
            ps.setString(i++, n.lane());
            ps.setString(i++, n.laneSource());
            ps.setString(i++, cut(n.listUnsubscribe(), 2000));
            ps.setBoolean(i, n.listUnsubscribePost());
            return ps;
        }, kh);
        return ((Number) kh.getKeys().get("id")).longValue();
    }

    public void deleteFolder(long accountId, String folder) {
        jdbc.update("DELETE FROM messages WHERE account_id = ? AND folder = ?", accountId, folder);
    }

    /**
     * Brings seen/flagged in line with the server for UIDs >= minUid, and drops rows the server no
     * longer has there (moved or deleted from another device).
     */
    public void reconcile(long accountId, String folder, long minUid, Map<Long, boolean[]> serverFlags) {
        List<Object[]> rows = jdbc.query(
                "SELECT id, uid, seen, flagged FROM messages WHERE account_id = ? AND folder = ? AND uid >= ?",
                (rs, i) -> new Object[]{rs.getLong(1), rs.getLong(2), rs.getBoolean(3), rs.getBoolean(4)},
                accountId, folder, minUid);
        for (Object[] r : rows) {
            long id = (Long) r[0];
            boolean[] f = serverFlags.get((Long) r[1]);
            if (f == null) {
                jdbc.update("DELETE FROM messages WHERE id = ?", id);
            } else if (f[0] != (Boolean) r[2] || f[1] != (Boolean) r[3]) {
                jdbc.update("UPDATE messages SET seen = ?, flagged = ? WHERE id = ?", f[0], f[1], id);
            }
        }
    }

    public void setSeen(long id, boolean seen) {
        jdbc.update("UPDATE messages SET seen = ? WHERE id = ?", seen, id);
    }

    public void setFlagged(long id, boolean flagged) {
        jdbc.update("UPDATE messages SET flagged = ? WHERE id = ?", flagged, id);
    }

    public void moved(long id, String folder, Long newUid) {
        jdbc.update("UPDATE messages SET folder = ?, uid = ? WHERE id = ?", folder, newUid, id);
    }

    public void setLane(long id, String lane, String source) {
        jdbc.update("UPDATE messages SET lane = ?, lane_source = ? WHERE id = ?", lane, source, id);
    }

    /**
     * Newest inbox mail still sorted only by heuristics, so the AI pass can catch up on mail
     * it missed (e.g. PA was unreachable when the mail first synced).
     */
    public List<Long> awaitingAiLane(long accountId, int limit) {
        return jdbc.queryForList("SELECT id FROM messages WHERE account_id = ? AND folder = 'INBOX' " +
                "AND lane_source = 'heuristic' ORDER BY received_at DESC LIMIT " + Math.max(1, limit), Long.class, accountId);
    }

    /** Re-lanes the sender's other inbox mail the AI or heuristics placed (never the user's own choices). */
    public void applySenderRule(long userId, String senderEmail, String lane) {
        jdbc.update("UPDATE messages SET lane = ?, lane_source = 'rule' WHERE LOWER(from_email) = LOWER(?) AND folder = 'INBOX' " +
                "AND (lane_source IS NULL OR lane_source <> 'user') AND account_id IN (SELECT id FROM accounts WHERE user_id = ?)",
                lane, senderEmail, userId);
    }

    public void markUnsubscribed(long accountId, String senderEmail) {
        jdbc.update("UPDATE messages SET unsubscribed = TRUE WHERE account_id = ? AND LOWER(from_email) = LOWER(?)", accountId, senderEmail);
    }

    // ── reads ─────────────────────────────────────────────────────

    public Optional<Row> find(long userId, long id) {
        return jdbc.query("SELECT " + COLS + ", m.body_text, m.body_html FROM messages m JOIN accounts a ON a.id = m.account_id " +
                "WHERE m.id = ? AND a.user_id = ?", mapper(true), id, userId).stream().findFirst();
    }

    public List<Row> thread(long accountId, String threadKey) {
        return jdbc.query("SELECT " + COLS + ", m.body_text, m.body_html FROM messages m JOIN accounts a ON a.id = m.account_id " +
                "WHERE m.account_id = ? AND m.thread_key = ? ORDER BY m.received_at ASC, m.id ASC", mapper(true), accountId, threadKey);
    }

    /** A mailbox's inbox mail since a time, newest first, bodies included (for finding report series). */
    public List<Row> recent(long accountId, OffsetDateTime since, int limit) {
        return jdbc.query("SELECT " + COLS + ", m.body_text, m.body_html FROM messages m JOIN accounts a ON a.id = m.account_id " +
                "WHERE m.account_id = ? AND m.folder = 'INBOX' AND m.received_at >= ? ORDER BY m.received_at DESC, m.id DESC LIMIT ?",
                mapper(true), accountId, since, limit);
    }

    public List<Row> list(long userId, String folder, String lane, Long accountId, MailSearch search,
                          OffsetDateTime cursorAt, Long cursorId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM messages m JOIN accounts a ON a.id = m.account_id WHERE a.user_id = ? AND m.folder = ?");
        List<Object> args = new ArrayList<>(List.of(userId, folder));
        if (lane != null) {
            sql.append(" AND m.lane = ?");
            args.add(lane);
        }
        if (accountId != null) {
            sql.append(" AND m.account_id = ?");
            args.add(accountId);
        }
        if (search != null) search.appendTo(sql, args);
        if (cursorAt != null && cursorId != null) {
            sql.append(" AND (m.received_at < ? OR (m.received_at = ? AND m.id < ?))");
            args.add(cursorAt);
            args.add(cursorAt);
            args.add(cursorId);
        }
        sql.append(" ORDER BY m.received_at DESC, m.id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), mapper(false), args.toArray());
    }

    public Map<String, long[]> laneCounts(long userId, Long accountId) {
        Map<String, long[]> out = new LinkedHashMap<>();
        String sql = "SELECT m.lane, COUNT(*), SUM(CASE WHEN m.seen THEN 0 ELSE 1 END) FROM messages m JOIN accounts a ON a.id = m.account_id " +
                "WHERE a.user_id = ? AND m.folder = 'INBOX'" + (accountId != null ? " AND m.account_id = ?" : "") + " GROUP BY m.lane";
        Object[] args = accountId != null ? new Object[]{userId, accountId} : new Object[]{userId};
        jdbc.query(sql, (ResultSet rs) -> {
            out.put(rs.getString(1), new long[]{rs.getLong(2), rs.getLong(3)});
        }, args);
        return out;
    }

    public Map<Long, Long> unreadByAccount(long userId) {
        Map<Long, Long> out = new LinkedHashMap<>();
        jdbc.query("SELECT m.account_id, SUM(CASE WHEN m.seen THEN 0 ELSE 1 END) FROM messages m JOIN accounts a ON a.id = m.account_id " +
                "WHERE a.user_id = ? AND m.folder = 'INBOX' GROUP BY m.account_id", (ResultSet rs) -> {
            out.put(rs.getLong(1), rs.getLong(2));
        }, userId);
        return out;
    }

    /** Count of unseen inbox mail in a lane received between two instants. */
    public long unseenInLane(long userId, Long accountId, String lane, Instant from, Instant to) {
        String sql = "SELECT COUNT(*) FROM messages m JOIN accounts a ON a.id = m.account_id WHERE a.user_id = ? AND m.folder = 'INBOX' " +
                "AND m.lane = ? AND m.seen = FALSE AND m.received_at >= ? AND m.received_at < ?" + (accountId != null ? " AND m.account_id = ?" : "");
        List<Object> args = new ArrayList<>(List.of(userId, lane, OffsetDateTime.ofInstant(from, ZoneOffset.UTC), OffsetDateTime.ofInstant(to, ZoneOffset.UTC)));
        if (accountId != null) args.add(accountId);
        Long n = jdbc.queryForObject(sql, Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    public List<Map<String, Object>> subscriptions(long userId) {
        return jdbc.query(
                "SELECT m.account_id, a.email AS account_email, LOWER(m.from_email) AS sender, MAX(m.from_name) AS from_name, COUNT(*) AS cnt, " +
                "SUM(CASE WHEN m.seen THEN 0 ELSE 1 END) AS unread, MAX(m.received_at) AS last_at, MAX(m.id) AS latest_id " +
                "FROM messages m JOIN accounts a ON a.id = m.account_id " +
                "WHERE a.user_id = ? AND m.list_unsubscribe IS NOT NULL AND m.unsubscribed = FALSE AND m.from_email IS NOT NULL " +
                "GROUP BY m.account_id, a.email, LOWER(m.from_email) ORDER BY cnt DESC, last_at DESC",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("fromEmail", rs.getString("sender"));
                    m.put("fromName", rs.getString("from_name"));
                    m.put("accountId", rs.getLong("account_id"));
                    m.put("accountEmail", rs.getString("account_email"));
                    m.put("count", rs.getLong("cnt"));
                    m.put("unread", rs.getLong("unread"));
                    OffsetDateTime last = rs.getObject("last_at", OffsetDateTime.class);
                    m.put("lastAt", last == null ? null : last.toInstant().toString());
                    m.put("latestMessageId", rs.getLong("latest_id"));
                    return m;
                }, userId);
    }

    /** Recent mail the account owner wrote themselves, for learning their tone. */
    public List<String> sentSamples(long accountId, int n) {
        return jdbc.queryForList("SELECT body_text FROM messages WHERE account_id = ? AND folder = 'SENT' AND body_text IS NOT NULL " +
                "ORDER BY received_at DESC LIMIT ?", String.class, accountId, n);
    }

    public List<Row> byIds(Set<Long> ids) {
        if (ids.isEmpty()) return List.of();
        String in = String.join(",", ids.stream().map(String::valueOf).toList());
        return jdbc.query("SELECT " + COLS + ", m.body_text, m.body_html FROM messages m JOIN accounts a ON a.id = m.account_id WHERE m.id IN (" + in + ")", mapper(true));
    }

    public Optional<String> aiCache(long messageId, String kind) {
        return jdbc.queryForList("SELECT payload FROM ai_cache WHERE message_id = ? AND kind = ?", String.class, messageId, kind)
                .stream().findFirst();
    }

    public void putAiCache(long messageId, String kind, String payload) {
        jdbc.update("DELETE FROM ai_cache WHERE message_id = ? AND kind = ?", messageId, kind);
        jdbc.update("INSERT INTO ai_cache (message_id, kind, payload) VALUES (?,?,?)", messageId, kind, payload);
    }

    // ── sender rules ──────────────────────────────────────────────

    public Map<String, String> senderRules(long userId) {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("SELECT sender_email, lane FROM sender_rules WHERE user_id = ?", (ResultSet rs) -> {
            out.put(rs.getString(1).toLowerCase(Locale.ROOT), rs.getString(2));
        }, userId);
        return out;
    }

    public void putSenderRule(long userId, String senderEmail, String lane) {
        jdbc.update("DELETE FROM sender_rules WHERE user_id = ? AND sender_email = ?", userId, senderEmail.toLowerCase(Locale.ROOT));
        jdbc.update("INSERT INTO sender_rules (user_id, sender_email, lane) VALUES (?,?,?)", userId, senderEmail.toLowerCase(Locale.ROOT), lane);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
