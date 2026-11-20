package software.magizhchi.maillook.suite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import software.magizhchi.suite.orgs.SuiteOrgs;

import java.util.List;
import java.util.Locale;

/**
 * "Forward this to HR", "send job applications to the manager": the people the user works with whose designation
 * (set by the owner in Magizhchi ID → Organisations) answers the ask. Only colleagues from the user's own
 * organisations are ever returned, so a role can't land on a stranger's address.
 */
@Component
public class Colleagues {

    private static final Logger log = LoggerFactory.getLogger(Colleagues.class);

    /** Who an ask found: the best-matching people (one, or the few who share the title), or why nobody. */
    public record Found(List<SuiteOrgs.Teammate> people, String problem) {
        public boolean any() {
            return !people.isEmpty();
        }

        /** The best match is the same words or the initials ("HR", "Human Resources", "MD"). */
        public boolean exact() {
            return any() && people.get(0).match() >= 3;
        }

        public List<String> emails() {
            return people.stream().map(SuiteOrgs.Teammate::email).toList();
        }

        /** "Priya (HR) <priya@shop.in>, Meena (HR) <meena@shop.in>" */
        public String who() {
            return String.join(", ", people.stream().map(Colleagues::label).toList());
        }
    }

    private final SuiteOrgs orgs;
    private final JdbcTemplate jdbc;

    public Colleagues(SuiteOrgs orgs, JdbcTemplate jdbc) {
        this.orgs = orgs;
        this.jdbc = jdbc;
    }

    /** The best-matching colleagues for {@code ask} ("HR", "the manager"); empty with a reason when none. */
    public Found find(long userId, String ask) {
        String q = ask(ask);
        if (q.isEmpty()) return new Found(List.of(), null);
        List<String> mine = jdbc.queryForList("SELECT email FROM users WHERE id = ?", String.class, userId);
        if (mine.isEmpty()) return new Found(List.of(), null);
        try {
            List<SuiteOrgs.Teammate> all = orgs.teammates(mine.get(0), q).stream()
                    .filter(t -> t.match() > 0 && t.email() != null && !t.email().isBlank())
                    .toList();
            if (all.isEmpty()) return new Found(List.of(), null);
            int top = all.get(0).match();
            return new Found(all.stream().filter(t -> t.match() == top).limit(10).toList(), null);
        } catch (RuntimeException e) {
            // ID not configured or not reachable: the old behaviour (ask for the address) stands.
            log.warn("Colleague lookup for '{}' failed: {}", q, e.getMessage());
            return new Found(List.of(), "I can't reach Magizhchi ID right now to see who your " + q + " is.");
        }
    }

    /** "the manager", "to our HR", "my accountant" → "manager", "HR", "accountant"; an address → "". */
    static String ask(String raw) {
        if (raw == null) return "";
        String q = raw.trim();
        if (q.contains("@")) return "";
        q = q.replaceFirst("(?i)^((the|my|our|to)\\s+)+", "").trim();
        return q.length() > 60 ? "" : q;
    }

    static String label(SuiteOrgs.Teammate t) {
        String who = t.name() != null && !t.name().isBlank() ? t.name() : t.email();
        String what = t.designation() == null || t.designation().isBlank() ? "" : " (" + t.designation() + ")";
        return who + what + " <" + t.email().toLowerCase(Locale.ROOT) + ">";
    }
}
