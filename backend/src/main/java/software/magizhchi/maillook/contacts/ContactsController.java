package software.magizhchi.maillook.contacts;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.magizhchi.maillook.common.ApiException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Contacts: the Magizhchi Box address book (the master, shared with the phone and Magizhchi PA) joined
 * with the people in your mail. Suggests addresses while you type, who usually gets mail together,
 * and catches a mistyped or first-time address before it goes.
 */
@RestController
@RequestMapping("/api/contacts")
public class ContactsController {

    public record AddRequest(String name, String email, String phone, String org) {}

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@<>\",;]+@[^\\s@<>\",;]+\\.[^\\s@<>\",;]{2,}$");
    /** Big mail providers people often mistype. */
    private static final List<String> COMMON_DOMAINS = List.of("gmail.com", "yahoo.com", "yahoo.co.in", "outlook.com", "hotmail.com",
            "live.com", "icloud.com", "rediffmail.com", "protonmail.com", "zoho.com", "magizhchi.software");

    private final PeopleService people;
    private final BoxContacts box;

    public ContactsController(PeopleService people, BoxContacts box) {
        this.people = people;
        this.box = box;
    }

    /** One entry per email address, from Box and from mail. */
    private record Entry(String email, String name, PeopleService.Person person, Vcards.Card card) {}

    private Map<String, Entry> entries(long userId, BoxContacts.Book book) {
        PeopleService.People p = people.people(userId);
        Map<String, Entry> out = new HashMap<>();
        for (PeopleService.Person x : p.byEmail().values()) out.put(x.email, new Entry(x.email, x.name, x, null));
        for (Vcards.Card c : book.cards()) {
            for (String raw : c.emails) {
                String e = raw.trim().toLowerCase(Locale.ROOT);
                if (e.isEmpty() || p.own().contains(e)) continue;
                Entry had = out.get(e);
                out.put(e, new Entry(e, c.name != null ? c.name : had == null ? null : had.name(), had == null ? null : had.person(), c));
            }
        }
        return out;
    }

    private static String source(Entry e) {
        return e.card() != null && e.person() != null ? "both" : e.card() != null ? "box" : "mail";
    }

    /** "You wrote 12 times · last 3 days ago" */
    static String hint(PeopleService.Person p, Instant now) {
        if (p == null) return "In your Magizhchi Box contacts";
        List<String> parts = new ArrayList<>();
        if (p.sent > 0) parts.add(p.sent == 1 ? "You wrote once" : "You wrote " + p.sent + " times");
        else if (p.received > 0) parts.add(p.received == 1 ? "Wrote to you once" : "Wrote to you " + p.received + " times");
        if (p.last != null) parts.add("last " + ago(p.last, now));
        return String.join(" · ", parts);
    }

    static String ago(Instant t, Instant now) {
        long d = Duration.between(t, now).toDays();
        if (d < 1) return "today";
        if (d < 2) return "yesterday";
        if (d < 14) return d + " days ago";
        if (d < 60) return (d / 7) + " weeks ago";
        if (d < 730) return (d / 30) + " months ago";
        return (d / 365) + " years ago";
    }

    private Map<String, Object> json(Entry e, Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("email", e.email());
        m.put("name", e.name());
        m.put("source", source(e));
        m.put("hint", hint(e.person(), now));
        m.put("phone", e.card() == null || e.card().tels.isEmpty() ? null : e.card().tels.get(0));
        m.put("org", e.card() == null ? null : e.card().org);
        m.put("photo", e.card() == null ? null : photoUrl(e.card()));
        return m;
    }

    /** The contact's photo as a data URL for an <img>, or null when there is none (or it is oversized). */
    static String photoUrl(Vcards.Card card) {
        if (card == null || card.photo == null || card.photo.isBlank() || card.photo.length() > 130_000) return null;
        return "data:image/jpeg;base64," + card.photo.replaceAll("\s", "");
    }

    /** A card's photo for the address-book list: only a small one, so 200 rows stay light. */
    static String listPhoto(Vcards.Card card) {
        return card == null || card.photo == null || card.photo.length() > 40_000 ? null : photoUrl(card);
    }

    /** Photos for addresses already on the mail (the chips): email to data URL, only for people who have one. */
    @GetMapping("/photos")
    public Map<String, Object> photos(@RequestAttribute long userId, @RequestParam List<String> emails) {
        if (emails.size() > 40) throw ApiException.bad("Ask for up to 40 addresses at a time.");
        Set<String> wanted = new HashSet<>();
        for (String raw : emails) {
            String[] ne = PeopleService.split(raw);
            String e = ne[1] != null ? ne[1] : raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (!e.isEmpty()) wanted.add(e);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Vcards.Card c : box.book(userId).cards()) {
            String url = photoUrl(c);
            if (url == null) continue;
            for (String mail : c.emails) {
                String k = mail.trim().toLowerCase(Locale.ROOT);
                if (wanted.contains(k)) out.putIfAbsent(k, url);
            }
        }
        return Map.of("photos", out);
    }

    /** How well the typed text matches: 0 = not at all. */
    static double match(String q, String email, String name) {
        if (q.isEmpty()) return 1;
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (email.startsWith(q)) return 3;
        if (n.startsWith(q)) return 3;
        for (String w : n.split("[\\s.,'-]+")) if (!w.isEmpty() && w.startsWith(q)) return 2.5;
        String local = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
        for (String w : local.split("[._+-]+")) if (!w.isEmpty() && w.startsWith(q)) return 2;
        if (email.contains(q) || n.contains(q)) return 1;
        return 0;
    }

    /**
     * Address suggestions while typing in To, Cc or Bcc. With nothing typed and others already on the
     * mail, it suggests who usually gets mail together with them.
     */
    @GetMapping("/suggest")
    public Map<String, Object> suggest(@RequestAttribute long userId,
                                       @RequestParam(defaultValue = "") String q,
                                       @RequestParam(required = false) Long accountId,
                                       @RequestParam(name = "with", required = false) List<String> with,
                                       @RequestParam(defaultValue = "8") int limit) {
        String query = q.trim().toLowerCase(Locale.ROOT);
        Set<String> already = new HashSet<>();
        if (with != null) for (String w : with) {
            String[] ne = PeopleService.split(w);
            if (ne[1] != null) already.add(ne[1]);
        }
        BoxContacts.Book book = box.book(userId);
        Map<String, Entry> all = entries(userId, book);
        Instant now = Instant.now();
        int n = Math.max(1, Math.min(20, limit));

        record Scored(Entry e, double s, String why) {}
        List<Scored> scored = new ArrayList<>();
        for (Entry e : all.values()) {
            if (already.contains(e.email())) continue;
            double m = match(query, e.email(), e.name());
            if (m == 0) continue;
            PeopleService.Person p = e.person();
            double together = 0;
            String why = null;
            if (p != null) for (String w : already) {
                double t = p.with(w);
                if (t > together) {
                    together = t;
                    why = "Often on mail with " + w;
                }
            }
            // Nothing typed: only people who go with the ones already on the mail.
            if (query.isEmpty() && together == 0) continue;
            double s = m * 2 + (p == null ? 0.6 : Math.log1p(p.score) * 2) + together * 3
                    + (p != null && p.fromAccount(accountId) > 0 ? 1 : 0) + (e.card() != null ? 0.5 : 0);
            scored.add(new Scored(e, s, why));
        }
        scored.sort(Comparator.comparingDouble(Scored::s).reversed());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Scored s : scored.subList(0, Math.min(n, scored.size()))) {
            Map<String, Object> m = json(s.e(), now);
            if (s.why() != null) m.put("why", s.why());
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("box", boxState(userId, book));
        return out;
    }

    /** Before sending: addresses that look mistyped, and people you've never written to before. */
    @GetMapping("/check")
    public Map<String, Object> check(@RequestAttribute long userId, @RequestParam List<String> emails) {
        if (emails.size() > 100) throw ApiException.bad("Check up to 100 addresses at a time.");
        Map<String, Entry> all = entries(userId, box.book(userId));
        Set<String> own = people.people(userId).own();
        List<Map<String, Object>> items = new ArrayList<>();
        for (String raw : emails) {
            String[] ne = PeopleService.split(raw);
            String e = ne[1] != null ? ne[1] : raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (e.isEmpty()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("email", e);
            if (!EMAIL.matcher(e).matches()) {
                m.put("problem", "invalid");
                m.put("message", e + " isn't a complete email address.");
                items.add(m);
                continue;
            }
            Entry known = all.get(e);
            boolean wrote = known != null && (known.card() != null || (known.person() != null && known.person().sent > 0));
            if (wrote || own.contains(e)) continue;
            String better = didYouMean(e, all);
            if (better != null) {
                m.put("problem", "typo");
                m.put("didYouMean", better);
                Entry b = all.get(better);
                m.put("message", "Did you mean " + better + (b != null && b.name() != null ? " (" + b.name() + ")" : "") + "?");
            } else if (known != null && known.person() != null) {
                m.put("problem", "first_reply");
                m.put("message", "First time you write to " + e + ". They wrote to you " + ago(known.person().last, Instant.now()) + ".");
            } else {
                m.put("problem", "first_time");
                m.put("message", "You've never written to " + e + " before.");
            }
            items.add(m);
        }
        return Map.of("items", items);
    }

    /** A known address one or two letters away, or the same name at a mistyped big provider. */
    static String didYouMean(String e, Map<String, Entry> all) {
        String best = null;
        int bestD = 3;
        double bestScore = -1;
        for (Entry k : all.values()) {
            if (k.person() == null && k.card() == null) continue;
            if (Math.abs(k.email().length() - e.length()) > 2) continue;
            int d = distance(e, k.email(), 2);
            double score = k.person() == null ? 0.5 : k.person().score;
            if (d > 0 && d <= 2 && (d < bestD || (d == bestD && score > bestScore))) {
                best = k.email();
                bestD = d;
                bestScore = score;
            }
        }
        if (best != null) return best;
        int at = e.indexOf('@');
        String domain = e.substring(at + 1);
        for (String c : COMMON_DOMAINS) {
            int d = distance(domain, c, 2);
            if (d > 0 && d <= (c.length() > 8 ? 2 : 1)) return e.substring(0, at + 1) + c;
        }
        return null;
    }

    /** Levenshtein distance, giving up (max + 1) once it is over max. Adjacent swaps count once. */
    static int distance(String a, String b, int max) {
        if (Math.abs(a.length() - b.length()) > max) return max + 1;
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int rowMin = Integer.MAX_VALUE;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    v = Math.min(v, d[i - 2][j - 2] + 1);
                }
                d[i][j] = v;
                rowMin = Math.min(rowMin, v);
            }
            if (rowMin > max) return max + 1;
        }
        return d[a.length()][b.length()];
    }

    /** Everything known about one person: for the card on a mail you're reading. */
    @GetMapping("/card")
    public Map<String, Object> card(@RequestAttribute long userId, @RequestParam String email) {
        String e = email.trim().toLowerCase(Locale.ROOT);
        BoxContacts.Book book = box.book(userId);
        PeopleService.People p = people.people(userId);
        PeopleService.Person person = p.byEmail().get(e);
        Vcards.Card card = book.cards().stream().filter(c -> c.emails.stream().anyMatch(x -> x.equalsIgnoreCase(e))).findFirst().orElse(null);
        Instant now = Instant.now();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("email", e);
        m.put("name", card != null && card.name != null ? card.name : person == null ? null : person.name);
        m.put("inBox", card != null);
        m.put("sent", person == null ? 0 : person.sent);
        m.put("received", person == null ? 0 : person.received);
        m.put("last", person == null || person.last == null ? null : person.last.toString());
        m.put("hint", person == null ? null : hint(person, now));
        m.put("phones", card == null ? List.of() : card.tels);
        m.put("emails", card == null ? List.of(e) : card.emails);
        m.put("org", card == null ? null : card.org);
        m.put("birthday", card == null ? null : card.bday);
        m.put("address", card == null ? null : card.address);
        m.put("note", card == null ? null : card.note);
        m.put("photo", photoUrl(card));
        // Who they usually appear with, for "Often with".
        List<String> together = person == null ? List.of() : person.together().entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(3).map(Map.Entry::getKey).toList();
        m.put("oftenWith", together);
        m.put("box", boxState(userId, book));
        return m;
    }

    /** Everything on a Box card, so the page can open it without asking again. */
    private static void details(Map<String, Object> m, Vcards.Card c) {
        m.put("phones", c.tels);
        m.put("birthday", c.bday);
        m.put("address", c.address);
        m.put("note", c.note);
    }

    /** Changes when the Box book changes: the Contacts page asks every few seconds and reloads on a change. */
    @GetMapping("/stamp")
    public Map<String, Object> stamp(@RequestAttribute long userId) {
        BoxContacts.Book book = box.book(userId);
        return Map.of("stamp", book.stamp(), "contacts", book.cards().size());
    }

    /** The address book: Box contacts and the people you write to most, a page at a time. */
    @GetMapping
    public Map<String, Object> list(@RequestAttribute long userId, @RequestParam(defaultValue = "") String q,
                                    @RequestParam(defaultValue = "200") int limit,
                                    @RequestParam(defaultValue = "0") int offset) {
        String query = q.trim().toLowerCase(Locale.ROOT);
        BoxContacts.Book book = box.book(userId);
        Map<String, Entry> all = entries(userId, book);
        Instant now = Instant.now();
        List<Map<String, Object>> items = new ArrayList<>();
        Set<Vcards.Card> shown = new HashSet<>();
        // Box cards first (one row per card, even with several emails), then people from mail.
        for (Vcards.Card c : book.cards()) {
            String first = c.emails.isEmpty() ? "" : c.emails.get(0).toLowerCase(Locale.ROOT);
            if (!query.isEmpty() && match(query, first, c.name) == 0
                    && c.tels.stream().noneMatch(t -> Vcards.normTel(t).contains(query.replaceAll("\\D", "")) && !query.replaceAll("\\D", "").isEmpty())) continue;
            shown.add(c);
            Entry e = first.isEmpty() ? null : all.get(first);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("email", first.isEmpty() ? null : first);
            m.put("emails", c.emails);
            m.put("name", c.name);
            m.put("phone", c.tels.isEmpty() ? null : c.tels.get(0));
            m.put("org", c.org);
            m.put("photo", listPhoto(c));
            details(m, c);
            m.put("source", e != null && e.person() != null ? "both" : "box");
            m.put("hint", e == null || e.person() == null ? "In your Magizhchi Box contacts" : hint(e.person(), now));
            m.put("score", e == null || e.person() == null ? 0 : e.person().score);
            items.add(m);
        }
        List<Entry> mailOnly = all.values().stream()
                .filter(e -> e.card() == null && e.person() != null && e.person().sent > 0)
                .filter(e -> match(query, e.email(), e.name()) > 0)
                .sorted(Comparator.comparingDouble((Entry e) -> e.person().score).reversed())
                .limit(200).toList();
        for (Entry e : mailOnly) {
            Map<String, Object> m = json(e, now);
            m.put("score", e.person().score);
            items.add(m);
        }
        int n = Math.max(1, Math.min(1000, limit));
        int from = Math.max(0, Math.min(offset, items.size()));
        int to = Math.min(items.size(), from + n);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items.subList(from, to));
        out.put("total", items.size());
        out.put("nextOffset", to < items.size() ? to : null);
        out.put("stamp", book.stamp());
        out.put("box", boxState(userId, book));
        return out;
    }

    @PostMapping
    public Map<String, Object> add(@RequestAttribute long userId, @RequestBody AddRequest req) {
        String[] ne = PeopleService.split(req.email());
        String e = ne[1];
        if (e == null || !EMAIL.matcher(e).matches()) throw ApiException.bad("That doesn't look like an email address.");
        if (!box.available()) throw ApiException.bad("Magizhchi Box isn't set up on this server, so contacts can't be saved yet.");
        String name = req.name() == null || req.name().isBlank() ? ne[0] : req.name().trim();
        if (name != null && name.length() > 200) throw ApiException.bad("Names can be up to 200 characters.");
        if (req.phone() != null && req.phone().length() > 40) throw ApiException.bad("That phone number is too long.");
        boolean updated = box.add(userId, name, e, req.phone(), req.org());
        Map<String, Object> out = card(userId, e);
        out.put("message", updated ? "Added to " + (name == null ? e : name) + "'s card in Magizhchi Box." : "Saved to your Magizhchi Box contacts.");
        return out;
    }

    /** Connects to Magizhchi Box so its contact book shows here (the same book as the Box app). */
    @PostMapping("/connect")
    public Map<String, Object> connect(@RequestAttribute long userId) {
        BoxContacts.Book b = box.connect(userId);
        return Map.of("box", boxState(userId, b));
    }

    private Map<String, Object> boxState(long userId, BoxContacts.Book b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", box.available());
        m.put("connected", b.connected());
        m.put("contacts", b.cards().size());
        m.put("error", b.error());
        return m;
    }
}
