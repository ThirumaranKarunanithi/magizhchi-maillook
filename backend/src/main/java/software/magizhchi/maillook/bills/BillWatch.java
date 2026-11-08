package software.magizhchi.maillook.bills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import software.magizhchi.maillook.notify.PushService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Regular bills: the ones that come every month (or quarter, or year) from the same biller for the
 * same account. From the bills already read, it learns when each usually arrives and what it usually
 * costs, then
 * <ul>
 *   <li>says when one hasn't come (five days past when it usually does), once per bill, by alert;</li>
 *   <li>marks a bill that is more than 20% above the usual amount.</li>
 * </ul>
 * Nothing is sent anywhere: it is arithmetic on the person's own bills.
 */
@Service
public class BillWatch {
    private static final Logger log = LoggerFactory.getLogger(BillWatch.class);
    static final int LATE_DAYS = 5;
    static final BigDecimal JUMP = new BigDecimal("0.20");
    static final BigDecimal JUMP_MIN_INR = new BigDecimal("100");

    /** How often a regular bill comes. */
    public enum Every {
        MONTH(25, 35), QUARTER(80, 100), YEAR(330, 400);
        final int min, max;
        Every(int min, int max) { this.min = min; this.max = max; }
    }

    /**
     * One regular bill.
     * @param key       biller (first word) and account, e.g. "act|1146684"
     * @param billIds   its bills, oldest first
     * @param nextBy    when the next one should have come by (usual arrival + the usual gap)
     * @param late      past {@code nextBy} by {@value #LATE_DAYS} days and still not here
     */
    public record Regular(String key, String vendor, String account, Every every, int usualDay, BigDecimal usualAmount,
                          String currency, LocalDate lastAt, LocalDate nextBy, boolean late, List<Long> billIds) {}

    /** A bill above its usual amount: how much more, as a share. */
    public record Jump(BigDecimal usual, int percent) {}

    private record Row(long id, String vendor, String first, String account, BigDecimal amount, String currency, LocalDate at) {}

    private final JdbcTemplate jdbc;
    private final PushService push;
    private final DataClient data;
    private final boolean timer;

    public BillWatch(JdbcTemplate jdbc, PushService push, DataClient data,
                     @org.springframework.beans.factory.annotation.Value("${maillook.bills.timer:true}") boolean timer) {
        this.jdbc = jdbc;
        this.push = push;
        this.data = data;
        this.timer = timer;
    }

    /** Every 30 minutes: alerts for regular bills that haven't come. */
    @org.springframework.scheduling.annotation.Scheduled(initialDelay = 120_000, fixedDelay = 1_800_000)
    public void onTimer() {
        if (!timer || !data.configured()) return;
        try {
            watch(Instant.now());
        } catch (Exception e) {
            log.warn("Watching regular bills failed: {}", e.toString());
        }
    }

    /** This person's regular bills, those due back soonest first. */
    public List<Regular> regulars(long userId, LocalDate today) {
        List<Regular> out = new ArrayList<>();
        for (Map.Entry<String, List<Row>> e : groups(userId).entrySet()) {
            Regular r = regular(e.getKey(), e.getValue(), today);
            if (r != null) out.add(r);
        }
        out.sort(Comparator.comparing(Regular::nextBy));
        return out;
    }

    /** Bills above the usual amount of their regular bill, by bill id. */
    public Map<Long, Jump> jumps(long userId) {
        Map<Long, Jump> out = new HashMap<>();
        for (List<Row> g : groups(userId).values()) {
            for (int i = 1; i < g.size(); i++) {
                List<BigDecimal> before = new ArrayList<>();
                for (int j = Math.max(0, i - 3); j < i; j++) {
                    if (Objects.equals(g.get(j).currency(), g.get(i).currency())) before.add(g.get(j).amount());
                }
                if (before.isEmpty()) continue;
                BigDecimal usual = median(before);
                BigDecimal now = g.get(i).amount();
                if (usual.signum() <= 0 || now.compareTo(usual.multiply(BigDecimal.ONE.add(JUMP))) <= 0) continue;
                if ("INR".equals(g.get(i).currency()) && now.subtract(usual).compareTo(JUMP_MIN_INR) < 0) continue;
                int pct = now.subtract(usual).multiply(BigDecimal.valueOf(100)).divide(usual, 0, RoundingMode.HALF_UP).intValue();
                out.put(g.get(i).id(), new Jump(usual, pct));
            }
        }
        return out;
    }

