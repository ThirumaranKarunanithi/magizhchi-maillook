package software.magizhchi.maillook.suite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.magizhchi.maillook.mail.LaneClassifier;
import software.magizhchi.maillook.mail.MessageRepo;
import software.magizhchi.suite.events.SuiteEvents;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Puts {@code maillook.mail.received} on the suite's events bus: new mail has arrived in one of the person's
 * mailboxes. The mailbox's owner gets it, so their "when this happens" automations in PA run (make a task from a
 * client's mail, file a bill, ...). Sent for each new inbox mail from a sync, never for the first fill of a mailbox
 * just added, and not for newsletters and promotions ("can wait") or mail the person sent themself. Inert until the
 * provision secret is set.
 */
@Component
public class MailBusEvents {

    private static final Logger log = LoggerFactory.getLogger(MailBusEvents.class);

    public static final String RECEIVED_TYPE = "maillook.mail.received";
    /** At most this many events from one sync (the newest), so a catch-up after downtime cannot flood PA. */
    static final int MAX_PER_SYNC = 25;
    static final int SNIPPET_CHARS = 300;

    private final SuiteEvents events;
    private final MessageRepo messages;
    private final MailLookUsers users;
    private final String webUrl;

    public MailBusEvents(SuiteEvents events, MessageRepo messages, MailLookUsers users,
                         @Value("${maillook.web-url:}") String webUrl) {
        this.events = events;
        this.messages = messages;
        this.users = users;
        this.webUrl = webUrl == null ? "" : webUrl.trim().replaceAll("/+$", "");
    }

    public boolean enabled() {
        return events.enabled();
    }

    /** New inbox mail from one sync (the rows are already saved). Never throws: a sync is never held up by the bus. */
    public void received(long userId, List<Long> ids) {
        if (!enabled() || ids == null || ids.isEmpty()) return;
        try {
            String email = users.identity(userId).map(SuiteTokens.Identity::email).map(MailBusEvents::clean).orElse(null);
            if (email == null) return;   // not a Magizhchi ID account, so there is no PA to tell
            List<MessageRepo.Row> rows = messages.byIds(new HashSet<>(ids)).stream()
                    .filter(MailBusEvents::worthTelling)
                    .sorted(Comparator.comparing(MessageRepo.Row::receivedAt, Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                    .toList();
            if (rows.size() > MAX_PER_SYNC) {
                log.info("maillook.mail.received: {} new mails in one sync for user {}, telling PA about the newest {}",
                        rows.size(), userId, MAX_PER_SYNC);
                rows = rows.subList(0, MAX_PER_SYNC);
            }
            for (MessageRepo.Row r : rows) {
                events.publish(SuiteEvents.Event.of(RECEIVED_TYPE, r.id() + ":received").owner(email).data(data(r, webUrl)));
            }
        } catch (RuntimeException ex) {
            log.info("mail received events not published for user {}: {}", userId, ex.toString());
        }
    }

    /** Not newsletters and promotions, and not the person's own mail coming back to them. */
    static boolean worthTelling(MessageRepo.Row r) {
        if (LaneClassifier.CAN_WAIT.equals(r.lane())) return false;
        String from = clean(r.fromEmail());
        return from == null || !from.equals(clean(r.accountEmail()));
    }

    /** What receivers get: who wrote, the subject, the start of the mail, and a link to open it. */
    static Map<String, Object> data(MessageRepo.Row r, String webUrl) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("mailId", r.id());
        d.put("mailbox", r.accountEmail());
        d.put("fromName", r.fromName());
        d.put("fromEmail", r.fromEmail());
        d.put("subject", r.subject() == null || r.subject().isBlank() ? "(no subject)" : r.subject());
        String s = r.snippet() == null ? "" : r.snippet().strip();
        d.put("snippet", s.length() > SNIPPET_CHARS ? s.substring(0, SNIPPET_CHARS) + "…" : s);
        d.put("lane", r.lane());
        d.put("needsMe", LaneClassifier.NEEDS_ME.equals(r.lane()));
        d.put("folder", r.folder());
        d.put("attachmentCount", r.attachments() == null ? 0 : r.attachments().size());
        d.put("attachmentNames", r.attachments() == null ? List.of() : r.attachments().stream().limit(5)
                .map(a -> String.valueOf(a.getOrDefault("name", "attachment"))).toList());
        d.put("receivedAt", (r.receivedAt() == null ? Instant.now() : r.receivedAt().toInstant()).toString());
        d.put("link", webUrl == null || webUrl.isBlank() ? null : webUrl + "/?open=" + r.id());
        return d;
    }

    private static String clean(String email) {
        if (email == null || !email.contains("@")) return null;
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
