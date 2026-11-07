package software.magizhchi.maillook.bills;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import software.magizhchi.maillook.common.ApiException;
import software.magizhchi.maillook.mail.MailActions;
import software.magizhchi.maillook.mail.MessageRepo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * The Bills page and the bill card on a mail.
 *
 *   GET   /api/bills/setup              on, Data account and credits, free bills left, mail waiting
 *   PUT   /api/bills/setup  {on}
 *   GET   /api/bills/count              on, and bills overdue or due within 7 days (the sidebar)
 *   GET   /api/bills/scan?days=90       how many older mails look like bills (and the credits)
 *   POST  /api/bills/scan   {days}      queue them
 *   POST  /api/bills/retry              read mail that waited for credits, an account or a retry
 *   GET   /api/bills                    bills: everything due, and the last 120 days of paid
 *   PATCH /api/bills/{id}               fix a field, mark paid or due
 *   POST  /api/bills/{id}/not-a-bill    {never}: forget it (and never read that sender again)
 *   GET   /api/messages/{id}/bill       the bill read from this mail, if any
 *   POST  /api/messages/{id}/bill       "This is a bill": read it now
 */
@RestController
@RequestMapping("/api")
public class BillsController {
    private final BillService bills;
    private final DataClient data;
    private final MailActions actions;
    private final JdbcTemplate jdbc;
    private final FxRates fx;

    public BillsController(BillService bills, DataClient data, MailActions actions, JdbcTemplate jdbc, FxRates fx) {
        this.fx = fx;
        this.bills = bills;
        this.data = data;
        this.actions = actions;
        this.jdbc = jdbc;
    }

    @GetMapping("/bills/setup")
    public Map<String, Object> setup(@RequestAttribute long userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", bills.configured());
        m.put("on", bills.isOn(userId));
        m.put("freePerMonth", bills.freePerMonth());
        m.put("freeLeft", bills.freeLeft(userId));
        m.put("waiting", bills.waiting(userId));
        m.put("linked", false);
        m.put("balance", 0);
        m.put("costPerBill", 1);
        if (bills.configured()) {
            String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
            DataClient.Reply r = data.account(email);
            if (r.ok()) {
                m.put("linked", r.json().path("linked").asBoolean(false));
                m.put("balance", r.json().path("balance").asInt(0));
                m.put("costPerBill", r.json().path("costPerBill").asInt(1));
            } else {
                m.put("dataDown", true);
            }
        }
        return m;
    }

    /** For the sidebar: is Bills on, and how many bills are overdue or due in the next 7 days. */
    @GetMapping("/bills/count")
    public Map<String, Object> count(@RequestAttribute long userId) {
        boolean on = bills.configured() && bills.isOn(userId);
        Integer n = on ? jdbc.queryForObject("SELECT COUNT(*) FROM bills WHERE user_id = ? AND status = 'due' AND autopay = FALSE " +
                "AND due_date <= ?", Integer.class, userId, LocalDate.now(BillService.INDIA).plusDays(7)) : Integer.valueOf(0);
        return Map.of("configured", bills.configured(), "on", on, "dueSoon", n == null ? 0 : n);
    }

    @PutMapping("/bills/setup")
    public Map<String, Object> setOn(@RequestAttribute long userId, @RequestBody Map<String, Object> body) {
        requireConfigured();
        bills.setOn(userId, Boolean.TRUE.equals(body.get("on")));
        return setup(userId);
    }

    @GetMapping("/bills/scan")
    public Map<String, Object> estimate(@RequestAttribute long userId, @RequestParam(defaultValue = "90") int days) {
        requireConfigured();
        int n = bills.scan(userId, days, false);
        int free = Math.min(n, bills.freeLeft(userId));
        return Map.of("days", days, "bills", n, "free", free, "paid", n - free);
    }

    @PostMapping("/bills/scan")
    public Map<String, Object> scan(@RequestAttribute long userId, @RequestBody Map<String, Object> body) {
        requireConfigured();
        if (!bills.isOn(userId)) throw ApiException.bad("Turn Bills on first.");
        int days = body.get("days") instanceof Number d ? d.intValue() : 90;
        return Map.of("queued", bills.scan(userId, days, true));
    }

    @PostMapping("/bills/retry")
    public Map<String, Object> retry(@RequestAttribute long userId) {
        requireConfigured();
        return Map.of("queued", bills.retry(userId));
    }

