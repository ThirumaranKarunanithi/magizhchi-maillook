package software.magizhchi.maillook.forms;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import software.magizhchi.maillook.account.Account;
import software.magizhchi.maillook.account.AccountRepo;
import software.magizhchi.maillook.common.ApiException;
import software.magizhchi.maillook.mail.MailActions;
import software.magizhchi.maillook.mail.MessageParser;
import software.magizhchi.maillook.mail.MessageRepo;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Magizhchi Forms in MailLook: approvals and new responses (the Forms page and its badge), forms and
 * polls to put in a mail, who filled what, and turning mail into forms and responses. Everything is
 * asked of Forms as the person's Magizhchi ID email, so they only ever see their own forms.
 */
@RestController
@RequestMapping("/api/forms")
public class FormsController {

    static final Set<String> RESPONSES = Set.of("lane", "daily", "off");

    private final FormsClient forms;
    private final FormMail mail;
    private final MailActions actions;
    private final AccountRepo accounts;
    private final JdbcTemplate jdbc;
    private final FormsWatch watch;

    public FormsController(FormsClient forms, FormMail mail, MailActions actions, AccountRepo accounts, JdbcTemplate jdbc, FormsWatch watch) {
        this.watch = watch;
        this.forms = forms;
        this.mail = mail;
        this.actions = actions;
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    String email(long userId) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private void need() {
        if (!forms.configured()) throw ApiException.notFound("Magizhchi Forms");
    }

    /** Forms' answer as is, or its message as ours. */
    private JsonNode ok(FormsClient.Reply r) {
        if (r.ok()) return r.json();
        String said = r.json() == null ? null : r.json().path("error").asText(r.json().path("message").asText(null));
        HttpStatus s = r.code() == 503 || r.code() >= 500 ? HttpStatus.BAD_GATEWAY : HttpStatus.resolve(r.code());
        throw new ApiException(s == null ? HttpStatus.BAD_GATEWAY : s,
                said != null && !said.isBlank() ? said : r.code() == 503 ? "Magizhchi Forms didn't answer. Try again in a minute." : "Magizhchi Forms said no (" + r.code() + ").");
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestAttribute long userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", forms.configured());
        m.put("web", forms.web());
        Map<String, Object> u = jdbc.queryForMap("SELECT forms_responses, forms_send_account, forms_linked FROM users WHERE id = ?", userId);
        m.put("responses", u.get("forms_responses"));
        m.put("sendAccountId", u.get("forms_send_account"));
        m.put("linked", u.get("forms_linked"));
        m.put("accounts", accounts.forUser(userId).stream().map(a -> Map.of("id", a.id(), "email", a.email())).toList());
        return m;
    }

    public record Settings(String responses, Long sendAccountId, Boolean sendFromMailbox) {}

    @PutMapping("/settings")
    public Map<String, Object> settings(@RequestAttribute long userId, @RequestBody Settings s) {
        if (s.responses() != null) {
            if (!RESPONSES.contains(s.responses())) throw ApiException.bad("Choose lane, daily or off.");
            jdbc.update("UPDATE users SET forms_responses = ? WHERE id = ?", s.responses(), userId);
        }
        if (Boolean.FALSE.equals(s.sendFromMailbox())) {
            jdbc.update("UPDATE users SET forms_send_account = NULL WHERE id = ?", userId);
        } else if (s.sendAccountId() != null) {
            accounts.find(s.sendAccountId()).filter(a -> a.userId() == userId).orElseThrow(() -> ApiException.notFound("Mailbox"));
            jdbc.update("UPDATE users SET forms_send_account = ? WHERE id = ?", s.sendAccountId(), userId);
        }
        return status(userId);
    }

    /** Approvals waiting, new responses (since {@code since}, default a week) and the person's forms. */
    @GetMapping("/overview")
    public JsonNode overview(@RequestAttribute long userId, @RequestParam(required = false) String since) {
        need();
        FormsClient.Reply r = forms.get("/overview", sinceQuery(email(userId), since));
        if (r.code() == 404 && r.json() != null) {
            jdbc.update("UPDATE users SET forms_linked = FALSE WHERE id = ?", userId);
            throw ApiException.notFound("Magizhchi Forms account");
        }
        JsonNode j = ok(r);
        jdbc.update("UPDATE users SET forms_linked = TRUE WHERE id = ?", userId);
        return j;
    }

    static Map<String, String> sinceQuery(String email, String since) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("email", email);
        if (since != null && !since.isBlank()) {
            try {
                q.put("since", Instant.parse(since).toString());
            } catch (Exception e) {
                throw ApiException.bad("That time couldn't be read.");
            }
        }
        return q;
    }

    public record Decision(String responseId, String decision, String comment) {}

