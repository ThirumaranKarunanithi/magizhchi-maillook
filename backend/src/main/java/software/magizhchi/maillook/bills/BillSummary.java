package software.magizhchi.maillook.bills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import software.magizhchi.maillook.common.ApiException;
import software.magizhchi.maillook.mail.MailActions;
import software.magizhchi.maillook.mail.MessageRepo;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The month of bills at a glance, and the GST pack for an accountant.
 *
 *   GET /api/bills/summary?month=2026-11      spending by kind, against last month and the last six
 *   GET /api/bills/gst-pack?month=2026-11     a ZIP: every bill of the month as a spreadsheet (CSV),
 *                                             and the PDF invoices of bills with a GSTIN
 *
 * A bill counts in the month of its invoice date (else when its mail came, else its due date). Bills in
 * dollars count at the rupees that went out when a bank alert said so, else at today's rate.
 */
@RestController
@RequestMapping("/api/bills")
public class BillSummary {
    private static final Logger log = LoggerFactory.getLogger(BillSummary.class);
    static final int PACK_MAX_FILES = 60;
    static final long PACK_MAX_BYTES = 40L * 1024 * 1024;

    /** Kinds of spending, guessed from the biller's name; the person can change one in Edit. */
    public static final List<String> CATEGORIES = List.of(
            "internet", "phone", "power", "utilities", "software", "insurance", "education", "home", "tax", "entertainment", "shopping", "other");

    private static final List<Map.Entry<String, Pattern>> GUESS = List.of(
            Map.entry("internet", words("act", "fibernet", "broadband", "hathway", "excitel", "tikona", "spectra", "jiofiber", "airtel xstream", "bsnl ftth", "internet")),
            Map.entry("phone", words("postpaid", "prepaid", "mobile", "vodafone", "vi", "jio", "airtel", "bsnl", "mtnl", "recharge")),
            Map.entry("power", words("tnpdcl", "tneb", "tangedco", "electricity", "power", "bescom", "msedcl", "bses", "kseb", "apspdcl", "tsspdcl", "cesc", "torrent")),
            Map.entry("utilities", words("gas", "indane", "bharatgas", "hp gas", "water", "cmwssb", "metro water", "piped")),
            Map.entry("software", words("aws", "amazon web services", "google cloud", "gcp", "azure", "railway", "anthropic", "openai", "github", "hostinger",
                    "godaddy", "digitalocean", "vercel", "netlify", "cloudflare", "heroku", "render", "namecheap", "zoho", "microsoft", "google workspace",
                    "atlassian", "figma", "notion", "slack", "canva", "adobe", "jetbrains", "cursor", "claude", "chatgpt", "domain", "hosting", "saas")),
            Map.entry("insurance", words("insurance", "lic", "policy", "premium", "assurance", "star health", "hdfc ergo", "icici lombard")),
            Map.entry("education", words("school", "college", "tuition", "academy", "university", "fees", "coaching")),
            Map.entry("home", words("rent", "maintenance", "society", "apartment", "association", "housing")),
            Map.entry("tax", words("gst", "income tax", "tds", "property tax", "corporation", "municipal")),
            Map.entry("entertainment", words("netflix", "hotstar", "prime video", "spotify", "youtube", "zee5", "sonyliv", "tata play", "dish tv", "sun direct")),
            Map.entry("shopping", words("amazon", "flipkart", "myntra", "swiggy", "zomato", "bigbasket", "blinkit", "zepto")));

    private final JdbcTemplate jdbc;
    private final FxRates fx;
    private final MailActions actions;

    public BillSummary(JdbcTemplate jdbc, FxRates fx, MailActions actions) {
        this.jdbc = jdbc;
        this.fx = fx;
        this.actions = actions;
    }

    private static Pattern words(String... ws) {
        StringBuilder b = new StringBuilder("\\b(");
        for (int i = 0; i < ws.length; i++) b.append(i == 0 ? "" : "|").append(Pattern.quote(ws[i]));
        return Pattern.compile(b.append(")\\b").toString(), Pattern.CASE_INSENSITIVE);
    }

    /** The kind of spending a biller's bills are, from its name: "ACT Fibernet" is internet. */
    public static String guess(String vendor) {
        if (vendor == null) return "other";
        for (Map.Entry<String, Pattern> e : GUESS) if (e.getValue().matcher(vendor).find()) return e.getKey();
        return "other";
    }