    @GetMapping("/bills")
    public Map<String, Object> list(@RequestAttribute long userId) {
        OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minusDays(120);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT b.*, m.subject AS mail_subject, m.from_name AS mail_from FROM bills b LEFT JOIN messages m ON m.id = b.message_id " +
                        "WHERE b.user_id = ? AND (b.status = 'due' OR b.created_at >= ? OR b.paid_at >= ?) " +
                        "ORDER BY b.due_date NULLS LAST, b.id DESC LIMIT 500", userId, since, since);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) out.add(view(r));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bills", out);
        m.put("today", LocalDate.now(BillService.INDIA).toString());
        return m;
    }

    @PatchMapping("/bills/{id}")
    public Map<String, Object> update(@RequestAttribute long userId, @PathVariable long id, @RequestBody Map<String, Object> body) {
        owned(userId, id);
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (body.containsKey("vendor")) {
            String v = trimmed(body.get("vendor"), 200);
            if (v == null) throw ApiException.bad("Who is the bill from?");
            sets.add("vendor = ?");
            args.add(v);
        }
        if (body.containsKey("accountName")) { sets.add("account_name = ?"); args.add(trimmed(body.get("accountName"), 100)); }
        if (body.containsKey("invoiceNo")) { sets.add("invoice_no = ?"); args.add(trimmed(body.get("invoiceNo"), 100)); }
        if (body.containsKey("dueDate")) { sets.add("due_date = ?"); args.add(dateOrNull(body.get("dueDate"))); sets.add("remind_before_sent = FALSE"); sets.add("remind_day_sent = FALSE"); }
        if (body.containsKey("invoiceDate")) { sets.add("invoice_date = ?"); args.add(dateOrNull(body.get("invoiceDate"))); }
        if (body.containsKey("amount")) {
            BigDecimal a;
            try {
                a = new BigDecimal(String.valueOf(body.get("amount")).replaceAll("[^0-9.]", "")).setScale(2, java.math.RoundingMode.HALF_UP);
            } catch (Exception e) {
                throw ApiException.bad("The amount should be a number, like 1180 or 1180.50.");
            }
            sets.add("amount = ?");
            args.add(a);
        }
        if (body.containsKey("autopay")) { sets.add("autopay = ?"); args.add(Boolean.TRUE.equals(body.get("autopay"))); }
        if (body.containsKey("status")) {
            String s = String.valueOf(body.get("status"));
            if (!s.equals("due") && !s.equals("paid")) throw ApiException.bad("A bill is due or paid.");
            sets.add("status = ?");
            args.add(s);
            sets.add("paid_at = ?");
            args.add(s.equals("paid") ? OffsetDateTime.now(ZoneOffset.UTC) : null);
        }
        sets.add("checked = TRUE");
        sets.add("updated_at = ?");
        args.add(OffsetDateTime.now(ZoneOffset.UTC));
        args.add(id);
        jdbc.update("UPDATE bills SET " + String.join(", ", sets) + " WHERE id = ?", args.toArray());
        return one(userId, id);
    }

    @PostMapping("/bills/{id}/not-a-bill")
    public Map<String, Object> notABill(@RequestAttribute long userId, @PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = owned(userId, id);
        if (body != null && Boolean.TRUE.equals(body.get("never")) && b.get("message_id") != null) {
            String from = jdbc.query("SELECT from_email FROM messages WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null,
                    ((Number) b.get("message_id")).longValue());
            bills.setBiller(userId, from, false);
        }
        jdbc.update("UPDATE messages SET bill_state = 'not_bill', bill_id = NULL WHERE bill_id = ?", id);
        jdbc.update("DELETE FROM bills WHERE id = ?", id);
        return Map.of("removed", true);
    }

    @GetMapping("/messages/{id}/bill")
    public Map<String, Object> forMessage(@RequestAttribute long userId, @PathVariable long id) {
        actions.owned(userId, id);
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> row = jdbc.query("SELECT bill_state, bill_id FROM messages WHERE id = ?",
                rs -> rs.next() ? Map.<String, Object>of("state", Objects.toString(rs.getString(1), ""), "billId", rs.getLong(2)) : Map.of(), id);
        long billId = row.get("billId") instanceof Number n ? n.longValue() : 0;
        m.put("on", bills.configured() && bills.isOn(userId));
        m.put("state", row.getOrDefault("state", ""));
        m.put("bill", billId > 0 ? one(userId, billId) : null);
        return m;
    }

    @PostMapping("/messages/{id}/bill")
    public Map<String, Object> readNow(@RequestAttribute long userId, @PathVariable long id) {
        requireConfigured();
        MessageRepo.Row msg = actions.owned(userId, id);
        BillService.Result r = bills.read(userId, id);
        switch (r.outcome()) {
            case BILL -> {
                bills.setBiller(userId, msg.fromEmail(), true);
                return forMessage(userId, id);
            }
            case NOT_BILL -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Magizhchi Data read this mail and found no amount to pay in it.");
            case NO_CREDITS -> throw new ApiException(HttpStatus.PAYMENT_REQUIRED, r.message());
            case NO_ACCOUNT -> throw new ApiException(HttpStatus.CONFLICT, r.message());
            default -> throw new ApiException(HttpStatus.BAD_GATEWAY, r.message());
        }
    }

    // ── helpers ──────────────────────────────────────────────

    private Map<String, Object> one(long userId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT b.*, m.subject AS mail_subject, m.from_name AS mail_from FROM bills b LEFT JOIN messages m ON m.id = b.message_id " +
                        "WHERE b.id = ? AND b.user_id = ?", id, userId);
        if (rows.isEmpty()) throw ApiException.notFound("Bill");
        return view(rows.get(0));
    }

    private Map<String, Object> owned(long userId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM bills WHERE id = ? AND user_id = ?", id, userId);
        if (rows.isEmpty()) throw ApiException.notFound("Bill");
        return rows.get(0);
    }

    private Map<String, Object> view(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id"));
        m.put("messageId", r.get("message_id"));
        m.put("kind", r.get("kind"));
        m.put("vendor", r.get("vendor"));
        m.put("vendorGstin", r.get("vendor_gstin"));
        m.put("invoiceNo", r.get("invoice_no"));
        m.put("invoiceDate", day(r.get("invoice_date")));
        m.put("dueDate", day(r.get("due_date")));
        m.put("amount", r.get("amount"));
        m.put("currency", r.get("currency"));
        // Bills in dollars and the like also say what they come to in rupees today.
        FxRates.Rate rate = r.get("amount") == null ? null : fx.toInr(Objects.toString(r.get("currency"), "INR"));
        if (rate != null) {
            m.put("amountInr", ((BigDecimal) r.get("amount")).multiply(rate.inr()).setScale(2, java.math.RoundingMode.HALF_UP));
            m.put("fxRate", rate.inr());
            m.put("fxDate", rate.date());
        }
        m.put("cgst", r.get("cgst"));
        m.put("sgst", r.get("sgst"));
        m.put("igst", r.get("igst"));
        m.put("customerRef", r.get("customer_ref"));
        m.put("accountName", r.get("account_name"));
        m.put("payLink", r.get("pay_link"));
        m.put("upiId", r.get("upi_id"));
        m.put("payeeAccount", r.get("payee_account"));
        m.put("payeeIfsc", r.get("payee_ifsc"));
        m.put("autopay", r.get("autopay"));
        m.put("status", r.get("status"));
        m.put("paidAt", r.get("paid_at") == null ? null : r.get("paid_at").toString());
        m.put("source", r.get("source"));
        m.put("checked", r.get("checked"));
        Map<String, Object> conf = new LinkedHashMap<>();
        try {
            JsonNode c = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Objects.toString(r.get("confidence"), "{}"));
            c.fields().forEachRemaining(e -> conf.put(e.getKey(), e.getValue().asDouble()));
        } catch (Exception ignored) {
            // no confidence: nothing to highlight
        }
        // Once the person has looked at it, nothing is "unsure" any more.
        m.put("confidence", Boolean.TRUE.equals(r.get("checked")) ? Map.of() : conf);
        m.put("mailSubject", r.get("mail_subject"));
        m.put("mailFrom", r.get("mail_from"));
        return m;
    }

    private static String day(Object d) {
        return d == null ? null : d.toString().substring(0, 10);
    }

    private static String trimmed(Object o, int max) {
        if (o == null) return null;
        String s = o.toString().trim();
        if (s.isEmpty()) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static LocalDate dateOrNull(Object o) {
        if (o == null || o.toString().isBlank()) return null;
        try {
            return LocalDate.parse(o.toString().substring(0, 10));
        } catch (Exception e) {
            throw ApiException.bad("Dates look like 2026-11-15.");
        }
    }

    private void requireConfigured() {
        if (!bills.configured()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Bills aren't set up on this server yet.");
    }

}
