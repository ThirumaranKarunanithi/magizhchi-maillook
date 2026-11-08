package software.magizhchi.maillook.forms;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import software.magizhchi.maillook.account.Account;
import software.magizhchi.maillook.account.AccountRepo;
import software.magizhchi.maillook.mail.MailActions;
import software.magizhchi.maillook.mail.MessageParser;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Magizhchi Forms' server sending a form's mail (a confirmation, a workflow rule's mail) from its
 * owner's own mailbox, so replies come back to them and a copy sits in their Sent folder.
 *
 * Only when the owner has said yes in MailLook (Forms page → Settings → "Send Forms' mail from my
 * mailbox", which picks the mailbox); otherwise 409, and Forms sends from its own address as before.
 * Guarded by the shared Forms secret, and capped per person per day.
 */
@RestController
@RequestMapping("/api/integration/forms")
public class FormsSendController {

    static final int PER_DAY = 300;

    private final FormsClient forms;
    private final MailActions actions;
    private final AccountRepo accounts;
    private final JdbcTemplate jdbc;
    private final Map<String, AtomicInteger> today = new ConcurrentHashMap<>();

    public FormsSendController(FormsClient forms, MailActions actions, AccountRepo accounts, JdbcTemplate jdbc) {
        this.forms = forms;
        this.actions = actions;
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    public record SendRequest(String owner, String to, String subject, String text, String formTitle) {}

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> send(@RequestHeader(value = "X-Integration-Secret", required = false) String secret,
                                                    @RequestBody SendRequest req) {
        if (!forms.configured()) return answer(HttpStatus.NOT_FOUND, "Not found.");
        if (!forms.secretMatches(secret)) return answer(HttpStatus.FORBIDDEN, "Wrong integration secret.");
        String owner = req.owner() == null ? "" : req.owner().trim().toLowerCase(Locale.ROOT);
        String to = req.to() == null ? null : MessageParser.emailOnly(req.to());
        if (owner.isEmpty() || to == null || !to.contains("@") || to.contains(",") || to.contains(" "))
            return answer(HttpStatus.BAD_REQUEST, "Say whose mailbox (owner) and one address to send to.");
        List<Map<String, Object>> users = jdbc.queryForList("SELECT id, forms_send_account FROM users WHERE lower(email) = ?", owner);
        if (users.isEmpty() || users.get(0).get("forms_send_account") == null)
            return answer(HttpStatus.CONFLICT, "The owner hasn't allowed Forms to send from their mailbox.");
        long userId = ((Number) users.get(0).get("id")).longValue();
        long accountId = ((Number) users.get(0).get("forms_send_account")).longValue();
        Account a = accounts.find(accountId).filter(x -> x.userId() == userId).orElse(null);
        if (a == null || "error".equals(a.status())) return answer(HttpStatus.CONFLICT, "The owner's chosen mailbox isn't working.");

        String key = userId + ":" + LocalDate.now();
        today.keySet().removeIf(k -> !k.endsWith(LocalDate.now().toString()));
        if (today.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet() > PER_DAY)
            return answer(HttpStatus.TOO_MANY_REQUESTS, "Today's limit for Forms' mail from this mailbox is reached.");

        String subject = req.subject() == null ? "" : req.subject().strip();
        if (subject.length() > 300) subject = subject.substring(0, 300);
        String text = req.text() == null ? "" : req.text();
        if (text.length() > 50_000) text = text.substring(0, 50_000);
        String title = req.formTitle() == null || req.formTitle().isBlank() ? "a form" : "“" + req.formTitle().strip() + "”";
        text = text.stripTrailing() + "\n\n—\nSent through Magizhchi Forms for " + title + ".";
        try {
            actions.deliver(userId, new MailActions.Outgoing(a.id(), List.of(to), List.of(), List.of(), subject, text, null), List.of());
        } catch (Exception e) {
            return answer(HttpStatus.BAD_GATEWAY, "The owner's mailbox didn't send it.");
        }
        return ResponseEntity.ok(Map.of("sent", true, "from", a.email()));
    }

    private static ResponseEntity<Map<String, Object>> answer(HttpStatus s, String why) {
        return ResponseEntity.status(s).body(Map.of("error", why));
    }
}