    @PostMapping("/decision")
    public JsonNode decide(@RequestAttribute long userId, @RequestBody Decision d) {
        need();
        if (d.responseId() == null || !Set.of("approve", "reject", "comment").contains(String.valueOf(d.decision())))
            throw ApiException.bad("Approve, reject or comment.");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email(userId));
        body.put("responseId", d.responseId());
        body.put("decision", d.decision());
        body.put("comment", d.comment());
        JsonNode j = ok(forms.post("/decision", body));
        jdbc.update("DELETE FROM alerts WHERE user_id = ? AND tag = ?", userId, "maillook-forms-approval-" + d.responseId());
        return j;
    }

    @GetMapping("/list")
    public JsonNode list(@RequestAttribute long userId) {
        need();
        return ok(forms.get("/forms", Map.of("email", email(userId))));
    }

    /** What this sender has filled of the person's forms (the person panel). */
    @GetMapping("/person")
    public JsonNode person(@RequestAttribute long userId, @RequestParam String who) {
        need();
        String e = MessageParser.emailOnly(who);
        if (e == null || !e.contains("@")) throw ApiException.bad("Not an email address.");
        return ok(forms.get("/person", Map.of("email", email(userId), "who", e)));
    }

    public record PollRequest(String question, List<String> choices) {}

    @PostMapping("/poll")
    public JsonNode poll(@RequestAttribute long userId, @RequestBody PollRequest p) {
        need();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email(userId));
        body.put("question", p.question());
        body.put("choices", p.choices());
        return ok(forms.post("/poll", body));
    }

    // ── sent forms ───────────────────────────────────────────────

    @GetMapping("/sent")
    public List<Map<String, Object>> sent(@RequestAttribute long userId) {
        return mail.sent(userId, 40).stream().map(FormsController::view).toList();
    }

    static Map<String, Object> view(FormMail.Sent s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("formId", s.formId());
        m.put("kind", s.kind());
        m.put("title", s.title());
        m.put("subject", s.subject());
        m.put("asked", s.recipients().size());
        m.put("filled", s.filled());
        m.put("sentAt", s.sentAt().toString());
        m.put("remindedAt", s.remindedAt() == null ? null : s.remindedAt().toString());
        m.put("messageId", s.messageIds().isEmpty() ? null : s.messageIds().get(0));
        return m;
    }

    /** Who has filled it and who hasn't (and, for a poll, the tally), live from Forms. */
    @GetMapping("/sent/{id}")
    public Map<String, Object> sentOne(@RequestAttribute long userId, @PathVariable long id) {
        need();
        return detail(userId, mail.find(userId, id).orElseThrow(() -> ApiException.notFound("Sent form")));
    }

    /** The form or poll a mail in Sent carried, if it carried one. */
    @GetMapping("/for-message/{messageId}")
    public Map<String, Object> forMessage(@RequestAttribute long userId, @PathVariable long messageId) {
        Optional<FormMail.Sent> s = mail.forMessage(userId, messageId);
        if (s.isEmpty() || !forms.configured()) return Map.of("found", false);
        Map<String, Object> m = detail(userId, s.get());
        m.put("found", true);
        return m;
    }

    private Map<String, Object> detail(long userId, FormMail.Sent s) {
        String me = email(userId);
        Map<String, JsonNode> filled = mail.filled(me, s);
        Map<String, Object> m = view(s);
        m.put("filled", filled.size());
        List<Map<String, Object>> people = new ArrayList<>();
        for (String e : s.recipients()) {
            JsonNode f = filled.get(e);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("email", e);
            p.put("filled", f != null);
            if (f != null) {
                p.put("at", f.path("at").asText(null));
                p.put("status", f.path("status").asText(null));
                p.put("responseId", f.path("responseId").asText(null));
            }
            people.add(p);
        }
        m.put("people", people);
        if ("poll".equals(s.kind())) {
            FormsClient.Reply t = forms.get("/poll-tally", Map.of("email", me, "formId", s.formId()));
            if (t.ok()) m.put("tally", t.json());
        }
        return m;
    }

    @PostMapping("/sent/{id}/remind")
    public Map<String, Object> remind(@RequestAttribute long userId, @PathVariable long id) {
        need();
        FormMail.Sent s = mail.find(userId, id).orElseThrow(() -> ApiException.notFound("Sent form"));
        int n = mail.remind(userId, email(userId), s);
        return Map.of("reminded", n);
    }

    // ── mail into forms ──────────────────────────────────────────

    public record BuildRequest(Long messageId, String prompt) {}

    /**
     * Forms' AI makes a form from what the mail (or the person's own words) asks for; MailLook then
     * offers to send it to everyone on the thread, with tracking.
     */
    @PostMapping("/build")
    public Map<String, Object> build(@RequestAttribute long userId, @RequestBody BuildRequest b) {
        need();
        String prompt = b.prompt() == null ? "" : b.prompt().trim();
        List<String> people = new ArrayList<>();
        String subject = null;
        Long accountId = null;
        if (b.messageId() != null) {
            MessageRepo.Row r = actions.owned(userId, b.messageId());
            subject = r.subject();
            accountId = r.accountId();
            String body = MessageParser.ownText(r.bodyText() == null ? "" : r.bodyText());
            if (body.length() > 4000) body = body.substring(0, 4000);
            prompt = (prompt.isEmpty() ? "Make a form to collect what this email asks people for." : prompt)
                    + "\n\nEmail subject: " + (subject == null ? "" : subject) + "\nEmail:\n" + body;
            Set<String> mine = new HashSet<>();
            accounts.forUser(userId).forEach(a -> mine.add(a.email().toLowerCase(Locale.ROOT)));
            LinkedHashSet<String> all = new LinkedHashSet<>();
            if (r.fromEmail() != null) all.add(r.fromEmail().toLowerCase(Locale.ROOT));
            for (List<String> l : Arrays.asList(r.to(), r.cc())) if (l != null) for (String a : l) {
                String e = MessageParser.emailOnly(a);
                if (e != null && e.contains("@")) all.add(e);
            }
            all.removeAll(mine);
            people.addAll(all);
        }
        if (prompt.length() < 8) throw ApiException.bad("Say what the form should collect.");
        JsonNode f = ok(forms.post("/build", Map.of("email", email(userId), "prompt", prompt)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("form", f);
        out.put("people", people);
        out.put("subject", subject);
        out.put("accountId", accountId);
        return out;
    }

    public record ReadRequest(long messageId, String formId, Integer attachment) {}

    /** The form's answers read from a mail (and one attached file, by Magizhchi Data), for the person to check. */
    @PostMapping("/read")
    public JsonNode read(@RequestAttribute long userId, @RequestBody ReadRequest req) {
        need();
        MessageRepo.Row r = actions.owned(userId, req.messageId());
        String text = "From: " + (r.fromName() == null ? "" : r.fromName() + " ") + "<" + r.fromEmail() + ">\nSubject: "
                + (r.subject() == null ? "" : r.subject()) + "\n\n" + MessageParser.ownText(r.bodyText() == null ? "" : r.bodyText());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email(userId));
        body.put("formId", req.formId());
        body.put("text", text);
        Integer idx = req.attachment();
        if (idx == null && r.attachments() != null) {
            for (Map<String, Object> a : r.attachments()) {
                String ct = String.valueOf(a.get("contentType"));
                long size = a.get("size") instanceof Number n ? n.longValue() : 0;
                if (List.of("application/pdf", "image/jpeg", "image/png", "image/webp").contains(ct) && size < 12_000_000) {
                    idx = ((Number) a.get("index")).intValue();
                    break;
                }
            }
        }
        if (idx != null) {
            MailActions.Download d = actions.attachment(userId, r.id(), idx);
            if (d.bytes().length <= 12_000_000) {
                body.put("fileName", d.name());
                body.put("contentType", d.contentType());
                body.put("fileBase64", Base64.getEncoder().encodeToString(d.bytes()));
            }
        }
        return ok(forms.post("/response/read", body));
    }

    public record SaveRequest(long messageId, String formId, Map<String, Object> answers) {}

    @PostMapping("/response")
    public JsonNode save(@RequestAttribute long userId, @RequestBody SaveRequest req) {
        need();
        MessageRepo.Row r = actions.owned(userId, req.messageId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email(userId));
        body.put("formId", req.formId());
        body.put("answers", req.answers() == null ? Map.of() : req.answers());
        body.put("from", (r.fromName() == null ? "" : r.fromName() + " ") + "<" + r.fromEmail() + ">");
        body.put("subject", r.subject());
        return ok(forms.post("/response", body));
    }

    // ── the badge ─────────────────────────────────────────────────

    /** Approvals waiting (the sidebar badge), from the last look; a look now when there's been none. */
    @GetMapping("/count")
    public Map<String, Object> count(@RequestAttribute long userId) {
        if (!forms.configured()) return Map.of("configured", false);
        var c = watch.counts(userId);
        if (c.isEmpty() || c.get().at().isBefore(Instant.now().minus(Duration.ofMinutes(15)))) {
            Boolean linked = jdbc.queryForObject("SELECT forms_linked FROM users WHERE id = ?", Boolean.class, userId);
            if (Boolean.FALSE.equals(linked)) return Map.of("configured", true, "linked", false);
            String tz = jdbc.queryForObject("SELECT tz FROM users WHERE id = ?", String.class, userId);
            watch.look(userId, email(userId), tz, "lane", Instant.now(), false);
            c = watch.counts(userId);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", true);
        m.put("linked", c.isPresent());
        m.put("approvals", c.map(FormsWatch.Counts::approvals).orElse(0));
        m.put("newResponses", c.map(FormsWatch.Counts::newResponses).orElse(0));
        return m;
    }

    static Instant dayAgo() {
        return Instant.now().minus(Duration.ofDays(1));
    }

    Optional<Account> sendAccount(long userId) {
        Long id = jdbc.queryForObject("SELECT forms_send_account FROM users WHERE id = ?", Long.class, userId);
        return id == null ? Optional.empty() : accounts.find(id).filter(a -> a.userId() == userId);
    }
}
