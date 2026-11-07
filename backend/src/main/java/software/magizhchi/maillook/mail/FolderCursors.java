package software.magizhchi.maillook.mail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Where sync got to in the folders beyond Inbox and Sent: Trash, Spam, Archive and the mailbox's own
 * folders (codes "F:" + the server's folder name) once someone opens one.
 */
@Repository
public class FolderCursors {

    public record Cursor(long accountId, String code, String serverName, Long uidValidity, Long lastUid) {}

    private final JdbcTemplate jdbc;

    public FolderCursors(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Cursor map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Cursor(rs.getLong("account_id"), rs.getString("code"), rs.getString("server_name"),
                (Long) rs.getObject("uid_validity", Long.class), (Long) rs.getObject("last_uid", Long.class));
    }

    public Optional<Cursor> find(long accountId, String code) {
        return jdbc.query("SELECT * FROM folder_cursors WHERE account_id = ? AND code = ?", (rs, i) -> map(rs), accountId, code)
                .stream().findFirst();
    }

    public List<Cursor> forAccount(long accountId) {
        return jdbc.query("SELECT * FROM folder_cursors WHERE account_id = ? ORDER BY code", (rs, i) -> map(rs), accountId);
    }

    /** Whether any of this person's mailboxes has opened that folder code. */
    public boolean known(long userId, String code) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM folder_cursors c JOIN accounts a ON a.id = c.account_id " +
                "WHERE a.user_id = ? AND c.code = ?", Long.class, userId, code);
        return n != null && n > 0;
    }

    /** Remembers a folder to keep in sync (no-op when it already is). */
    public void open(long accountId, String code, String serverName) {
        if (find(accountId, code).isPresent()) return;
        jdbc.update("INSERT INTO folder_cursors (account_id, code, server_name) VALUES (?, ?, ?)", accountId, code, serverName);
    }

    public void save(long accountId, String code, String serverName, Long uidValidity, Long lastUid) {
        int n = jdbc.update("UPDATE folder_cursors SET server_name = ?, uid_validity = ?, last_uid = ?, synced_at = ? " +
                        "WHERE account_id = ? AND code = ?",
                serverName, uidValidity, lastUid, Timestamp.from(Instant.now()), accountId, code);
        if (n == 0) {
            jdbc.update("INSERT INTO folder_cursors (account_id, code, server_name, uid_validity, last_uid, synced_at) VALUES (?, ?, ?, ?, ?, ?)",
                    accountId, code, serverName, uidValidity, lastUid, Timestamp.from(Instant.now()));
        }
    }

    public void close(long accountId, String code) {
        jdbc.update("DELETE FROM folder_cursors WHERE account_id = ? AND code = ?", accountId, code);
    }
}
