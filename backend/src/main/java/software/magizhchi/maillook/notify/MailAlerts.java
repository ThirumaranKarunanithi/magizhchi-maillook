package software.magizhchi.maillook.notify;

import org.springframework.stereotype.Service;
import software.magizhchi.maillook.mail.LaneClassifier;
import software.magizhchi.maillook.mail.MessageRepo;

import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

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
        List<MessageRepo.Row> fresh = messages.byIds(new HashSet<>(ids)).stream()
                .filter(r -> !r.seen() && r.snoozedUntil() == null)
                .toList();
        // A one-time code is no use later, so it rings on its own, even when only "Needs me" mail rings.
        List<MessageRepo.Row> codes = fresh.stream().filter(MailAlerts::isCode).toList();
        for (MessageRepo.Row r : codes) {
            push.send(userId, "Code · " + who(r),
                    (r.subject() == null || r.subject().isBlank() ? "(no subject)" : r.subject())
                            + (r.snippet() == null || r.snippet().isBlank() ? "" : "\n" + r.snippet()),
                    "/?open=" + r.id(), "maillook-" + r.id());
        }
        List<MessageRepo.Row> rows = fresh.stream()
                .filter(r -> !codes.contains(r))
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

    /** OTP, one-time password, verification / login / security code, in English or Tamil. */
    private static final Pattern CODE = Pattern.compile(
            "\\b(otp|one[- ]time (password|passcode|pin|code)|verification code|verify code|security code|"
                    + "login code|sign[- ]?in code|confirmation code|authentication code|access code|passcode|2fa|"
                    + "two[- ]factor)\\b|is your (\\w+ )?code|ஒரு முறை கடவுச்சொல்|சரிபார்ப்பு குறியீடு",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static boolean isCode(MessageRepo.Row r) {
        return isCode(r.subject(), r.snippet());
    }

    static boolean isCode(String subject, String snippet) {
        return CODE.matcher((subject == null ? "" : subject) + "\n" + (snippet == null ? "" : snippet)).find();
    }

    private static String who(MessageRepo.Row r) {
        return r.fromName() == null || r.fromName().isBlank() ? (r.fromEmail() == null ? "Someone" : r.fromEmail()) : r.fromName();
    }
}
