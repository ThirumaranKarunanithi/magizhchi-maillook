package software.magizhchi.maillook.suite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** MailLook's users, each tied to one Magizhchi ID account. */
@Repository
public class MailLookUsers {

    private final JdbcTemplate jdbc;

    public MailLookUsers(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Finds the user for this Magizhchi ID account (linking an older same-email user), or creates one. */
    public synchronized long signIn(SuiteTokens.Identity who) {
        List<Long> byAccount = jdbc.queryForList("SELECT id FROM users WHERE magizhchi_account_id = ?", Long.class, who.accountId());
        String name = who.name() == null || who.name().isBlank() ? who.email().substring(0, who.email().indexOf('@')) : who.name().trim();
        if (!byAccount.isEmpty()) {
            jdbc.update("UPDATE users SET email = ?, name = ? WHERE id = ?", who.email(), name, byAccount.get(0));
            return byAccount.get(0);
        }
        List<Long> byEmail = jdbc.queryForList("SELECT id FROM users WHERE LOWER(email) = LOWER(?) AND magizhchi_account_id IS NULL",
                Long.class, who.email());
        if (!byEmail.isEmpty()) {
            jdbc.update("UPDATE users SET magizhchi_account_id = ?, name = ? WHERE id = ?", who.accountId(), name, byEmail.get(0));
            return byEmail.get(0);
        }
        jdbc.update("INSERT INTO users (email, name, magizhchi_account_id) VALUES (?, ?, ?)", who.email(), name, who.accountId());
        return jdbc.queryForObject("SELECT id FROM users WHERE magizhchi_account_id = ?", Long.class, who.accountId());
    }

    /** The Magizhchi ID identity behind a MailLook user, if they have one. */
    public Optional<SuiteTokens.Identity> identity(long userId) {
        return jdbc.query("SELECT magizhchi_account_id, email, name FROM users WHERE id = ?",
                        (rs, i) -> rs.getString(1) == null ? null : new SuiteTokens.Identity(rs.getString(1), rs.getString(2), rs.getString(3)),
                        userId)
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }

    public Optional<Map<String, Object>> view(long userId) {
        return jdbc.queryForList("SELECT id, email, name FROM users WHERE id = ?", userId).stream().findFirst().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ((Number) r.get("id")).longValue());
            m.put("email", r.get("email"));
            m.put("name", r.get("name"));
            return m;
        });
    }
}
