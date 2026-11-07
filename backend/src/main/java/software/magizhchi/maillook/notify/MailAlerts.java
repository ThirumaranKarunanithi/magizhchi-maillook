package software.magizhchi.maillook.notify;

import org.springframework.stereotype.Service;
import software.magizhchi.maillook.mail.LaneClassifier;
import software.magizhchi.maillook.mail.MessageRepo;

import java.util.HashSet;
import java.util.List;

/** Rings people's browsers and phones when new mail arrives (never for the first fill of a new mailbox). */
@Service
public class MailAlerts {

    private final MessageRepo messages;
    private final PushService push;

    public MailAlerts(MessageRepo messages, PushService push) {
        this.messages = messages;
        this.push = push;
    }

    public void arrived(long userId, List<Long> ids) {
        // Recorded for the desktop app's feed even when this server has no Web Push keys.
        if (ids == null || ids.isEmpty()) return;
        String mode = push.mode(userId);
        if (PushService.OFF.equals(mode)) return;
        List<MessageRepo.Row> rows = messages.byIds(new HashSet<>(ids)).stream()
                .filter(r -> !r.seen() && r.snoozedUntil() == null)
                .filter(r -> PushService.ALL.equals(mode) || LaneClassifier.NEEDS_ME.equals(r.lane()))
                .toList();
        if (rows.isEmpty()) return;
        boolean needsMe = rows.stream().anyMatch(r -> LaneClassifier.NEEDS_ME.equals(r.lane()));
        if (rows.size() > 3) {
            long n = rows.stream().filter(r -> LaneClassifier.NEEDS_ME.equals(r.lane())).count();
            String title = n > 0 ? n + " new mails need you" : rows.size() + " new mails";
            String body = rows.stream().limit(3).map(MailAlerts::who).distinct().reduce((a, b) -> a + ", " + b).orElse("");
            push.send(userId, title, "From " + body + (rows.size() > 3 ? " and others" : ""),
                    needsMe ? "/?view=needs_me" : "/", "maillook-new");
            return;
        }
        for (MessageRepo.Row r : rows) {
            boolean nm = LaneClassifier.NEEDS_ME.equals(r.lane());
            push.send(userId, (nm ? "Needs you · " : "") + who(r),
                    (r.subject() == null || r.subject().isBlank() ? "(no subject)" : r.subject())
                            + (r.snippet() == null || r.snippet().isBlank() ? "" : "\n" + r.snippet()),
                    "/?open=" + r.id(), "maillook-" + r.id());
        }
    }

    private static String who(MessageRepo.Row r) {
        return r.fromName() == null || r.fromName().isBlank() ? (r.fromEmail() == null ? "Someone" : r.fromEmail()) : r.fromName();
    }
}