    // ── the month ────────────────────────────────────────────

    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestAttribute long userId, @RequestParam(required = false) String month) {
        YearMonth ym = month(month);
        List<Bill> all = bills(userId, ym.minusMonths(5).atDay(1), ym.atEndOfMonth());

        List<Bill> these = all.stream().filter(b -> YearMonth.from(b.on()).equals(ym)).toList();
        Map<String, BigDecimal> byCat = new LinkedHashMap<>();
        Map<String, Integer> nByCat = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        int unpriced = 0;
        BigDecimal cgst = BigDecimal.ZERO, sgst = BigDecimal.ZERO, igst = BigDecimal.ZERO;
        int gstBills = 0;
        for (Bill b : these) {
            if (b.inr() == null) {
                unpriced++;
                continue;
            }
            total = total.add(b.inr());
            byCat.merge(b.category(), b.inr(), BigDecimal::add);
            nByCat.merge(b.category(), 1, Integer::sum);
            if (b.gstin() != null) {
                gstBills++;
                cgst = cgst.add(nz(b.cgst()));
                sgst = sgst.add(nz(b.sgst()));
                igst = igst.add(nz(b.igst()));
            }
        }
        List<Map<String, Object>> cats = new ArrayList<>();
        byCat.entrySet().stream().sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed()).forEach(e -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("category", e.getKey());
            c.put("total", e.getValue());
            c.put("count", nByCat.get(e.getKey()));
            cats.add(c);
        });
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            YearMonth m = ym.minusMonths(i);
            BigDecimal t = all.stream().filter(b -> YearMonth.from(b.on()).equals(m) && b.inr() != null).map(Bill::inr).reduce(BigDecimal.ZERO, BigDecimal::add);
            long n = all.stream().filter(b -> YearMonth.from(b.on()).equals(m)).count();
            trend.add(Map.of("month", m.toString(), "total", t, "count", n));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", ym.toString());
        out.put("total", total);
        out.put("count", these.size());
        out.put("unpriced", unpriced);
        out.put("previousTotal", trend.get(4).get("total"));
        out.put("byCategory", cats);
        out.put("trend", trend);
        out.put("gst", Map.of("bills", gstBills, "cgst", cgst, "sgst", sgst, "igst", igst, "total", cgst.add(sgst).add(igst)));
        out.put("categories", CATEGORIES);
        return out;
    }

    // ── the GST pack ─────────────────────────────────────────

    @GetMapping("/gst-pack")
    public ResponseEntity<byte[]> gstPack(@RequestAttribute long userId, @RequestParam(required = false) String month) {
        YearMonth ym = month(month);
        List<Bill> these = bills(userId, ym.atDay(1), ym.atEndOfMonth());
        if (these.isEmpty()) throw ApiException.notFound("Bills for " + ym);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("bills-" + ym + ".csv"));
            zip.write(csv(these).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            int files = 0;
            long size = 0;
            Set<String> names = new HashSet<>();
            for (Bill b : these) {
                if (b.gstin() == null || b.messageId() == null || files >= PACK_MAX_FILES) continue;
                try {
                    MessageRepo.Row m = actions.owned(userId, b.messageId());
                    List<Map<String, Object>> atts = m.attachments() == null ? List.of() : m.attachments();
                    for (int i = 0; i < atts.size() && files < PACK_MAX_FILES; i++) {
                        String n = Objects.toString(atts.get(i).get("name"), "");
                        if (!n.toLowerCase(Locale.ROOT).endsWith(".pdf")) continue;
                        MailActions.Download d = actions.attachment(userId, b.messageId(), i);
                        if (size + d.bytes().length > PACK_MAX_BYTES) break;
                        String base = "invoices/" + b.on() + " " + safe(b.vendor()) + (b.invoiceNo() == null ? "" : " " + safe(b.invoiceNo()));
                        String name = base + ".pdf";
                        for (int k = 2; !names.add(name); k++) name = base + " (" + k + ").pdf";
                        zip.putNextEntry(new ZipEntry(name));
                        zip.write(d.bytes());
                        zip.closeEntry();
                        files++;
                        size += d.bytes().length;
                    }
                } catch (Exception e) {
                    log.debug("Invoice of bill {} left out of the GST pack: {}", b.id(), e.toString());
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        String file = "bills-" + ym + ".zip";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build().toString())
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(bytes.toByteArray());
    }

    static String csv(List<Bill> bills) {
        StringBuilder s = new StringBuilder("﻿");  // Excel opens it as UTF-8 (₹, Tamil names)
        s.append("Invoice date,Vendor,Vendor GSTIN,Invoice no,Account,Kind of spending,Currency,Amount,Amount in INR,Taxable value,CGST,SGST,IGST,Status,Paid on\r\n");
        for (Bill b : bills) {
            BigDecimal tax = nz(b.cgst()).add(nz(b.sgst())).add(nz(b.igst()));
            BigDecimal taxable = b.gstin() == null || tax.signum() == 0 || !"INR".equals(b.currency()) ? null : b.amount().subtract(tax);
            s.append(String.join(",", List.of(
                    b.on().toString(), cell(b.vendor()), cell(b.gstin()), cell(b.invoiceNo()), cell(b.account()), cell(b.category()),
                    cell(b.currency()), plain(b.amount()), plain(b.inr()), plain(taxable), plain(b.cgst()), plain(b.sgst()), plain(b.igst()),
                    b.paid() ? "paid" : "due", b.paidOn() == null ? "" : b.paidOn().toString()))).append("\r\n");
        }
        return s.toString();
    }

    // ── reading bills ────────────────────────────────────────

    record Bill(long id, Long messageId, String vendor, String gstin, String invoiceNo, String account, String category,
                LocalDate on, BigDecimal amount, String currency, BigDecimal inr, BigDecimal cgst, BigDecimal sgst, BigDecimal igst,
                boolean paid, LocalDate paidOn) {}

    private List<Bill> bills(long userId, LocalDate from, LocalDate to) {
        List<Bill> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT b.*, m.received_at AS mail_at FROM bills b LEFT JOIN messages m ON m.id = b.message_id " +
                        "WHERE b.user_id = ? AND b.kind IN ('bill', 'receipt') AND b.amount IS NOT NULL", userId)) {
            LocalDate on = r.get("invoice_date") != null ? day(r.get("invoice_date"))
                    : r.get("mail_at") != null ? at(r.get("mail_at")) : r.get("due_date") != null ? day(r.get("due_date")) : at(r.get("created_at"));
            if (on == null || on.isBefore(from) || on.isAfter(to)) continue;
            BigDecimal amount = (BigDecimal) r.get("amount");
            String currency = Objects.toString(r.get("currency"), "INR");
            BigDecimal inr = "INR".equals(currency) ? amount
                    : r.get("paid_inr") != null ? (BigDecimal) r.get("paid_inr") : fx.inr(amount, currency);
            String cat = r.get("category") != null ? r.get("category").toString() : guess(Objects.toString(r.get("vendor"), null));
            String account = r.get("account_name") != null ? r.get("account_name").toString() : (String) r.get("customer_ref");
            out.add(new Bill(((Number) r.get("id")).longValue(), r.get("message_id") == null ? null : ((Number) r.get("message_id")).longValue(),
                    Objects.toString(r.get("vendor"), ""), (String) r.get("vendor_gstin"), (String) r.get("invoice_no"), account, cat,
                    on, amount, currency, inr == null ? null : inr.setScale(2, RoundingMode.HALF_UP),
                    (BigDecimal) r.get("cgst"), (BigDecimal) r.get("sgst"), (BigDecimal) r.get("igst"),
                    "paid".equals(r.get("status")), r.get("paid_at") == null ? null : at(r.get("paid_at"))));
        }
        out.sort(Comparator.comparing(Bill::on).thenComparing(Bill::vendor));
        return out;
    }

    private static YearMonth month(String m) {
        if (m == null || m.isBlank()) return YearMonth.now(BillService.INDIA);
        try {
            return YearMonth.parse(m.trim());
        } catch (Exception e) {
            throw ApiException.bad("A month looks like 2026-11.");
        }
    }

    private static LocalDate at(Object o) {
        if (o == null) return null;
        if (o instanceof OffsetDateTime t) return t.atZoneSameInstant(BillService.INDIA).toLocalDate();
        if (o instanceof java.sql.Timestamp t) return t.toInstant().atZone(BillService.INDIA).toLocalDate();
        try {
            return OffsetDateTime.parse(o.toString()).atZoneSameInstant(BillService.INDIA).toLocalDate();
        } catch (Exception e) {
            return day(o);
        }
    }

    private static LocalDate day(Object o) {
        try {
            return o == null ? null : LocalDate.parse(o.toString().substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** A CSV cell: quoted when needed, and never read by a spreadsheet as a formula. */
    private static String cell(String v) {
        if (v == null) return "";
        String s = v.matches("^[=+\\-@\\t\\r].*") ? "'" + v : v;
        return s.matches(".*[\",\\r\\n].*") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    private static String safe(String s) {
        String t = s.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
        return t.length() > 60 ? t.substring(0, 60) : t;
    }
}
