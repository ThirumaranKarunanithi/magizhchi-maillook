package software.magizhchi.maillook.mail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Private notes on a conversation (one per thread in a mailbox), seen only by the person who wrote them. */
@Repository
public class NoteRepo {

    public record Note(long id, String body, String paNoteId, OffsetDateTime updatedAt) {
        public Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("body", body);
            m.put("paNoteId", paNoteId);
            m.put("updatedAt", updatedAt == null ? null : updatedAt.toInstant().toString());
            return m;
        }
    }

    private final JdbcTemplate jdbc;

    public NoteRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Note> find(long accountId, String threadKey) {
        List<Note> n = jdbc.query("SELECT id, body, pa_note_id, updated_at FROM thread_notes WHERE account_id = ? AND thread_key = ?",
                (rs, i) -> new Note(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getObject(4, OffsetDateTime.class)),
                accountId, threadKey);
        return n.stream().findFirst();
    }

    public Note save(long userId, long accountId, String threadKey, String body) {
        OffsetDateTime now = OffsetDateTime.now();
        int n = jdbc.update("UPDATE thread_notes SET body = ?, updated_at = ? WHERE account_id = ? AND thread_key = ?",
                body, now, accountId, threadKey);
        if (n == 0) {
            jdbc.update("INSERT INTO thread_notes (user_id, account_id, thread_key, body, updated_at) VALUES (?, ?, ?, ?, ?)",
                    userId, accountId, threadKey, body, now);
        }
        return find(accountId, threadKey).orElseThrow();
    }

    public void setPaNote(long accountId, String threadKey, String paNoteId) {
        jdbc.update("UPDATE thread_notes SET pa_note_id = ? WHERE account_id = ? AND thread_key = ?", paNoteId, accountId, threadKey);
    }

    public void delete(long accountId, String threadKey) {
        jdbc.update("DELETE FROM thread_notes WHERE account_id = ? AND thread_key = ?", accountId, threadKey);
    }
}
