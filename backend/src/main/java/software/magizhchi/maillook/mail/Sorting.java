package software.magizhchi.maillook.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import software.magizhchi.maillook.common.ApiException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The person's own sections (lanes they name, with an icon) and the rules that file mail into them:
 * "mail from no-reply@magizhchi.software goes to Meet notices", "anything with 'invoice' in the subject goes to
 * Bills and is marked read". Rules run on each new inbox mail before the AI sorts it, and can be run over the mail
 * already there. A section's mail carries lane "s" + its id, so every lane query works for it unchanged.
 */
@Service
public class Sorting {

    private static final Logger log = LoggerFactory.getLogger(Sorting.class);

    /** The icons a section can wear (shown in the sidebar and the menus). */
    public static final List<String> ICONS = List.of("📁", "⭐", "💼", "🎓", "🏫", "📚", "💰", "🧾", "🏦", "🛒", "📦", "✈️",
            "🎥", "📅", "🔔", "🤖", "👪", "❤️", "🏠", "⚙️", "📰", "🎯", "🛠️", "🩺");
    public static final List<String> COLORS = List.of("teal", "marigold", "dusk", "rose", "leaf", "sky", "plum", "slate");
    private static final Pattern SECTION = Pattern.compile("^s\\d{1,12}$");
    static final Set<String> BUILT_IN = Set.of(LaneClassifier.NEEDS_ME, LaneClassifier.FYI, LaneClassifier.CAN_WAIT);
    private static final int MAX_SECTIONS = 30;
    private static final int MAX_RULES = 200;

    public record Section(long id, String lane, String name, String icon, String color, int position) {}

    /** {@code forwardTo}: addresses each new mail it catches is forwarded to (empty for none). */
    public record Rule(long id, String from, String subject, String to, String words, String lane, boolean markRead,
                       boolean archive, boolean enabled, String madeBy, int matched, String lastMatched, List<String> forwardTo) {
        public Rule(long id, String from, String subject, String to, String words, String lane, boolean markRead,
                    boolean archive, boolean enabled, String madeBy, int matched, String lastMatched) {
            this(id, from, subject, to, words, lane, markRead, archive, enabled, madeBy, matched, lastMatched, List.of());
        }
    }

    /** What a rule is made of, from the rules page or from PA. {@code forwardTo} null = leave as it is, empty = none. */
    public record RuleIn(String from, String subject, String to, String words, String lane, Boolean markRead,
                         Boolean archive, Boolean enabled, List<String> forwardTo) {
        public RuleIn(String from, String subject, String to, String words, String lane, Boolean markRead,
                      Boolean archive, Boolean enabled) {
            this(from, subject, to, words, lane, markRead, archive, enabled, null);
        }
    }

    static final int MAX_FORWARD = 10;
    private static final Pattern ADDRESS = Pattern.compile("^[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$");

    private final JdbcTemplate jdbc;
    private final MessageRepo messages;
    private final MailActions actions;
    private final RuleForwarder forwarder;

