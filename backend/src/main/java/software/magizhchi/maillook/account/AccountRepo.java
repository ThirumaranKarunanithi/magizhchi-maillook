package software.magizhchi.maillook.account;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class AccountRepo {

    private final JdbcTemplate jdbc;

    private static final RowMapper<Account> MAPPER = (rs, i) -> new Account(
            rs.getLong("id"), rs.getLong("user_id"), rs.getString("email"), rs.getString("display_name"),
            rs.getString("color"), rs.getString("provider"), rs.getString("imap_host"), rs.getInt("imap_port"),
            rs.getString("smtp_host"), rs.getInt("smtp_port"), rs.getString("password_enc"), rs.getString("status"),
            rs.getObject("last_sync_at", OffsetDateTime.class), rs.getString("last_error"),
            (Long) rs.getObject("inbox_uid_validity", Long.class), (Long) rs.getObject("inbox_last_uid", Long.class),
            (Long) rs.getObject("sent_uid_validity", Long.class), (Long) rs.getObject("sent_last_uid", Long.class));

    public AccountRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Account> forUser(long userId) {
        return jdbc.query("SELECT * FROM accounts WHERE user_id = ? ORDER BY id", MAPPER, userId);
    }

    public List<Account> all() {
        return jdbc.query("SELECT * FROM accounts ORDER BY id", MAPPER);
    }

    public Optional<Account> find(long id) {
        return jdbc.query("SELECT * FROM accounts WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<Account> findByEmail(long userId, String email) {
        return jdbc.query("SELECT * FROM accounts WHERE user_id = ? AND LOWER(email) = LOWER(?)", MAPPER, userId, email)
                .stream().findFirst();
    }

    public Optional<Account> anyByEmail(String email) {
        return jdbc.query("SELECT * FROM accounts WHERE LOWER(email) = LOWER(?) ORDER BY id", MAPPER, email)
                .stream().findFirst();
    }

    public long insert(long userId, String email, String displayName, String color, ProviderDetector.Preset p, String passwordEnc) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO accounts (user_id, email, display_name, color, provider, imap_host, imap_port, smtp_host, smtp_port, password_enc, status) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?, 'new')", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, email);
            ps.setString(3, displayName);
            ps.setString(4, color);
            ps.setString(5, p.provider());
            ps.setString(6, p.imapHost());
            ps.setInt(7, p.imapPort());
            ps.setString(8, p.smtpHost());
            ps.setInt(9, p.smtpPort());
            ps.setString(10, passwordEnc);
            return ps;
        }, kh);
        return ((Number) kh.getKeys().get("id")).longValue();
    }

    public void updateProfile(long id, String displayName, String color) {
        jdbc.update("UPDATE accounts SET display_name = COALESCE(?, display_name), color = COALESCE(?, color) WHERE id = ?",
                displayName, color, id);
    }

    public void updatePassword(long id, String passwordEnc) {
        jdbc.update("UPDATE accounts SET password_enc = ?, status = 'new', last_error = NULL WHERE id = ?", passwordEnc, id);
    }

    public void setStatus(long id, String status, String error) {
        jdbc.update("UPDATE accounts SET status = ?, last_error = ? WHERE id = ?", status, error, id);
    }

    public void syncDone(long id) {
        jdbc.update("UPDATE accounts SET status = 'ok', last_error = NULL, last_sync_at = ? WHERE id = ?",
                OffsetDateTime.now(), id);
    }

    public void setInboxCursor(long id, Long uidValidity, Long lastUid) {
        jdbc.update("UPDATE accounts SET inbox_uid_validity = ?, inbox_last_uid = ? WHERE id = ?", uidValidity, lastUid, id);
    }

    public void setSentCursor(long id, Long uidValidity, Long lastUid) {
        jdbc.update("UPDATE accounts SET sent_uid_validity = ?, sent_last_uid = ? WHERE id = ?", uidValidity, lastUid, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM accounts WHERE id = ?", id);
    }
}
