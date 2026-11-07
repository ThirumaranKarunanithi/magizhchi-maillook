package software.magizhchi.maillook.contacts;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import software.magizhchi.maillook.common.Json;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The people in someone's mail: everyone they wrote to (counts most) and the real people who wrote to
 * them (not newsletters, not no-reply robots), weighted towards the recent, plus who usually gets
 * mail together. Built from the local store, kept for a few minutes.
 */
@Service
public class PeopleService {

    private static final long FRESH_MS = 10 * 60_000;
    private static final double HALF_LIFE_DAYS = 60;
    private static final Pattern ROBOT = Pattern.compile(
            "^(no-?reply|do-?not-?reply|donotreply|mailer-daemon|postmaster|bounces?|notifications?|notify|alerts?|newsletter|news|updates?|info|support|hello|team|marketing|billing|accounts?)([+._-].*)?@.*");
    private static final Pattern ADDRESS = Pattern.compile("^\\s*\"?([^\"<]*?)\"?\\s*<([^>]+)>\\s*$");

    public static final class Person {
        public final String email;
        public String name;
        public double score;
        public int sent;
        public int received;
        public Instant last;
        final Map<Long, Integer> byAccount = new HashMap<>();
        final Map<String, Double> with = new HashMap<>();

        Person(String email) {
            this.email = email;
        }

        public Long usualAccount() {
            return byAccount.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        }

        public int fromAccount(Long accountId) {
            return accountId == null ? 0 : byAccount.getOrDefault(accountId, 0);
        }

        public double with(String other) {
            return with.getOrDefault(other, 0.0);
        }

        public Map<String, Double> together() {
            return with;
        }
    }

    public record People(Map<String, Person> byEmail, Set<String> own) {}

    private record Cached(long at, People people) {}

    private final JdbcTemplate jdbc;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    public PeopleService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void forget(long userId) {
        cache.remove(userId);
    }

    public People people(long userId) {
        Cached c = cache.get(userId);
        if (c != null && System.currentTimeMillis() - c.at() < FRESH_MS) return c.people();
        People p = build(userId, Instant.now());
        cache.put(userId, new Cached(System.currentTimeMillis(), p));
        return p;
    }

    static boolean robot(String email) {
        return email == null || ROBOT.matcher(email).matches();
    }

    /** "Priya Raman <Priya@x.com>" → ["Priya Raman", "priya@x.com"]; a bare address has no name. */
    static String[] split(String address) {
        if (address == null) return new String[]{null, null};
        Matcher m = ADDRESS.matcher(address);
        if (m.matches()) {
            String n = m.group(1).trim();
            return new String[]{n.isEmpty() ? null : n, m.group(2).trim().toLowerCase(Locale.ROOT)};
        }
        String e = address.trim().toLowerCase(Locale.ROOT);
        return new String[]{null, e.contains("@") ? e : null};
    }

    private static double weight(Instant at, Instant now) {
        if (at == null) return 0.2;
        double days = Math.max(0, (now.toEpochMilli() - at.toEpochMilli()) / 86_400_000.0);
        return Math.pow(0.5, days / HALF_LIFE_DAYS);
    }

    People build(long userId, Instant now) {
        Set<String> own = new HashSet<>();
        for (String e : jdbc.queryForList("SELECT LOWER(email) FROM accounts WHERE user_id = ?", String.class, userId)) own.add(e);
        Map<String, Person> map = new HashMap<>();
        OffsetDateTime since = OffsetDateTime.ofInstant(now.minus(java.time.Duration.ofDays(730)), ZoneOffset.UTC);

        // Mail they sent: everyone on To and Cc, and who was on it together.
        jdbc.query("SELECT m.account_id, m.to_list, m.cc_list, m.received_at FROM messages m JOIN accounts a ON a.id = m.account_id " +
                "WHERE a.user_id = ? AND m.folder = 'SENT' AND m.received_at >= ? ORDER BY m.received_at DESC LIMIT 4000", rs -> {
            long account = rs.getLong(1);
            Instant at = rs.getObject(4, OffsetDateTime.class).toInstant();
            double w = weight(at, now);
            Set<String> group = new LinkedHashSet<>();
            List<String> all = new ArrayList<>(Json.strings(rs.getString(2)));
            all.addAll(Json.strings(rs.getString(3)));
            for (String raw : all) {
                String[] ne = split(raw);
                if (ne[1] == null || own.contains(ne[1])) continue;
                Person p = map.computeIfAbsent(ne[1], Person::new);
                if (ne[0] != null && p.name == null) p.name = ne[0];
                p.sent++;
                p.score += 3 * w;
                p.byAccount.merge(account, 1, Integer::sum);
                if (p.last == null || at.isAfter(p.last)) p.last = at;
                group.add(ne[1]);
            }
            if (group.size() > 1 && group.size() <= 12) {
                for (String a : group) for (String b : group) {
                    if (!a.equals(b)) map.get(a).with.merge(b, w, Double::sum);
                }
            }
        }, userId, since);

        // Mail from real people (newsletters and robots aside).
        jdbc.query("SELECT m.account_id, LOWER(m.from_email), m.from_name, m.received_at FROM messages m JOIN accounts a ON a.id = m.account_id " +
                "WHERE a.user_id = ? AND m.folder <> 'SENT' AND m.folder <> 'SPAM' AND m.folder <> 'TRASH' AND m.from_email IS NOT NULL " +
                "AND m.list_unsubscribe IS NULL AND (m.lane IS NULL OR m.lane <> 'can_wait') AND m.received_at >= ? " +
                "ORDER BY m.received_at DESC LIMIT 6000", rs -> {
            String email = rs.getString(2);
            if (email == null || own.contains(email) || robot(email)) return;
            Instant at = rs.getObject(4, OffsetDateTime.class).toInstant();
            Person p = map.computeIfAbsent(email, Person::new);
            String n = rs.getString(3);
            if (p.name == null && n != null && !n.isBlank() && !n.contains("@")) p.name = n.trim();
            p.received++;
            p.score += weight(at, now);
            p.byAccount.merge(rs.getLong(1), 1, Integer::sum);
            if (p.last == null || at.isAfter(p.last)) p.last = at;
        }, userId, since);

        return new People(map, own);
    }
}