    public Sorting(JdbcTemplate jdbc, MessageRepo messages, @Lazy MailActions actions, @Lazy RuleForwarder forwarder) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.actions = actions;
        this.forwarder = forwarder;
    }

    public static boolean isSectionLane(String lane) {
        return lane != null && SECTION.matcher(lane).matches();
    }

    /** A built-in lane, or one of this person's own sections. */
    public boolean isTarget(long userId, String lane) {
        if (lane == null) return false;
        if (BUILT_IN.contains(lane)) return true;
        if (!isSectionLane(lane)) return false;
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sections WHERE id = ? AND user_id = ?", Integer.class,
                Long.parseLong(lane.substring(1)), userId);
        return n != null && n > 0;
    }

    // ── sections ──────────────────────────────────────────────────

    public List<Section> sections(long userId) {
        return jdbc.query("SELECT * FROM sections WHERE user_id = ? ORDER BY position, id", (rs, i) -> section(rs), userId);
    }

    public Section createSection(long userId, String name, String icon, String color) {
        String n = cleanName(name);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sections WHERE user_id = ?", Integer.class, userId);
        if (count != null && count >= MAX_SECTIONS) throw ApiException.bad("You can have up to " + MAX_SECTIONS + " sections.");
        Section same = byName(userId, n);
        if (same != null) return same;
        Integer pos = jdbc.queryForObject("SELECT COALESCE(MAX(position), 0) + 1 FROM sections WHERE user_id = ?", Integer.class, userId);
        long id = insert("INSERT INTO sections (user_id, name, icon, color, position) VALUES (?,?,?,?,?)",
                userId, n, icon(icon), color(color, count == null ? 0 : count), pos == null ? 1 : pos);
        return section(userId, id);
    }

    public Section updateSection(long userId, long id, String name, String icon, String color) {
        Section s = section(userId, id);
        jdbc.update("UPDATE sections SET name = ?, icon = ?, color = ? WHERE id = ? AND user_id = ?",
                name == null ? s.name() : cleanName(name), icon == null ? s.icon() : icon(icon),
                color == null ? s.color() : color(color, 0), id, userId);
        return section(userId, id);
    }

    /** Deleting a section hands its mail back to the usual sorting, and drops the rules that filled it. */
    public void deleteSection(long userId, long id) {
        Section s = section(userId, id);
        jdbc.update("UPDATE messages SET lane = 'fyi', lane_source = 'heuristic' WHERE lane = ? " +
                "AND account_id IN (SELECT id FROM accounts WHERE user_id = ?)", s.lane(), userId);
        jdbc.update("DELETE FROM mail_rules WHERE user_id = ? AND lane = ?", userId, s.lane());
        jdbc.update("DELETE FROM sender_rules WHERE user_id = ? AND lane = ?", userId, s.lane());
        jdbc.update("DELETE FROM sections WHERE id = ? AND user_id = ?", id, userId);
    }

    public Section byName(long userId, String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase(Locale.ROOT);
        for (Section s : sections(userId)) if (s.name().toLowerCase(Locale.ROOT).equals(n)) return s;
        return null;
    }

    public Section section(long userId, long id) {
        List<Section> s = jdbc.query("SELECT * FROM sections WHERE id = ? AND user_id = ?", (rs, i) -> section(rs), id, userId);
        if (s.isEmpty()) throw ApiException.notFound("Section");
        return s.get(0);
    }

    private static Section section(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        return new Section(id, "s" + id, rs.getString("name"), rs.getString("icon"), rs.getString("color"), rs.getInt("position"));
    }

    private static String cleanName(String name) {
        String n = name == null ? "" : name.replaceAll("\\s+", " ").trim();
        if (n.isEmpty()) throw ApiException.bad("Give the section a name.");
        return n.length() > 40 ? n.substring(0, 40).trim() : n;
    }

    private static String icon(String icon) {
        return icon != null && ICONS.contains(icon.trim()) ? icon.trim() : "📁";
    }

    private static String color(String color, int index) {
        return color != null && COLORS.contains(color) ? color : COLORS.get(index % COLORS.size());
    }

    // ── rules ─────────────────────────────────────────────────────

    public List<Rule> rules(long userId) {
        return jdbc.query("SELECT * FROM mail_rules WHERE user_id = ? ORDER BY id", (rs, i) -> rule(rs), userId);
    }

    public Rule rule(long userId, long id) {
        List<Rule> r = jdbc.query("SELECT * FROM mail_rules WHERE id = ? AND user_id = ?", (rs, i) -> rule(rs), id, userId);
        if (r.isEmpty()) throw ApiException.notFound("Rule");
        return r.get(0);
    }

    public Rule createRule(long userId, RuleIn in, String madeBy) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM mail_rules WHERE user_id = ?", Integer.class, userId);
        if (count != null && count >= MAX_RULES) throw ApiException.bad("You can have up to " + MAX_RULES + " rules.");
        Checked c = check(userId, in);
        // The same conditions again replace the old rule's action instead of making a second one.
        List<Long> same = jdbc.queryForList("SELECT id FROM mail_rules WHERE user_id = ? AND COALESCE(from_match,'') = ? " +
                        "AND COALESCE(subject_match,'') = ? AND COALESCE(to_match,'') = ? AND COALESCE(words_match,'') = ?", Long.class,
                userId, nz(c.from), nz(c.subject), nz(c.to), nz(c.words));
        if (!same.isEmpty()) {
            Rule old = rule(userId, same.get(0));
            String fwd = in.forwardTo() == null ? joined(old.forwardTo()) : c.forward;
            jdbc.update("UPDATE mail_rules SET lane = ?, mark_read = ?, archive = ?, forward_to = ?, enabled = TRUE WHERE id = ?",
                    c.lane, c.markRead, c.archive, fwd, same.get(0));
            return rule(userId, same.get(0));
        }
        long id = insert("INSERT INTO mail_rules (user_id, from_match, subject_match, to_match, words_match, lane, " +
                        "mark_read, archive, forward_to, enabled, made_by) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                userId, c.from, c.subject, c.to, c.words, c.lane, c.markRead, c.archive, c.forward,
                in.enabled() == null || in.enabled(), "pa".equals(madeBy) ? "pa" : "user");
        return rule(userId, id);
    }

    public Rule updateRule(long userId, long id, RuleIn in) {
        Rule old = rule(userId, id);
        RuleIn merged = new RuleIn(in.from() != null ? in.from() : old.from(), in.subject() != null ? in.subject() : old.subject(),
                in.to() != null ? in.to() : old.to(), in.words() != null ? in.words() : old.words(),
                in.lane() != null ? in.lane() : old.lane(), in.markRead() != null ? in.markRead() : old.markRead(),
                in.archive() != null ? in.archive() : old.archive(), in.enabled() != null ? in.enabled() : old.enabled(),
                in.forwardTo() != null ? in.forwardTo() : old.forwardTo());
        Checked c = check(userId, merged);
        jdbc.update("UPDATE mail_rules SET from_match = ?, subject_match = ?, to_match = ?, words_match = ?, lane = ?, mark_read = ?, " +
                        "archive = ?, forward_to = ?, enabled = ? WHERE id = ? AND user_id = ?", c.from, c.subject, c.to, c.words, c.lane,
                c.markRead, c.archive, c.forward, merged.enabled() == null || merged.enabled(), id, userId);
        return rule(userId, id);
    }

    public void deleteRule(long userId, long id) {
        rule(userId, id);
        jdbc.update("DELETE FROM mail_rules WHERE id = ? AND user_id = ?", id, userId);
    }

    private record Checked(String from, String subject, String to, String words, String lane, boolean markRead, boolean archive,
                           String forward) {}

    private Checked check(long userId, RuleIn in) {
        String from = cond(in.from(), 400);
        String subject = cond(in.subject(), 200);
        String to = cond(in.to(), 400);
        String words = cond(in.words(), 200);
        if (from == null && subject == null && to == null && words == null) {
            throw ApiException.bad("Say which mail the rule is for: who it's from, words in the subject, or who it was sent to.");
        }
        String lane = in.lane() == null || in.lane().isBlank() ? null : in.lane().trim();
        boolean archive = Boolean.TRUE.equals(in.archive());
        boolean read = Boolean.TRUE.equals(in.markRead());
        List<String> forward = forwardList(userId, in.forwardTo());
        if (lane != null && !isTarget(userId, lane)) throw ApiException.bad("That section doesn't exist.");
        if (lane == null && !archive && !read && forward.isEmpty()) {
            throw ApiException.bad("Say what to do with the mail: a section, mark as read, archive, or forward it.");
        }
        return new Checked(from, subject, to, words, lane, read, archive, forward.isEmpty() ? null : String.join(", ", forward));
    }

    /** Clean, distinct addresses (up to 10); never one of the person's own mailboxes, so mail can't go round in circles. */
    private List<String> forwardList(long userId, List<String> in) {
        List<String> out = new ArrayList<>();
        if (in == null) return out;
        java.util.Set<String> own = new java.util.HashSet<>(jdbc.queryForList(
                "SELECT LOWER(email) FROM accounts WHERE user_id = ?", String.class, userId));
        for (String raw : in) {
            if (raw == null) continue;
            for (String part : raw.split("[,;\\s]+")) {
                String e = part.trim().replaceAll("^<|>$", "").toLowerCase(Locale.ROOT);
                if (e.isEmpty()) continue;
                if (!ADDRESS.matcher(e).matches() || e.length() > 200) throw ApiException.bad("\"" + part.trim() + "\" isn't an email address.");
                if (own.contains(e)) throw ApiException.bad(e + " is one of your own mailboxes, so forwarding there would loop.");
                if (!out.contains(e)) out.add(e);
            }
        }
        if (out.size() > MAX_FORWARD) throw ApiException.bad("A rule can forward to up to " + MAX_FORWARD + " people.");
        return out;
    }

    static List<String> split(String joined) {
        if (joined == null || joined.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : joined.split(",")) if (!p.isBlank()) out.add(p.trim());
        return out;
    }

    private static String joined(List<String> list) {
        return list == null || list.isEmpty() ? null : String.join(", ", list);
    }

    private static String cond(String s, int max) {
        if (s == null) return null;
        String t = s.replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static Rule rule(ResultSet rs) throws SQLException {
        java.sql.Timestamp last = rs.getTimestamp("last_matched");
        return new Rule(rs.getLong("id"), rs.getString("from_match"), rs.getString("subject_match"), rs.getString("to_match"),
                rs.getString("words_match"), rs.getString("lane"), rs.getBoolean("mark_read"), rs.getBoolean("archive"),
                rs.getBoolean("enabled"), rs.getString("made_by"), rs.getInt("matched"),
                last == null ? null : last.toInstant().toString(), split(rs.getString("forward_to")));
    }

    // ── matching ──────────────────────────────────────────────────

    /** The facts a rule looks at. */
    record Mail(long id, String fromEmail, String fromName, String to, String subject, String text, String laneSource) {}

    /** Every condition the rule has must hold. Each condition may list several choices, split by commas ("any of"). */
    static boolean matches(Rule r, Mail m) {
        return matchFrom(r.from(), m) && any(r.subject(), m.subject()) && any(r.to(), m.to())
                && any(r.words(), nz(m.subject()) + " " + nz(m.text()));
    }

    private static boolean matchFrom(String cond, Mail m) {
        if (cond == null) return true;
        String email = nz(m.fromEmail()).toLowerCase(Locale.ROOT);
        String name = nz(m.fromName()).toLowerCase(Locale.ROOT);
        for (String part : terms(cond)) {
            if (part.contains("@") && !part.startsWith("@")) {
                if (email.equals(part)) return true;
            } else {
                String dom = part.startsWith("@") ? part.substring(1) : part;
                // "@magizhchi.software" or "magizhchi.software": that domain and its subdomains; plain words: in the address or name
                if (dom.contains(".") && (email.endsWith("@" + dom) || email.endsWith("." + dom))) return true;
                if (!dom.contains(".") && (email.contains(dom) || name.contains(dom))) return true;
            }
        }
        return false;
    }

    private static boolean any(String cond, String value) {
        if (cond == null) return true;
        String v = nz(value).toLowerCase(Locale.ROOT);
        for (String t : terms(cond)) if (v.contains(t)) return true;
        return false;
    }

    private static List<String> terms(String cond) {
        List<String> out = new ArrayList<>();
        for (String p : cond.toLowerCase(Locale.ROOT).split("[,;]| or ")) {
            String t = p.trim().replaceAll("^\"|\"$", "");
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private List<Mail> mails(String where, Object... args) {
        return jdbc.query("SELECT m.id, m.from_email, m.from_name, m.to_list, m.cc_list, m.subject, m.snippet, m.lane_source " +
                        "FROM messages m JOIN accounts a ON a.id = m.account_id WHERE " + where,
                (rs, i) -> new Mail(rs.getLong(1), rs.getString(2), rs.getString(3), nz(rs.getString(4)) + " " + nz(rs.getString(5)),
                        rs.getString(6), rs.getString(7), rs.getString(8)), args);
    }

    /** Mail found by a search of the server (old mail): filed, never forwarded. */
    public void applyNew(long userId, List<Long> ids) {
        applyNew(userId, ids, false);
    }

    /**
     * New inbox mail: the first enabled rule that fits decides (before the AI sorts it). Never fails the sync.
     * {@code live}: mail that has just arrived (not the first fill of a mailbox), so a rule's forward goes too.
     */
    public void applyNew(long userId, List<Long> ids, boolean live) {
        if (ids == null || ids.isEmpty()) return;
        try {
            List<Rule> rules = rules(userId).stream().filter(Rule::enabled).toList();
            if (rules.isEmpty()) return;
            for (int i = 0; i < ids.size(); i += 200) {
                List<Long> part = ids.subList(i, Math.min(ids.size(), i + 200));
                String in = String.join(",", part.stream().map(String::valueOf).toList());
                for (Mail m : mails("a.user_id = ? AND m.folder = 'INBOX' AND m.id IN (" + in + ")", userId)) {
                    for (Rule r : rules) {
                        if (matches(r, m)) {
                            if (live && !r.forwardTo().isEmpty()) forwarder.forward(userId, r, m.id(), m.fromEmail());
                            act(userId, r, m);
                            jdbc.update("UPDATE mail_rules SET matched = matched + 1, last_matched = CURRENT_TIMESTAMP WHERE id = ?", r.id());
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Rules failed for user {}: {}", userId, e.toString());
        }
    }

    /** Runs one rule over the inbox mail already there (newest 2000). Mail the person placed by hand is left alone. */
    public int applyExisting(long userId, long ruleId) {
        Rule r = rule(userId, ruleId);
        int n = 0;
        for (Mail m : mails("a.user_id = ? AND m.folder = 'INBOX' ORDER BY m.received_at DESC LIMIT 2000", userId)) {
            if (!matches(r, m) || "user".equals(m.laneSource())) continue;
            try {
                act(userId, r, m);
                n++;
            } catch (Exception e) {
                log.debug("Rule {} on mail {}: {}", ruleId, m.id(), e.toString());
            }
        }
        if (n > 0) jdbc.update("UPDATE mail_rules SET matched = matched + ?, last_matched = CURRENT_TIMESTAMP WHERE id = ?", n, ruleId);
        return n;
    }

    /** How many inbox mails a rule would catch now, and a few of them (for "this will move 12 mails"). */
    public Map<String, Object> preview(long userId, RuleIn in) {
        Checked c = check(userId, new RuleIn(in.from(), in.subject(), in.to(), in.words(),
                in.lane() == null && !Boolean.TRUE.equals(in.archive()) ? LaneClassifier.FYI : in.lane(), in.markRead(), in.archive(), true,
                in.forwardTo()));
        Rule r = new Rule(0, c.from, c.subject, c.to, c.words, c.lane, c.markRead, c.archive, true, "user", 0, null, split(c.forward));
        int n = 0;
        List<Map<String, Object>> sample = new ArrayList<>();
        for (Mail m : mails("a.user_id = ? AND m.folder = 'INBOX' ORDER BY m.received_at DESC LIMIT 2000", userId)) {
            if (!matches(r, m)) continue;
            n++;
            if (sample.size() < 5) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", m.id());
                s.put("from", m.fromName() != null && !m.fromName().isBlank() ? m.fromName() : m.fromEmail());
                s.put("subject", m.subject());
                sample.add(s);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", n);
        out.put("sample", sample);
        return out;
    }

    private void act(long userId, Rule r, Mail m) {
        if (r.lane() != null) messages.setLane(m.id(), r.lane(), "rule");
        if (r.markRead()) actions.setSeen(userId, m.id(), true);
        if (r.archive()) actions.archive(userId, m.id());
    }

    private long insert(String sql, Object... args) {
        org.springframework.jdbc.support.GeneratedKeyHolder kh = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, kh);
        return ((Number) kh.getKeys().get("id")).longValue();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
