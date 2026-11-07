package software.magizhchi.maillook.bills;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.magizhchi.maillook.common.Json;
import software.magizhchi.maillook.mail.MailActions;
import software.magizhchi.maillook.mail.MessageParser;
import software.magizhchi.maillook.mail.MessageRepo;
import software.magizhchi.maillook.notify.PushService;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bills from your mail.
 *
 * <ol>
 *   <li><b>Screen</b> (free): new inbox mail of people who turned Bills on goes through
 *       {@link BillDetector}; likely bills are queued, the rest skipped.</li>
 *   <li><b>Read</b>: queued mail goes to Magizhchi Data with its text and up to three PDFs or photos.
 *       The first {@code free-per-month} reads each month are on MailLook; after that, the
 *       person's Data credits.</li>
 *   <li><b>Save</b>: a bill, receipt or credit note becomes a row in {@code bills}; a reminder mail
 *       for the same invoice joins the bill already there, and a receipt for it marks it paid.</li>
 *   <li><b>Remind</b>: 1–3 days before the due date and on the day, by day-time in their time zone.</li>
 * </ol>
 */
@Service
public class BillService {
    private static final Logger log = LoggerFactory.getLogger(BillService.class);
    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final int MAX_FILES = 3;
    static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    static final int MAX_TRIES = 3;
    static final int SAME_BILL_DAYS = 10;

    /** How reading one mail went. */
    public enum Outcome { BILL, NOT_BILL, NO_CREDITS, NO_ACCOUNT, FAILED }

    public record Result(Outcome outcome, Long billId, String message) {}

    private final JdbcTemplate jdbc;
    private final MailActions actions;
    private final DataClient data;
    private final PushService push;
    private final FxRates fx;
    private final int freePerMonth;
    private final boolean timer;

    public BillService(JdbcTemplate jdbc, MailActions actions, DataClient data, PushService push, FxRates fx,
                       @Value("${maillook.bills.free-per-month:20}") int freePerMonth,
                       @Value("${maillook.bills.timer:true}") boolean timer) {
        this.timer = timer;
        this.jdbc = jdbc;
        this.actions = actions;
        this.data = data;
        this.push = push;
        this.fx = fx;
        this.freePerMonth = freePerMonth;
    }

    public boolean configured() {
        return data.configured();
    }

    // ── settings ─────────────────────────────────────────────

    public boolean isOn(long userId) {
        return Boolean.TRUE.equals(jdbc.query("SELECT bills_on FROM users WHERE id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : false, userId));
    }

    public void setOn(long userId, boolean on) {
        if (on) {
            jdbc.update("UPDATE users SET bills_on = TRUE, bills_on_at = COALESCE(bills_on_at, ?) WHERE id = ?",
                    OffsetDateTime.now(ZoneOffset.UTC), userId);
        } else {
            jdbc.update("UPDATE users SET bills_on = FALSE WHERE id = ?", userId);
        }
    }

    public int freePerMonth() {
        return freePerMonth;
    }

    public int freeLeft(long userId) {
        String month = YearMonth.now(INDIA).toString();
        Map<String, Object> u = jdbc.queryForMap("SELECT bills_free_month, bills_free_used FROM users WHERE id = ?", userId);
        int used = month.equals(u.get("bills_free_month")) ? ((Number) u.get("bills_free_used")).intValue() : 0;
        return Math.max(0, freePerMonth - used);
    }

    private void useFree(long userId) {
        String month = YearMonth.now(INDIA).toString();
        jdbc.update("UPDATE users SET bills_free_used = CASE WHEN bills_free_month = ? THEN bills_free_used + 1 ELSE 1 END, " +
                "bills_free_month = ? WHERE id = ?", month, month, userId);
    }

    /** Mail waiting, by state: queued, no_credits, no_account, failed. */
    public Map<String, Integer> waiting(long userId) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String s : List.of("queued", "no_credits", "no_account", "failed")) out.put(s, 0);
        jdbc.query("SELECT m.bill_state, COUNT(*) FROM messages m JOIN accounts a ON a.id = m.account_id " +
                        "WHERE a.user_id = ? AND m.bill_state IN ('queued','no_credits','no_account','failed') GROUP BY m.bill_state",
                rs -> { out.put(rs.getString(1), rs.getInt(2)); }, userId);
        return out;
    }