    /**
     * Alerts for regular bills that haven't come, in the day-time where the person is, once per
     * missing bill. @return how many alerts were sent
     */
    public int watch(Instant now) {
        int sent = 0;
        for (Map<String, Object> u : jdbc.queryForList("SELECT id, tz FROM users WHERE bills_on = TRUE")) {
            long userId = ((Number) u.get("id")).longValue();
            ZonedDateTime local = now.atZone(zone(u.get("tz")));
            if (local.getHour() < 9 || local.getHour() >= 20) continue;
            for (Regular r : regulars(userId, local.toLocalDate())) {
                if (!r.late()) continue;
                String period = r.nextBy().toString();
                if (jdbc.update("INSERT INTO bill_watch_sent (user_id, watch_key, period) SELECT ?, ?, ? WHERE NOT EXISTS " +
                        "(SELECT 1 FROM bill_watch_sent WHERE user_id = ? AND watch_key = ? AND period = ?)",
                        userId, r.key(), period, userId, r.key(), period) == 0) continue;
                String who = r.vendor() + (r.account() == null ? "" : " · " + r.account());
                String usually = r.every() == Every.MONTH ? "It usually comes around the " + ordinal(r.usualDay()) + "."
                        : "It usually comes by " + r.nextBy().format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)) + ".";
                try {
                    push.reminder(userId, "No " + who + " bill yet", usually + " Worth checking it wasn't missed.",
                            "/bills?regular=" + r.key(), "maillook-bill-missing-" + r.key().replaceAll("[^a-z0-9]", "") + "-" + period);
                    sent++;
                } catch (Exception e) {
                    log.debug("Missing-bill alert not sent: {}", e.toString());
                }
            }
        }
        return sent;
    }

    // ── learning ─────────────────────────────────────────────

    /** Bills by biller and account, oldest first. A bill with no account joins the biller's only account. */
    private Map<String, List<Row>> groups(long userId) {
        List<Row> rows = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT b.id, b.vendor, b.vendor_key, b.customer_ref, b.account_name, b.amount, b.currency, b.invoice_date, b.created_at, " +
                        "m.received_at AS mail_at FROM bills b LEFT JOIN messages m ON m.id = b.message_id " +
                        "WHERE b.user_id = ? AND b.kind = 'bill' AND b.amount IS NOT NULL", userId)) {
            String first = BillService.firstWord(Objects.toString(r.get("vendor_key"), ""));
            if (first == null) continue;
            LocalDate at = r.get("mail_at") != null ? date(r.get("mail_at")) : r.get("invoice_date") != null ? date(r.get("invoice_date")) : date(r.get("created_at"));
            if (at == null) continue;
            String ref = r.get("customer_ref") != null ? r.get("customer_ref").toString().replaceAll("\\s", "")
                    : r.get("account_name") != null ? r.get("account_name").toString().trim().toLowerCase(Locale.ROOT) : null;
            String account = r.get("account_name") != null ? r.get("account_name").toString() : r.get("customer_ref") != null ? r.get("customer_ref").toString() : null;
            rows.add(new Row(((Number) r.get("id")).longValue(), Objects.toString(r.get("vendor"), first), first + (ref == null ? "" : "|" + ref),
                    account, (BigDecimal) r.get("amount"), Objects.toString(r.get("currency"), "INR"), at));
        }
        // A bill with no account: the biller's account when it has just one.
        Map<String, Set<String>> accounts = new HashMap<>();
        for (Row r : rows) if (r.first().contains("|")) accounts.computeIfAbsent(r.first().substring(0, r.first().indexOf('|')), k -> new HashSet<>()).add(r.first());
        Map<String, List<Row>> out = new LinkedHashMap<>();
        for (Row r : rows) {
            String key = r.first();
            if (!key.contains("|") && accounts.containsKey(key) && accounts.get(key).size() == 1) key = accounts.get(key).iterator().next();
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        out.values().forEach(g -> g.sort(Comparator.comparing(Row::at).thenComparing(Row::id)));
        return out;
    }

    private Regular regular(String key, List<Row> g, LocalDate today) {
        if (g.size() < 2) return null;
        // One per arrival: a reminder for the same bill a few days later is the same arrival.
        List<Row> arrivals = new ArrayList<>();
        for (Row r : g) {
            if (!arrivals.isEmpty() && ChronoUnit.DAYS.between(arrivals.get(arrivals.size() - 1).at(), r.at()) < 15) continue;
            arrivals.add(r);
        }
        if (arrivals.size() < 2) return null;
        List<Integer> gaps = new ArrayList<>();
        for (int i = 1; i < arrivals.size(); i++) gaps.add((int) ChronoUnit.DAYS.between(arrivals.get(i - 1).at(), arrivals.get(i).at()));
        Collections.sort(gaps);
        int gap = gaps.get(gaps.size() / 2);
        Every every = null;
        for (Every e : Every.values()) if (gap >= e.min && gap <= e.max) every = e;
        if (every == null) return null;
        // Most gaps must agree: a pattern, not two bills that happen to be a month apart among others.
        final Every ev = every;
        long agree = gaps.stream().filter(x -> x >= ev.min && x <= ev.max).count();
        if (agree * 2 < gaps.size() + 1) return null;

        Row last = arrivals.get(arrivals.size() - 1);
        List<Integer> days = new ArrayList<>();
        for (Row r : arrivals) days.add(r.at().getDayOfMonth());
        Collections.sort(days);
        int usualDay = days.get(days.size() / 2);
        LocalDate nextBy = switch (every) {
            case MONTH -> {
                LocalDate n = last.at().plusMonths(1);
                yield n.withDayOfMonth(Math.min(usualDay, n.lengthOfMonth()));
            }
            case QUARTER -> last.at().plusMonths(3);
            case YEAR -> last.at().plusYears(1);
        };
        if (nextBy.isBefore(last.at().plusDays(every.min))) nextBy = last.at().plusDays(gap);
        List<BigDecimal> recent = new ArrayList<>();
        for (int i = Math.max(0, arrivals.size() - 3); i < arrivals.size(); i++) recent.add(arrivals.get(i).amount());
        boolean late = !today.isBefore(nextBy.plusDays(LATE_DAYS)) && today.isBefore(nextBy.plusDays(every.max));
        List<Long> ids = new ArrayList<>();
        for (Row r : g) ids.add(r.id());
        return new Regular(key, last.vendor(), last.account(), every, usualDay, median(recent), last.currency(),
                last.at(), nextBy, late, ids);
    }

    // ── helpers ──────────────────────────────────────────────

    static BigDecimal median(List<BigDecimal> xs) {
        List<BigDecimal> s = new ArrayList<>(xs);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : s.get(n / 2 - 1).add(s.get(n / 2)).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    static String ordinal(int d) {
        String suffix = d >= 11 && d <= 13 ? "th" : switch (d % 10) { case 1 -> "st"; case 2 -> "nd"; case 3 -> "rd"; default -> "th"; };
        return d + suffix;
    }

    private static LocalDate date(Object at) {
        if (at == null) return null;
        if (at instanceof OffsetDateTime o) return o.atZoneSameInstant(BillService.INDIA).toLocalDate();
        if (at instanceof java.sql.Timestamp t) return t.toInstant().atZone(BillService.INDIA).toLocalDate();
        if (at instanceof java.sql.Date d) return d.toLocalDate();
        try {
            return OffsetDateTime.parse(at.toString()).atZoneSameInstant(BillService.INDIA).toLocalDate();
        } catch (Exception e) {
            try {
                return LocalDate.parse(at.toString().substring(0, 10));
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private static ZoneId zone(Object tz) {
        try {
            return tz == null || tz.toString().isBlank() ? BillService.INDIA : ZoneId.of(tz.toString());
        } catch (Exception e) {
            return BillService.INDIA;
        }
    }
}
