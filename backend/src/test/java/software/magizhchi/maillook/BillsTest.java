package software.magizhchi.maillook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import software.magizhchi.maillook.bills.BillDetector;
import software.magizhchi.maillook.bills.BillService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Bills from your mail, against a stand-in Magizhchi Data: the free word filter picks the likely
 * bills, Data reads them (free while the month's allowance lasts, then on credits), the same invoice
 * twice is one bill, a receipt is paid, and the due-date alert rings once.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${MAILLOOK_TEST_DB_URL:jdbc:h2:mem:bills;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${MAILLOOK_TEST_DB_USER:sa}",
        "spring.datasource.password=${MAILLOOK_TEST_DB_PASSWORD:}",
        "maillook.sync.interval-ms=3600000",
        "maillook.ai.api-key=",
        "magizhchi.apps.pa.api=",
        "magizhchi.apps.data.secret=data-secret-for-tests",
        "maillook.bills.free-per-month=5",
        "maillook.bills.timer=false"})
@AutoConfigureMockMvc
class BillsTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("tiru@magizhchi.test", "tiru@magizhchi.test", "pw"));

    static final String ACCOUNT_ID = "2b7c1f0e-3333-4d3a-8b44-000000000031";
    static final String SECRET = "data-secret-for-tests";
    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final LocalDate DUE = LocalDate.now(INDIA).plusDays(2);
    static final List<String> reads = new CopyOnWriteArrayList<>();
    static final HttpServer data = startFakeData();

    @DynamicPropertySource
    static void dataUrl(DynamicPropertyRegistry r) {
        r.add("magizhchi.apps.data.api", () -> "http://localhost:" + data.getAddress().getPort());
        r.add("maillook.fx.url", () -> "http://localhost:" + data.getAddress().getPort() + "/fx");
    }

    @AfterAll
    static void stop() {
        data.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired BillService bills;
    @Autowired JdbcTemplate jdbc;

    @Test
    void keepsRealPaymentPagesNotMailTrackers() {
        assertThat(BillService.usablePayLink("https://www.actcorp.in/quick-pay")).isEqualTo("https://www.actcorp.in/quick-pay");
        assertThat(BillService.usablePayLink("https://c.gle/ANiao5oAbc")).isNull();
        assertThat(BillService.usablePayLink("https://click.mail.example.com/x")).isNull();
        assertThat(BillService.usablePayLink("http://www.actcorp.in/pay")).isNull();
    }

    @Test
    void findsTheAccountNameInTheMail() {
        assertThat(BillService.accountNameIn("Bill Amount: Rs. 6010.92\nBill Category: Broadband\nAccount Name: alwarpuram\nDue Date: Nov 15, 2026")).isEqualTo("alwarpuram");
        assertThat(BillService.accountNameIn("Account Name\nalwarpuram\nDue Date")).isEqualTo("alwarpuram");
        assertThat(BillService.accountNameIn("Consumer name: R. Kumar Due date 19 Nov")).isEqualTo("R. Kumar");
        assertThat(BillService.accountNameIn("Your account number 1146")).isNull();
    }

    @Test
    void theWordFilterPicksLikelyBills() {
        assertThat(BillDetector.looksLikeBill("Your ACT Fibernet bill for November", "Amount due Rs. 1,180", List.of(), null)).isTrue();
        assertThat(BillDetector.looksLikeBill("Tax Invoice INV-77", "", List.of("INV-77.pdf"), null)).isTrue();
        assertThat(BillDetector.looksLikeBill("Fwd: documents", "Please find attached", List.of("invoice_oct.pdf"), null)).isTrue();
        assertThat(BillDetector.looksLikeBill("Hello", "The total due is ₹4,500, payable by Friday.", List.of(), null)).isTrue();
        assertThat(BillDetector.looksLikeBill("Deployment crashed for app", "Bill estimate $5", List.of(), null)).isFalse();
        assertThat(BillDetector.looksLikeBill("Debit Alert From SIB", "Rs 1180 has been debited from your account", List.of(), null)).isFalse();
        assertThat(BillDetector.looksLikeBill("Weekend sale: 50% off", "Bill shoes from Rs 999", List.of(), null)).isFalse();
        assertThat(BillDetector.looksLikeBill("Your OTP", "OTP for invoice payment of Rs 10 is 1234", List.of(), null)).isFalse();
        assertThat(BillDetector.looksLikeBill("Monthly update", "", List.of(), Boolean.TRUE)).isTrue();
        assertThat(BillDetector.looksLikeBill("Your bill", "Amount due Rs 10", List.of(), Boolean.FALSE)).isFalse();
    }

    @Test
    void readsBillsFromMailAndRemindsBeforeTheyAreDue() throws Exception {
        deliver("ACT Fibernet <billing@actcorp.example>", "Your ACT Fibernet bill for November",
                "Dear customer, your bill amount Rs. 1,180 is due on " + DUE + ". Account 1023.", List.of());
        deliver("Railway <alerts@railway.example>", "Deployment crashed for lovely-amazement", "Your deploy failed.", List.of());
        deliver("Academy Supplies <accounts@supplies.example>", "Payment receipt", "Thank you, payment received.",
                List.of(new Att("invoice.pdf", "application/pdf", "%PDF-1.4 receipt R-9 Rs 4,500 paid")));

        String token = call(post("/api/auth/sso").contentType(MediaType.APPLICATION_JSON).content(
                "{\"idToken\":\"" + TestIds.idToken(ACCOUNT_ID, "tiru@magizhchi.test", "Tiru") + "\"}"), null, 200).path("token").asText();
        call(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(
                "{\"email\":\"tiru@magizhchi.test\",\"password\":\"pw\",\"imapHost\":\"localhost\",\"imapPort\":3143,\"smtpHost\":\"localhost\",\"smtpPort\":3025}"),
                token, 200);
        long actMail = waitForMessage(token, "Your ACT Fibernet bill for November");
        waitForMessage(token, "Payment receipt");

        // Off until turned on; Data says the person has an account with credits.
        JsonNode setup = call(get("/api/bills/setup"), token, 200);
        assertThat(setup.path("configured").asBoolean()).isTrue();
        assertThat(setup.path("on").asBoolean()).isFalse();
        assertThat(setup.path("linked").asBoolean()).isTrue();
        assertThat(setup.path("balance").asInt()).isEqualTo(42);
        assertThat(setup.path("freeLeft").asInt()).isEqualTo(5);

        // The estimate only counts: two of three mails look like bills, all within the free allowance.
        JsonNode est = call(get("/api/bills/scan?days=90"), token, 200);
        assertThat(est.path("bills").asInt()).isEqualTo(2);
        assertThat(est.path("free").asInt()).isEqualTo(2);
        assertThat(reads).isEmpty();

        call(put("/api/bills/setup").contentType(MediaType.APPLICATION_JSON).content("{\"on\":true}"), token, 200);
        assertThat(call(post("/api/bills/scan").contentType(MediaType.APPLICATION_JSON).content("{\"days\":90}"), token, 200)
                .path("queued").asInt()).isEqualTo(2);
        assertThat(bills.work(10)).isEqualTo(2);

        // Data got the mail text, the PDF, the secret, and was told these are free reads.
        assertThat(reads).hasSize(2);
        assertThat(reads).allMatch(r -> r.startsWith(SECRET + "|") && r.contains("tiru@magizhchi.test") && free(r));
        assertThat(reads).anyMatch(r -> r.contains("filename=\"invoice.pdf\"") && r.contains("%PDF-1.4 receipt"));
        assertThat(reads).noneMatch(r -> r.contains("Deployment crashed"));

        JsonNode list = call(get("/api/bills"), token, 200).path("bills");
        assertThat(list).hasSize(2);
        JsonNode act = find(list, "ACT Fibernet");
        assertThat(act.path("status").asText()).isEqualTo("due");
        assertThat(act.path("dueDate").asText()).isEqualTo(DUE.toString());
        assertThat(act.path("amount").decimalValue()).isEqualByComparingTo("1180");
        assertThat(act.path("invoiceNo").asText()).isEqualTo("INV-77");
        assertThat(act.path("confidence").path("dueDate").asDouble()).isEqualTo(0.6);
        assertThat(find(list, "Academy Supplies").path("status").asText()).isEqualTo("paid");

        // The card on the mail.
        JsonNode card = call(get("/api/messages/" + actMail + "/bill"), token, 200);
        assertThat(card.path("on").asBoolean()).isTrue();
        assertThat(card.path("bill").path("id").asLong()).isEqualTo(act.path("id").asLong());

        // A reminder mail for the same invoice joins the bill instead of making a second one.
        deliver("ACT Fibernet <billing@actcorp.example>", "Reminder: ACT Fibernet bill",
                "Reminder: your bill of Rs. 1,180 is due on " + DUE + ".", List.of());
        call(post("/api/sync"), token, 202);
        long reminderMail = waitForMessage(token, "Reminder: ACT Fibernet bill");
        bills.work(10);
        assertThat(call(get("/api/bills"), token, 200).path("bills")).hasSize(2);
        assertThat(call(get("/api/messages/" + reminderMail + "/bill"), token, 200).path("bill").path("id").asLong())
                .isEqualTo(act.path("id").asLong());

        // The same bill told again by Google Pay, under another name and a later date: it joins the
        // ACT bill and adds what that mail knew (the account's name, its own Pay now button).
        deliverHtml("Google Pay <payments-noreply@google.example>", "New bill from ACT Broadband",
                "<p>New bill from ACT Broadband. Account Name: alwarpuram. Amount Rs. 1,180.</p>"
                        + "<a href=\"https://pay.example/act?b=1&amp;c=2\"><span>Pay Now</span></a> <a href=\"https://x.example/u\">Unsubscribe</a>");
        deliver("Railway <billing@railway.example>", "Your Railway invoice",
                "Invoice in_1Q9. Amount due USD 20.00, charged to your card.", List.of());
        call(post("/api/sync"), token, 202);
        long gpayMail = waitForMessage(token, "New bill from ACT Broadband");
        waitForMessage(token, "Your Railway invoice");
        bills.work(10);
        JsonNode three = call(get("/api/bills"), token, 200).path("bills");
        assertThat(three).hasSize(3);
        JsonNode joined = find(three, "ACT Fibernet");
        assertThat(joined.path("id").asLong()).isEqualTo(act.path("id").asLong());
        assertThat(joined.path("dueDate").asText()).isEqualTo(DUE.toString());
        assertThat(joined.path("accountName").asText()).isEqualTo("alwarpuram");
        assertThat(joined.path("payLink").asText()).isEqualTo("https://pay.example/act?b=1&c=2");
        assertThat(joined.has("amountInr")).isFalse();
        assertThat(call(get("/api/messages/" + gpayMail + "/bill"), token, 200).path("bill").path("id").asLong())
                .isEqualTo(act.path("id").asLong());
        // Set by hand: the pay link is checked, and the biller's other bills still to pay get it too.
        call(patch("/api/bills/" + usd(three)).contentType(MediaType.APPLICATION_JSON).content("{\"payLink\":\"http://plain.example\"}"), token, 400);
        call(patch("/api/bills/" + usd(three)).contentType(MediaType.APPLICATION_JSON).content("{\"upiId\":\"not a upi\"}"), token, 400);
        // A dollar bill also says what it comes to in rupees.
        JsonNode usd = find(three, "Railway");
        assertThat(usd.path("currency").asText()).isEqualTo("USD");
        assertThat(usd.path("amountInr").decimalValue()).isEqualByComparingTo("1878.40");
        assertThat(usd.path("fxDate").asText()).isEqualTo("2026-11-06");

        // Bills read before the joining rule: folded together on the next pass, the older one kept.
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'tiru@magizhchi.test'", Long.class);
        jdbc.update("INSERT INTO bills (user_id, kind, vendor, vendor_key, due_date, amount, currency, status) VALUES (?, 'bill', 'BSNL Landline', 'bsnl landline', ?, 640, 'INR', 'due')", userId, DUE.plusDays(1));
        jdbc.update("INSERT INTO bills (user_id, kind, vendor, vendor_key, due_date, amount, currency, status, account_name) VALUES (?, 'bill', 'BSNL', 'bsnl', ?, 640, 'INR', 'due', 'home')", userId, DUE.plusDays(4));
        assertThat(bills.mergeDuplicates()).isEqualTo(1);
        JsonNode bsnl = find(call(get("/api/bills"), token, 200).path("bills"), "BSNL Landline");
        assertThat(bsnl.path("accountName").asText()).isEqualTo("home");
        assertThat(bsnl.path("dueDate").asText()).isEqualTo(DUE.plusDays(1).toString());
        // A bill with no way to pay borrows the biller's link; one the person sets is shared.
        jdbc.update("INSERT INTO bills (user_id, kind, vendor, vendor_key, due_date, amount, currency, status) VALUES (?, 'bill', 'ACT Fibernet', 'act fibernet', ?, 2271.50, 'INR', 'due')", userId, DUE);
        long small = jdbc.queryForObject("SELECT id FROM bills WHERE amount = 2271.50", Long.class);
        assertThat(bills.fillPayWays()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT pay_link FROM bills WHERE id = ?", String.class, small)).isEqualTo("https://pay.example/act?b=1&c=2");
        long bsnlId = bsnl.path("id").asLong();
        jdbc.update("INSERT INTO bills (user_id, kind, vendor, vendor_key, due_date, amount, currency, status) VALUES (?, 'bill', 'BSNL Mobile', 'bsnl mobile', ?, 299, 'INR', 'due')", userId, DUE.plusDays(20));
        JsonNode set = call(patch("/api/bills/" + bsnlId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"payLink\":\"https://portal.bsnl.example/pay\",\"upiId\":\" bsnl@sbi \"}"), token, 200);
        assertThat(set.path("upiId").asText()).isEqualTo("bsnl@sbi");
        assertThat(jdbc.queryForObject("SELECT upi_id FROM bills WHERE vendor_key = 'bsnl mobile'", String.class)).isEqualTo("bsnl@sbi");
        jdbc.update("DELETE FROM bills WHERE vendor_key IN ('bsnl landline', 'bsnl mobile') OR id = ?", small);

        // The free allowance (5 a month here) is used up: the next bill goes on credits, and Data says
        // there are none. It waits, and doesn't ask Data again on every pass.
        deliver("Airtel <bills@airtel.example>", "Your Airtel bill", "Amount due Rs. 499 by the 20th.", List.of());
        call(post("/api/sync"), token, 202);
        waitForMessage(token, "Your Airtel bill");
        int before = reads.size();
        bills.work(10);
        assertThat(reads).hasSize(before + 1);
        assertThat(free(reads.get(reads.size() - 1))).isFalse();
        assertThat(call(get("/api/bills/setup"), token, 200).path("waiting").path("no_credits").asInt()).isEqualTo(1);
        bills.work(10);
        assertThat(reads).hasSize(before + 1);

        // Two days before, in the day-time in India: one alert, not two.
        Instant morning = LocalDate.now(INDIA).atTime(10, 0).atZone(INDIA).toInstant();
        assertThat(bills.remind(LocalDate.now(INDIA).atTime(23, 0).atZone(INDIA).toInstant())).isZero();
        assertThat(bills.remind(morning)).isEqualTo(1);
        assertThat(bills.remind(morning)).isZero();
        assertThat(jdbc.queryForList("SELECT title FROM alerts WHERE kind = 'reminder'", String.class))
                .anyMatch(t -> t.startsWith("ACT Fibernet bill due on"));

        // Fix the amount, mark it paid.
        JsonNode fixed = call(patch("/api/bills/" + act.path("id").asLong()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"1,200\",\"status\":\"paid\"}"), token, 200);
        assertThat(fixed.path("amount").decimalValue()).isEqualByComparingTo("1200");
        assertThat(fixed.path("status").asText()).isEqualTo("paid");
        assertThat(fixed.path("confidence").size()).isZero();

        // Other people can't touch it.
        String other = call(post("/api/auth/sso").contentType(MediaType.APPLICATION_JSON).content(
                "{\"idToken\":\"" + TestIds.idToken("2b7c1f0e-3333-4d3a-8b44-000000000032", "someone@magizhchi.test", "Someone") + "\"}"), null, 200).path("token").asText();
        call(patch("/api/bills/" + act.path("id").asLong()).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"due\"}"), other, 404);
        call(get("/api/messages/" + actMail + "/bill"), other, 404);

        // Not a bill, and never from this sender again.
        long receipt = find(call(get("/api/bills"), token, 200).path("bills"), "Academy Supplies").path("id").asLong();
        call(post("/api/bills/" + receipt + "/not-a-bill").contentType(MediaType.APPLICATION_JSON).content("{\"never\":true}"), token, 200);
        assertThat(call(get("/api/bills"), token, 200).path("bills")).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT is_biller FROM billers WHERE from_email = 'accounts@supplies.example'", Boolean.class)).isFalse();
    }

    // ── a stand-in Magizhchi Data ──────────────────────────────

    static boolean free(String body) {
        return body.matches("(?s).*name=\"free\"\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\ntrue\r\n.*");
    }

    static HttpServer startFakeData() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            s.createContext("/", BillsTest::handle);
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String secret = ex.getRequestHeaders().getFirst("X-Integration-Secret");
        if (path.equals("/fx")) {
            reply(ex, 200, ex.getRequestURI().getQuery().contains("base=USD")
                    ? "{\"amount\":1.0,\"base\":\"USD\",\"date\":\"2026-11-06\",\"rates\":{\"INR\":93.92}}" : "{\"rates\":{}}");
            return;
        }
        if (!SECRET.equals(secret)) {
            reply(ex, 403, "{\"message\":\"Bad integration secret.\"}");
            return;
        }
        if (path.equals("/api/v1/integration/maillook/account")) {
            reply(ex, 200, "{\"costPerBill\":1,\"linked\":true,\"balance\":42}");
            return;
        }
        if (path.equals("/api/v1/integration/maillook/bill")) {
            reads.add(secret + "|" + body);
            if (!free(body)) {
                reply(ex, 402, "{\"code\":\"NO_CREDITS\",\"message\":\"Not enough credits\"}");
                return;
            }
            String bill;
            if (body.contains("ACT Broadband")) {
                bill = "{\"kind\":\"bill\",\"vendor\":\"ACT Broadband\",\"accountName\":\"alwarpuram\",\"dueDate\":\"" + DUE.plusDays(6) + "\","
                        + "\"amount\":1180,\"currency\":\"INR\",\"payLink\":null,\"autopay\":false,\"alreadyPaid\":false,\"confidence\":{}}";
            } else if (body.contains("in_1Q9")) {
                bill = "{\"kind\":\"bill\",\"vendor\":\"Railway\",\"invoiceNo\":\"in_1Q9\",\"dueDate\":\"" + DUE.plusDays(3) + "\","
                        + "\"amount\":20,\"currency\":\"USD\",\"autopay\":true,\"alreadyPaid\":false,\"confidence\":{}}";
            } else if (body.contains("ACT Fibernet")) {
                bill = "{\"kind\":\"bill\",\"vendor\":\"ACT Fibernet\",\"invoiceNo\":\"INV-77\",\"dueDate\":\"" + DUE + "\","
                        + "\"amount\":1180,\"currency\":\"INR\",\"autopay\":false,\"alreadyPaid\":false,"
                        + "\"confidence\":{\"amount\":0.98,\"dueDate\":0.6}}";
            } else if (body.contains("filename=\"invoice.pdf\"")) {
                bill = "{\"kind\":\"receipt\",\"vendor\":\"Academy Supplies\",\"invoiceNo\":\"R-9\",\"amount\":4500,"
                        + "\"currency\":\"INR\",\"autopay\":false,\"alreadyPaid\":true,\"confidence\":{}}";
            } else {
                bill = "{\"kind\":\"other\",\"amount\":null,\"confidence\":{}}";
            }
            reply(ex, 200, "{\"bill\":" + bill + ",\"source\":\"text\",\"charged\":0,\"cached\":false,\"balance\":42}");
            return;
        }
        reply(ex, 404, "{\"message\":\"not found\"}");
    }

    static void reply(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    // ── helpers ───────────────────────────────────────────────────

    record Att(String name, String type, String content) {}

    private static JsonNode find(JsonNode list, String vendor) {
        for (JsonNode b : list) if (vendor.equals(b.path("vendor").asText())) return b;
        throw new AssertionError("No bill from " + vendor + " in " + list);
    }

    private void deliver(String from, String subject, String text, List<Att> files) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress(from));
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse("tiru@magizhchi.test"));
        m.setSubject(subject);
        MimeMultipart mp = new MimeMultipart();
        MimeBodyPart t = new MimeBodyPart();
        t.setText(text, "UTF-8");
        mp.addBodyPart(t);
        for (Att a : files) {
            MimeBodyPart p = new MimeBodyPart();
            p.setDataHandler(new DataHandler(new ByteArrayDataSource(a.content().getBytes(StandardCharsets.UTF_8), a.type())));
            p.setFileName(a.name());
            p.setDisposition(MimeBodyPart.ATTACHMENT);
            mp.addBodyPart(p);
        }
        m.setContent(mp);
        m.setSentDate(Date.from(Instant.now()));
        m.saveChanges();
        greenMail.getUserManager().getUserByEmail("tiru@magizhchi.test").deliver(m);
    }

    private static long usd(JsonNode list) {
        return find(list, "Railway").path("id").asLong();
    }

    private void deliverHtml(String from, String subject, String html) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress(from));
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse("tiru@magizhchi.test"));
        m.setSubject(subject);
        m.setContent(html, "text/html; charset=UTF-8");
        m.setSentDate(Date.from(Instant.now()));
        m.saveChanges();
        greenMail.getUserManager().getUserByEmail("tiru@magizhchi.test").deliver(m);
    }

    private long waitForMessage(String token, String subject) throws Exception {
        for (int i = 0; i < 100; i++) {
            for (JsonNode it : call(get("/api/messages"), token, 200).path("items")) {
                if (subject.equals(it.path("subject").asText())) return it.path("id").asLong();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Mail never synced: " + subject);
    }

    private JsonNode call(MockHttpServletRequestBuilder req, String token, int status) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        var res = mvc.perform(req).andReturn().getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(status);
        String body = res.getContentAsString(StandardCharsets.UTF_8);
        return body.isEmpty() ? json.createObjectNode() : json.readTree(body);
    }
}
