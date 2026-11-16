package software.magizhchi.maillook.mail;

import jakarta.mail.Message;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.BodyTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.RecipientStringTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SubjectTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.magizhchi.maillook.account.Account;
import software.magizhchi.maillook.account.AccountRepo;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Search reaches the whole mailbox, not just the mail MailLook keeps (sync keeps the newest few hundred).
 * After the list shows what's kept, the web app asks here; this asks the mail server itself (Gmail, Outlook,
 * Hostinger…) for matching mail, brings the newest matches in, and says how many, so the list can refresh.
 */
@RestController
@RequestMapping("/api")
public class ServerSearch {

    private static final Logger log = LoggerFactory.getLogger(ServerSearch.class);

    /** Where a search of All mail looks: everything kept except Trash and Spam. */
    public static final List<String> ALL_MAIL = List.of("INBOX", "SENT", "ARCHIVE");

    /** Newest matches brought in per folder and search; enough for a screen of results without flooding the inbox. */
    static final int PER_FOLDER = 40;
    /** The same search again within this long doesn't ask the server again. */
    private static final Duration FRESH = Duration.ofMinutes(10);
    /** How long one account may take before its search counts as failed. */
    private static final Duration PATIENCE = Duration.ofSeconds(90);
    /** Searches run here, a few mailboxes at a time across everyone. */
    private final ExecutorService pool = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "server-search");
        t.setDaemon(true);
        return t;
    });

    private final AccountRepo accounts;
    private final SyncService sync;
    private final Map<String, Instant> recent = new ConcurrentHashMap<>();

    public ServerSearch(AccountRepo accounts, SyncService sync) {
        this.accounts = accounts;
        this.sync = sync;
    }

    @PostMapping("/messages/server-search")
    public Map<String, Object> search(@RequestAttribute long userId,
                                      @RequestParam(required = false) String q,
                                      @RequestParam(required = false) String from,
                                      @RequestParam(required = false) Long accountId,
                                      @RequestParam(required = false) String folder,
                                      @RequestParam(required = false) String lane,
                                      @RequestParam(required = false) String tz) {
        Map<String, Object> out = new LinkedHashMap<>();
        MailSearch s = MailSearch.parse(q, MessageController.zone(tz), Instant.now()).with(from, null, null, null, null, null);
        Set<String> codes = folders(folder, lane);
        Query term = query(s);
        if (term == null || codes.isEmpty()) {
            out.put("added", 0);
            out.put("searched", false);
            return out;
        }
        String key = userId + "|" + accountId + "|" + codes + "|" + (q == null ? "" : q.trim().toLowerCase(Locale.ROOT))
                + "|" + (from == null ? "" : from.trim().toLowerCase(Locale.ROOT));
        sweep();
        Instant last = recent.get(key);
        if (last != null && last.plus(FRESH).isAfter(Instant.now())) {
            out.put("added", 0);
            out.put("searched", true);
            return out;
        }
        // Every account at once: one slow mailbox shouldn't hold the others up.
        List<Account> mine = new ArrayList<>();
        for (Account a : accounts.forUser(userId)) if (accountId == null || a.id() == accountId) mine.add(a);
        List<Future<Integer>> runs = new ArrayList<>();
        for (Account a : mine) runs.add(pool.submit(() -> sync.importMatching(a, term, codes, PER_FOLDER)));
        int added = 0;
        List<String> failed = new ArrayList<>();
        for (int i = 0; i < mine.size(); i++) {
            try {
                added += runs.get(i).get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
            } catch (Exception e) {
                runs.get(i).cancel(true);
                log.info("Server search for {} failed: {}", mine.get(i).email(), e.toString());
                failed.add(mine.get(i).email());
            }
        }
        if (failed.isEmpty()) recent.put(key, Instant.now());
        out.put("added", added);
        out.put("searched", true);
        if (!failed.isEmpty()) out.put("failed", failed);
        return out;
    }

    /** Which server folders a view's search should look in. Lanes are Inbox mail; Trash and Spam aren't searched there. */
    static Set<String> folders(String folder, String lane) {
        String f = folder == null ? "INBOX" : folder.toUpperCase(Locale.ROOT);
        boolean allLanes = lane == null || lane.isBlank() || "all".equals(lane);
        return switch (f) {
            case "INBOX" -> allLanes ? Set.copyOf(ALL_MAIL) : Set.of("INBOX");
            case "SENT" -> Set.of("SENT");
            case "ARCHIVE" -> Set.of("ARCHIVE");
            default -> Set.of();
        };
    }

    /**
     * A search as mail servers take it. Every group must match (each word, and the sender); a group matches when
     * any of its terms does (the word in the sender, subject, body, To or Cc, like Gmail). Each term goes to the
     * server as a simple search of its own and the results are combined here: some servers handle nested
     * OR searches badly, simple ones everywhere.
     */
    public record Query(List<List<SearchTerm>> groups, List<SearchTerm> limits) {
        /** The term with the date limits added, as one simple server search. */
        SearchTerm limited(SearchTerm t) {
            if (limits.isEmpty()) return t;
            List<SearchTerm> all = new ArrayList<>(limits);
            all.add(0, t);
            return new AndTerm(all.toArray(new SearchTerm[0]));
        }
    }

    /** The search box's words and sender as a server search; null when there's nothing to look for. */
    static Query query(MailSearch s) {
        if (s == null || !s.hasWords()) return null;
        List<List<SearchTerm>> groups = new ArrayList<>();
        if (s.text() != null) {
            int n = 0;
            for (String w : s.text().replace("\"", " ").trim().split("\\s+")) {
                if (w.isBlank() || ++n > 6) continue;
                groups.add(List.of(new FromStringTerm(w), new SubjectTerm(w), new BodyTerm(w),
                        new RecipientStringTerm(Message.RecipientType.TO, w), new RecipientStringTerm(Message.RecipientType.CC, w)));
            }
        }
        if (s.from() != null && !s.from().isBlank()) groups.add(List.of(new FromStringTerm(s.from().trim())));
        if (groups.isEmpty()) return null;
        List<SearchTerm> limits = new ArrayList<>();
        if (s.after() != null) limits.add(new ReceivedDateTerm(ComparisonTerm.GE, Date.from(s.after())));
        if (s.before() != null) limits.add(new ReceivedDateTerm(ComparisonTerm.LT, Date.from(s.before())));
        return new Query(groups, limits);
    }

    private void sweep() {
        Instant cutoff = Instant.now().minus(FRESH);
        recent.values().removeIf(t -> t.isBefore(cutoff));
    }
}
