package software.magizhchi.maillook.mail;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.magizhchi.maillook.ai.AiService;
import software.magizhchi.maillook.common.ApiException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Waiting on others: drop a mail from the list, or get a kind follow-up ready to send. */
@RestController
@RequestMapping("/api/messages/{id}")
public class WaitingController {

    private final MailActions actions;
    private final Waiting waiting;
    private final AiService ai;

    public WaitingController(MailActions actions, Waiting waiting, AiService ai) {
        this.actions = actions;
        this.waiting = waiting;
        this.ai = ai;
    }

    @PostMapping("/waiting/off")
    public ResponseEntity<Void> off(@RequestAttribute long userId, @PathVariable long id) {
        waiting.dismiss(actions.owned(userId, id));
        return ResponseEntity.noContent().build();
    }

    /**
     * The follow-up as a reply to your own mail, to the same people: AI writes it in your voice, or,
     * without AI, a short plain reminder.
     */
    @PostMapping("/nudge")
    public Map<String, Object> nudge(@RequestAttribute long userId, @PathVariable long id) {
        MessageRepo.Row sent = actions.owned(userId, id);
        if (!"SENT".equals(sent.folder())) throw ApiException.bad("Only mail you sent can be followed up.");
        long days = Math.max(1, Duration.between(sent.receivedAt().toInstant(), Instant.now()).toDays());
        String body = null;
        boolean byAi = false;
        if (ai.enabled(userId)) {
            try {
                body = ai.nudge(sent, days).path("body").asText("").trim();
                byAi = !body.isEmpty();
            } catch (ApiException ignored) {
                // falls back to the plain one below
            }
        }
        if (body == null || body.isBlank()) {
            String first = Waiting.who(sent);
            body = "Hi " + (first.contains("@") ? "" : first.split(" ")[0]) + ",\n\n"
                    + "Just following up on my mail below from " + days + (days == 1 ? " day" : " days") + " ago. "
                    + "Could you let me know when you get a chance?\n\nThanks!";
        }
        String subject = sent.subject() == null ? "" : sent.subject().trim();
        if (!subject.toLowerCase(java.util.Locale.ROOT).startsWith("re:")) subject = "Re: " + subject;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("body", body);
        out.put("byAi", byAi);
        out.put("to", sent.to());
        out.put("cc", sent.cc() == null ? List.of() : new ArrayList<>(sent.cc()));
        out.put("subject", subject);
        out.put("accountId", sent.accountId());
        return out;
    }
}
