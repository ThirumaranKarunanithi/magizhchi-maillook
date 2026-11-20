package software.magizhchi.maillook.mail;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Forwards new mail an automation catches ("job applications go to the manager and HR"), with its attachments,
 * from the mailbox it arrived in. Runs in the background so the sync never waits on it. Each mail is forwarded
 * once (rule_forwards), and never back to whoever sent it.
 */
@Service
public class RuleForwarder {

    private static final Logger log = LoggerFactory.getLogger(RuleForwarder.class);
    /** Attachments go along up to this much in all; past it the forward says what was left out. */
    static final long MAX_FILES_BYTES = 18L * 1024 * 1024;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE, d MMM yyyy 'at' h:mm a", Locale.ENGLISH);

    private final JdbcTemplate jdbc;
    private final MessageRepo messages;
    private final MailActions actions;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rule-forward");
        t.setDaemon(true);
        return t;
    });

    public RuleForwarder(JdbcTemplate jdbc, MessageRepo messages, MailActions actions) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.actions = actions;
    }

    /** Queues the forward (once per mail). Mail from one of the people it would go to is not sent back to them. */
    public void forward(long userId, Sorting.Rule rule, long messageId, String fromEmail) {
        String from = fromEmail == null ? "" : fromEmail.toLowerCase(Locale.ROOT);
        List<String> to = rule.forwardTo().stream().filter(e -> !e.equalsIgnoreCase(from)).toList();
        if (to.isEmpty()) return;
        try {
            jdbc.update("INSERT INTO rule_forwards (message_id, rule_id, forwarded_to) VALUES (?,?,?)",
                    messageId, rule.id(), String.join(", ", to));
        } catch (DuplicateKeyException e) {
            return; // already forwarded (or on its way)
        }
        worker.submit(() -> send(userId, messageId, to));
    }

    void send(long userId, long messageId, List<String> to) {
        try {
            MessageRepo.Row r = messages.find(userId, messageId).orElseThrow(() -> new IllegalStateException("mail is gone"));
            List<MailActions.OutFile> files = new ArrayList<>();
            List<String> left = new ArrayList<>();
            long total = 0;
            for (int i = 0; i < r.attachments().size(); i++) {
                Map<String, Object> meta = r.attachments().get(i);
                String name = String.valueOf(meta.getOrDefault("name", "attachment"));
                long size = meta.get("size") instanceof Number n ? n.longValue() : 0;
                if (total + size > MAX_FILES_BYTES) {
                    left.add(name);
                    continue;
                }
                try {
                    MailActions.Download d = actions.attachment(userId, messageId, i);
                    if (total + d.bytes().length > MAX_FILES_BYTES) {
                        left.add(name);
                        continue;
                    }
                    total += d.bytes().length;
                    files.add(new MailActions.OutFile(d.name(), d.contentType(), d.bytes()));
                } catch (Exception e) {
                    left.add(name);
                }
            }
            String subject = subject(r.subject());
            String text = header(r, left) + "\n" + (r.bodyText() == null ? (r.snippet() == null ? "" : r.snippet()) : r.bodyText());
            String html = r.bodyHtml() == null ? null : htmlHeader(r, left) + r.bodyHtml();
            actions.deliver(userId, new MailActions.Outgoing(r.accountId(), to, List.of(), List.of(), subject, text, null, null), files, html);
            jdbc.update("UPDATE rule_forwards SET status = 'sent' WHERE message_id = ?", messageId);
        } catch (Exception e) {
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("Forwarding mail {} to {} failed: {}", messageId, to, why);
            jdbc.update("UPDATE rule_forwards SET status = 'failed', error = ? WHERE message_id = ?",
                    why.length() > 400 ? why.substring(0, 400) : why, messageId);
        }
    }

    static String subject(String s) {
        String t = s == null ? "" : s.trim();
        return t.toLowerCase(Locale.ROOT).matches("^(fwd?|fw):.*") ? t : "Fwd: " + t;
    }

    private static String who(MessageRepo.Row r) {
        String email = r.fromEmail() == null ? "" : r.fromEmail();
        return r.fromName() == null || r.fromName().isBlank() ? email : r.fromName() + " <" + email + ">";
    }

    private static String when(MessageRepo.Row r) {
        return r.receivedAt() == null ? "" : r.receivedAt().atZoneSameInstant(ZoneId.of("Asia/Kolkata")).format(WHEN);
    }

    static String header(MessageRepo.Row r, List<String> left) {
        StringBuilder b = new StringBuilder("---------- Forwarded message ----------\n");
        b.append("From: ").append(who(r)).append('\n');
        b.append("Date: ").append(when(r)).append('\n');
        b.append("Subject: ").append(r.subject() == null ? "" : r.subject()).append('\n');
        if (!r.to().isEmpty()) b.append("To: ").append(String.join(", ", r.to())).append('\n');
        if (!left.isEmpty()) b.append("Not attached (too large to forward): ").append(String.join(", ", left)).append('\n');
        return b.toString();
    }

    private static String htmlHeader(MessageRepo.Row r, List<String> left) {
        StringBuilder b = new StringBuilder("<div style=\"font-family:Arial,sans-serif;font-size:13px;color:#555;margin-bottom:12px\">")
                .append("---------- Forwarded message ----------<br>")
                .append("From: ").append(esc(who(r))).append("<br>")
                .append("Date: ").append(esc(when(r))).append("<br>")
                .append("Subject: ").append(esc(r.subject() == null ? "" : r.subject())).append("<br>");
        if (!r.to().isEmpty()) b.append("To: ").append(esc(String.join(", ", r.to()))).append("<br>");
        if (!left.isEmpty()) b.append("Not attached (too large to forward): ").append(esc(String.join(", ", left))).append("<br>");
        return b.append("</div>").toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    @PreDestroy
    void shutdown() {
        worker.shutdown();
    }
}