    /** Puts mail that waited for credits, an account or a retry back in the queue. */
    public int retry(long userId) {
        return jdbc.update("UPDATE messages SET bill_state = 'queued', bill_tries = 0 WHERE bill_state IN ('no_credits','no_account','failed') " +
                "AND account_id IN (SELECT id FROM accounts WHERE user_id = ?)", userId);
    }

    // ── screening ────────────────────────────────────────────

    /**
     * Looks through inbox mail of the last {@code days} days that hasn't been looked at yet.
     * @param apply false only counts (for "about N bills, about M credits")
     * @return how many look like bills
     */
    public int scan(long userId, int days, boolean apply) {
        OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minusDays(Math.max(1, Math.min(days, 365)));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT m.id, m.subject, m.body_text, m.body_html, m.attachments, m.from_email FROM messages m " +
                        "JOIN accounts a ON a.id = m.account_id WHERE a.user_id = ? AND m.folder = 'INBOX' " +
                        "AND m.bill_state IS NULL AND m.received_at >= ?", userId, since);
        return screen(userId, rows, apply);
    }

    private int screen(long userId, List<Map<String, Object>> rows, boolean apply) {
        Map<String, Boolean> rules = billers(userId);
        int bills = 0;
        for (Map<String, Object> r : rows) {
            String body = r.get("body_text") != null ? r.get("body_text").toString()
                    : r.get("body_html") == null ? "" : MessageParser.htmlToText(r.get("body_html").toString());
            List<String> names = new ArrayList<>();
            for (Map<String, Object> a : Json.maps(str(r.get("attachments")))) names.add(str(a.get("name")));
            String from = str(r.get("from_email")).toLowerCase(Locale.ROOT);
            boolean bill = BillDetector.looksLikeBill(str(r.get("subject")), body, names, rules.get(from));
            if (bill) bills++;
            if (apply) {
                jdbc.update("UPDATE messages SET bill_state = ? WHERE id = ? AND bill_state IS NULL",
                        bill ? "queued" : "skip", ((Number) r.get("id")).longValue());
            }
        }
        return bills;
    }

    private Map<String, Boolean> billers(long userId) {
        Map<String, Boolean> out = new HashMap<>();
        jdbc.query("SELECT from_email, is_biller FROM billers WHERE user_id = ?",
                rs -> { out.put(rs.getString(1), rs.getBoolean(2)); }, userId);
        return out;
    }

    public void setBiller(long userId, String fromEmail, boolean isBiller) {
        if (fromEmail == null || fromEmail.isBlank()) return;
        String e = fromEmail.trim().toLowerCase(Locale.ROOT);
        if (jdbc.update("UPDATE billers SET is_biller = ? WHERE user_id = ? AND from_email = ?", isBiller, userId, e) == 0) {
            jdbc.update("INSERT INTO billers (user_id, from_email, is_biller) VALUES (?, ?, ?)", userId, e, isBiller);
        }
    }

    // ── the worker ───────────────────────────────────────────

    @Scheduled(initialDelay = 45_000, fixedDelay = 60_000)
    public void onTimer() {
        if (!timer || !configured()) return;
        try {
            work(15);
        } catch (Exception e) {
            log.warn("Bills pass failed: {}", e.toString());
        }
        try {
            mergeDuplicates();
        } catch (Exception e) {
            log.warn("Bill merging failed: {}", e.toString());
        }
        try {
            remind(Instant.now());
        } catch (Exception e) {
            log.warn("Bill reminders failed: {}", e.toString());
        }
    }

    /**
     * Screens new mail of everyone with Bills on, then reads up to {@code limit} queued mails.
     * @return how many were read
     */
    public int work(int limit) {
        List<Map<String, Object>> fresh = jdbc.queryForList(
                "SELECT m.id, m.subject, m.body_text, m.body_html, m.attachments, m.from_email, a.user_id FROM messages m " +
                        "JOIN accounts a ON a.id = m.account_id JOIN users u ON u.id = a.user_id " +
                        "WHERE u.bills_on = TRUE AND m.folder = 'INBOX' AND m.bill_state IS NULL AND m.created_at >= u.bills_on_at " +
                        "ORDER BY m.id LIMIT 500");
        Map<Long, List<Map<String, Object>>> byUser = new LinkedHashMap<>();
        for (Map<String, Object> r : fresh) byUser.computeIfAbsent(((Number) r.get("user_id")).longValue(), k -> new ArrayList<>()).add(r);
        byUser.forEach((userId, rows) -> screen(userId, rows, true));

        List<Map<String, Object>> queued = jdbc.queryForList(
                "SELECT m.id, a.user_id FROM messages m JOIN accounts a ON a.id = m.account_id JOIN users u ON u.id = a.user_id " +
                        "WHERE u.bills_on = TRUE AND m.bill_state = 'queued' ORDER BY m.received_at DESC LIMIT ?", limit);
        Set<Long> stopped = new HashSet<>();
        int read = 0;
        for (Map<String, Object> q : queued) {
            long userId = ((Number) q.get("user_id")).longValue();
            if (stopped.contains(userId)) continue;
            Result r = read(userId, ((Number) q.get("id")).longValue());
            read++;
            // Out of credits or no Data account: the rest of their mail would say the same.
            if (r.outcome() == Outcome.NO_CREDITS || r.outcome() == Outcome.NO_ACCOUNT) stopped.add(userId);
        }
        return read;
    }

    // ── reading one mail ─────────────────────────────────────

    /** Reads one of this person's mails as a bill now (the worker, or "This is a bill"). */
    public Result read(long userId, long messageId) {
        MessageRepo.Row m = actions.owned(userId, messageId);
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);

        StringBuilder text = new StringBuilder();
        text.append("From: ").append(m.fromName() == null ? "" : m.fromName() + " ").append('<').append(str(m.fromEmail())).append(">\n");
        text.append("Subject: ").append(str(m.subject())).append('\n');
        text.append("Received: ").append(m.receivedAt() == null ? "" : m.receivedAt().atZoneSameInstant(INDIA).toLocalDate()).append("\n\n");
        String body = m.bodyText() != null && !m.bodyText().isBlank() ? m.bodyText()
                : m.bodyHtml() == null ? "" : MessageParser.htmlToText(m.bodyHtml());
        text.append(body.length() > 12_000 ? body.substring(0, 12_000) : body);

        List<DataClient.File> files = files(userId, m);
        boolean free = freeLeft(userId) > 0;
        DataClient.Reply reply = data.readBill(email, str(m.subject()), text.toString(), free, files);

        if (!reply.ok()) {
            String code = reply.errorCode();
            if (reply.code() == 402 || "NO_CREDITS".equals(code)) {
                state(messageId, "no_credits");
                return new Result(Outcome.NO_CREDITS, null, "Your Magizhchi Data credits have run out. Add credits in Magizhchi Data, then read the waiting bills.");
            }
            if (reply.code() == 409 || "NO_ACCOUNT".equals(code)) {
                state(messageId, "no_account");
                return new Result(Outcome.NO_ACCOUNT, null, "This month's free bills are used up. Open Magizhchi Data once with your Magizhchi ID to keep reading bills on your Data credits.");
            }
            jdbc.update("UPDATE messages SET bill_tries = bill_tries + 1 WHERE id = ?", messageId);
            Integer n = jdbc.queryForObject("SELECT bill_tries FROM messages WHERE id = ?", Integer.class, messageId);
            if (n == null || n >= MAX_TRIES) state(messageId, "failed");
            return new Result(Outcome.FAILED, null, "Magizhchi Data couldn't read this mail just now. Try again in a minute.");
        }
        if (free && !reply.json().path("cached").asBoolean(false)) useFree(userId);

        JsonNode b = reply.json().path("bill");
        String kind = b.path("kind").asText("other");
        BigDecimal amount = decimal(b.get("amount"));
        if (!Set.of("bill", "receipt", "credit_note").contains(kind) || amount == null) {
            state(messageId, "not_bill");
            return new Result(Outcome.NOT_BILL, null, "This mail doesn't look like a bill.");
        }
        long billId = save(userId, m, b, kind, amount, reply.json().path("source").asText("text"));
        jdbc.update("UPDATE messages SET bill_state = 'read', bill_id = ? WHERE id = ?", billId, messageId);
        return new Result(Outcome.BILL, billId, null);
    }

    /** Up to three PDFs or bill-like photos; a file the server won't give is left out. */
    private List<DataClient.File> files(long userId, MessageRepo.Row m) {
        List<DataClient.File> out = new ArrayList<>();
        List<Map<String, Object>> atts = m.attachments() == null ? List.of() : m.attachments();
        // PDFs first: bills are PDFs far more often than photos, and photos are often logos.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < atts.size(); i++) if (str(atts.get(i).get("name")).toLowerCase(Locale.ROOT).endsWith(".pdf")) order.add(i);
        for (int i = 0; i < atts.size(); i++) {
            String n = str(atts.get(i).get("name"));
            long size = atts.get(i).get("size") instanceof Number s ? s.longValue() : 0;
            if (!n.toLowerCase(Locale.ROOT).endsWith(".pdf") && BillDetector.readable(n) && size > 30_000) order.add(i);
        }
        for (int i : order) {
            if (out.size() >= MAX_FILES) break;
            long size = atts.get(i).get("size") instanceof Number s ? s.longValue() : 0;
            if (size > MAX_FILE_BYTES) continue;
            try {
                MailActions.Download d = actions.attachment(userId, m.id(), i);
                if (d.bytes().length <= MAX_FILE_BYTES) out.add(new DataClient.File(d.name(), d.contentType(), d.bytes()));
            } catch (Exception e) {
                log.debug("Attachment {} of mail {} not fetched for Bills: {}", i, m.id(), e.toString());
            }
        }
        return out;
    }

    private long save(long userId, MessageRepo.Row m, JsonNode b, String kind, BigDecimal amount, String source) {
        String vendor = text(b, "vendor");
        if (vendor == null) vendor = m.fromName() != null && !m.fromName().isBlank() ? m.fromName() : str(m.fromEmail());
        String vendorKey = vendor.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        String invoiceNo = text(b, "invoiceNo");
        LocalDate due = date(text(b, "dueDate"));
        boolean paid = !"bill".equals(kind) || b.path("alreadyPaid").asBoolean(false);

        // The same invoice again: a reminder joins the bill, a receipt marks it paid.
        Long existing = null;
        if (invoiceNo != null) {
            existing = jdbc.query("SELECT id FROM bills WHERE user_id = ? AND vendor_key = ? AND invoice_no = ? ORDER BY id LIMIT 1",
                    rs -> rs.next() ? rs.getLong(1) : null, userId, vendorKey, invoiceNo);
        }
        if (existing == null && "bill".equals(kind) && due != null) {
            // The same bill told twice, perhaps under another name ("ACT Broadband" in a Google Pay mail,
            // "ACT Fibernet" from ACT): the same amount, a due date within ten days, the same first word.
            String first = firstWord(vendorKey);
            for (Map<String, Object> r : jdbc.queryForList("SELECT id, vendor_key FROM bills WHERE user_id = ? AND kind = 'bill' " +
                    "AND amount = ? AND due_date BETWEEN ? AND ? ORDER BY id", userId, amount, due.minusDays(SAME_BILL_DAYS), due.plusDays(SAME_BILL_DAYS))) {
                if (first != null && first.equals(firstWord(str(r.get("vendor_key"))))) {
                    existing = ((Number) r.get("id")).longValue();
                    break;
                }
            }
        }
        String payLink = Optional.ofNullable(text(b, "payLink")).orElse(payLinkIn(m.bodyHtml()));
        String upi = Optional.ofNullable(text(b, "upiId")).orElse(upiIn(m.bodyHtml()));
        if (existing != null) {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            // What this mail adds that the first one didn't say; the earlier due date wins.
            jdbc.update("UPDATE bills SET account_name = COALESCE(account_name, ?), customer_ref = COALESCE(customer_ref, ?), " +
                            "pay_link = COALESCE(pay_link, ?), upi_id = COALESCE(upi_id, ?), invoice_no = COALESCE(invoice_no, ?), updated_at = ? WHERE id = ?",
                    clip(text(b, "accountName"), 100), text(b, "customerRef"), clip(payLink, 1000), clip(upi, 100), invoiceNo, now, existing);
            if (due != null) jdbc.update("UPDATE bills SET due_date = ? WHERE id = ? AND (due_date IS NULL OR due_date > ?)", due, existing, due);
            if (paid && !"credit_note".equals(kind)) {
                jdbc.update("UPDATE bills SET status = 'paid', paid_at = COALESCE(paid_at, ?), updated_at = ? WHERE id = ?",
                        now, now, existing);
            }
            return existing;
        }

        Map<String, Object> conf = new LinkedHashMap<>();
        b.path("confidence").fields().forEachRemaining(e -> conf.put(e.getKey(), e.getValue().asDouble()));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("INSERT INTO bills (user_id, message_id, kind, vendor, vendor_key, vendor_gstin, invoice_no, invoice_date, due_date, " +
                        "amount, currency, cgst, sgst, igst, customer_ref, account_name, pay_link, upi_id, payee_account, payee_ifsc, autopay, status, paid_at, " +
                        "source, confidence, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                userId, m.id(), kind, clip(vendor, 200), clip(vendorKey, 200), text(b, "vendorGstin"), invoiceNo,
                date(text(b, "invoiceDate")), due, amount, Optional.ofNullable(text(b, "currency")).orElse("INR"),
                decimal(b.get("cgst")), decimal(b.get("sgst")), decimal(b.get("igst")), text(b, "customerRef"),
                clip(text(b, "accountName"), 100), clip(payLink, 1000), clip(upi, 100), text(b, "payeeAccount"), text(b, "payeeIfsc"), b.path("autopay").asBoolean(false),
                paid ? "paid" : "due", paid ? now : null, source, Json.write(conf), now, now);
        return jdbc.queryForObject("SELECT MAX(id) FROM bills WHERE user_id = ? AND message_id = ?", Long.class, userId, m.id());
    }

    private void state(long messageId, String state) {
        jdbc.update("UPDATE messages SET bill_state = ? WHERE id = ?", state, messageId);
    }

    /**
     * Folds bills that are the same bill told twice into one (see {@link #save}), for bills read
     * before that rule existed or read in the other order. The older bill stays.
     * @return how many were folded in
     */
    public int mergeDuplicates() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, user_id, vendor_key, amount, currency, due_date FROM bills " +
                "WHERE kind = 'bill' AND due_date IS NOT NULL AND amount IS NOT NULL AND (status = 'due' OR paid_at >= ?) ORDER BY id",
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(60));
        Set<Long> gone = new HashSet<>();
        int merged = 0;
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> keep = rows.get(i);
            long keepId = ((Number) keep.get("id")).longValue();
            if (gone.contains(keepId)) continue;
            for (int j = i + 1; j < rows.size(); j++) {
                Map<String, Object> dup = rows.get(j);
                long dupId = ((Number) dup.get("id")).longValue();
                if (gone.contains(dupId) || !sameBill(keep, dup)) continue;
                fold(keepId, dupId);
                gone.add(dupId);
                merged++;
            }
        }
        return merged;
    }

    private static boolean sameBill(Map<String, Object> a, Map<String, Object> b) {
        if (!Objects.equals(a.get("user_id"), b.get("user_id")) || !Objects.equals(a.get("currency"), b.get("currency"))) return false;
        if (((BigDecimal) a.get("amount")).compareTo((BigDecimal) b.get("amount")) != 0) return false;
        String first = firstWord(str(a.get("vendor_key")));
        if (first == null || !first.equals(firstWord(str(b.get("vendor_key"))))) return false;
        LocalDate da = LocalDate.parse(a.get("due_date").toString().substring(0, 10));
        LocalDate db = LocalDate.parse(b.get("due_date").toString().substring(0, 10));
        return Math.abs(Duration.between(da.atStartOfDay(), db.atStartOfDay()).toDays()) <= SAME_BILL_DAYS;
    }

    private void fold(long keepId, long dupId) {
        Map<String, Object> d = jdbc.queryForMap("SELECT * FROM bills WHERE id = ?", dupId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("UPDATE bills SET account_name = COALESCE(account_name, ?), customer_ref = COALESCE(customer_ref, ?), " +
                        "pay_link = COALESCE(pay_link, ?), upi_id = COALESCE(upi_id, ?), invoice_no = COALESCE(invoice_no, ?), " +
                        "vendor_gstin = COALESCE(vendor_gstin, ?), payee_account = COALESCE(payee_account, ?), payee_ifsc = COALESCE(payee_ifsc, ?), " +
                        "due_date = CASE WHEN ? < due_date THEN ? ELSE due_date END, " +
                        "status = CASE WHEN ? = 'paid' THEN 'paid' ELSE status END, paid_at = COALESCE(paid_at, ?), updated_at = ? WHERE id = ?",
                d.get("account_name"), d.get("customer_ref"), d.get("pay_link"), d.get("upi_id"), d.get("invoice_no"),
                d.get("vendor_gstin"), d.get("payee_account"), d.get("payee_ifsc"),
                d.get("due_date"), d.get("due_date"), d.get("status"), d.get("paid_at"), now, keepId);
        jdbc.update("UPDATE messages SET bill_id = ? WHERE bill_id = ?", keepId, dupId);
        jdbc.update("DELETE FROM bills WHERE id = ?", dupId);
    }

    private static String firstWord(String vendorKey) {
        if (vendorKey == null) return null;
        String w = vendorKey.trim().split(" ")[0];
        return w.length() >= 2 ? w : null;
    }

    private static final Pattern ANCHOR = Pattern.compile(
            "<a\\s[^>]*?href\\s*=\\s*([\"'])(.*?)\\1[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PAY_WORDS = Pattern.compile(
            "\\b(pay|quick\\s*pay|make\\s+(a\\s+)?payment|recharge\\s+now)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_PAY = Pattern.compile(
            "unsubscribe|pay\\s*later|history|privacy|terms|paid|paypal", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPI_LINK = Pattern.compile(
            "upi://pay\\?[^\"'\\s>]*?\\bpa=([A-Za-z0-9._-]{2,64}(?:@|%40)[A-Za-z][A-Za-z0-9.-]{1,32})", Pattern.CASE_INSENSITIVE);

    /** The mail's own "Pay now" button, when it has one that goes to an https page. */
    static String payLinkIn(String html) {
        if (html == null || html.isBlank()) return null;
        Matcher m = ANCHOR.matcher(html.length() > 400_000 ? html.substring(0, 400_000) : html);
        while (m.find()) {
            String href = m.group(2).replace("&amp;", "&").trim();
            String label = m.group(3).replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
            if (label.isEmpty() || label.length() > 40 || !href.startsWith("https://") || href.length() > 1000) continue;
            if (PAY_WORDS.matcher(label).find() && !NOT_PAY.matcher(label).find()) return href;
        }
        return null;
    }

    /** A UPI ID from a {@code upi://pay?pa=…} link in the mail, if there is one. */
    static String upiIn(String html) {
        if (html == null) return null;
        Matcher m = UPI_LINK.matcher(html);
        return m.find() ? m.group(1).replace("%40", "@") : null;
    }

    // ── reminders ────────────────────────────────────────────

    /**
     * Rings 1–3 days before a bill is due and on the day, between 8 am and 9 pm where the person is.
     * Bills paid automatically don't ring. @return how many alerts were sent
     */
    public int remind(Instant now) {
        LocalDate utcToday = now.atZone(ZoneOffset.UTC).toLocalDate();
        List<Map<String, Object>> due = jdbc.queryForList(
                "SELECT b.id, b.user_id, b.vendor, b.amount, b.currency, b.invoice_no, b.due_date, b.remind_before_sent, " +
                        "b.remind_day_sent, u.tz FROM bills b JOIN users u ON u.id = b.user_id WHERE u.bills_on = TRUE " +
                        "AND b.status = 'due' AND b.autopay = FALSE AND b.due_date BETWEEN ? AND ? " +
                        "AND (b.remind_before_sent = FALSE OR b.remind_day_sent = FALSE)",
                utcToday.minusDays(1), utcToday.plusDays(4));
        int sent = 0;
        for (Map<String, Object> b : due) {
            ZoneId zone = zone(b.get("tz"));
            ZonedDateTime local = now.atZone(zone);
            if (local.getHour() < 8 || local.getHour() >= 21) continue;
            LocalDate today = local.toLocalDate();
            LocalDate dueDate = LocalDate.parse(b.get("due_date").toString().substring(0, 10));
            long days = Duration.between(today.atStartOfDay(), dueDate.atStartOfDay()).toDays();
            long id = ((Number) b.get("id")).longValue();
            long userId = ((Number) b.get("user_id")).longValue();
            String vendor = str(b.get("vendor"));
            String money = money((BigDecimal) b.get("amount"), str(b.get("currency")));
            String inv = b.get("invoice_no") == null ? "" : " · Invoice " + b.get("invoice_no");
            if (days == 0 && !Boolean.TRUE.equals(b.get("remind_day_sent"))) {
                push.reminder(userId, "Bill due today: " + vendor, money + inv + ". Tap to see it.", "/bills?open=" + id, "maillook-bill-" + id + "-day");
                jdbc.update("UPDATE bills SET remind_day_sent = TRUE, remind_before_sent = TRUE WHERE id = ?", id);
                sent++;
            } else if (days >= 1 && days <= 3 && !Boolean.TRUE.equals(b.get("remind_before_sent"))) {
                String when = days == 1 ? "tomorrow" : "on " + dueDate.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH));
                push.reminder(userId, vendor + " bill due " + when, money + inv + ". Tap to see it.", "/bills?open=" + id, "maillook-bill-" + id + "-before");
                jdbc.update("UPDATE bills SET remind_before_sent = TRUE WHERE id = ?", id);
                sent++;
            }
        }
        return sent;
    }

    /** "2 bills due this week · ₹3,400" for the morning briefing, or null. */
    public String briefingLine(long userId, LocalDate today) {
        if (!isOn(userId)) return null;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT amount, currency FROM bills WHERE user_id = ? AND status = 'due' " +
                "AND autopay = FALSE AND due_date BETWEEN ? AND ?", userId, today.minusDays(30), today.plusDays(7));
        if (rows.isEmpty()) return null;
        BigDecimal inr = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            if (r.get("amount") == null) continue;
            BigDecimal a = (BigDecimal) r.get("amount");
            BigDecimal v = "INR".equals(r.get("currency")) ? a : fx.inr(a, str(r.get("currency")));
            if (v != null) inr = inr.add(v);
        }
        String n = rows.size() == 1 ? "1 bill due this week" : rows.size() + " bills due this week";
        return inr.signum() > 0 ? n + " (" + money(inr, "INR") + ")" : n;
    }

    // ── helpers ──────────────────────────────────────────────

    static String money(BigDecimal amount, String currency) {
        if (amount == null) return "";
        if (currency == null || currency.isBlank() || "INR".equals(currency)) {
            NumberFormat f = NumberFormat.getNumberInstance(new Locale("en", "IN"));
            f.setMaximumFractionDigits(amount.stripTrailingZeros().scale() > 0 ? 2 : 0);
            f.setMinimumFractionDigits(amount.stripTrailingZeros().scale() > 0 ? 2 : 0);
            return "₹" + f.format(amount);
        }
        return currency + " " + amount.stripTrailingZeros().toPlainString();
    }

    private static ZoneId zone(Object tz) {
        try {
            return tz == null || tz.toString().isBlank() ? INDIA : ZoneId.of(tz.toString());
        } catch (Exception e) {
            return INDIA;
        }
    }

    private static String text(JsonNode j, String field) {
        JsonNode v = j.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    static BigDecimal decimal(JsonNode v) {
        if (v == null || v.isNull()) return null;
        try {
            return new BigDecimal(v.asText()).setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (Exception e) {
            return null;
        }
    }

    static LocalDate date(String s) {
        try {
            return s == null ? null : LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
