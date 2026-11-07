package software.magizhchi.maillook.mail;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.magizhchi.maillook.common.ApiException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/** Notes on a conversation, snooze ("show me this again later") and "remind me if nobody replies". */
@RestController
@RequestMapping("/api/messages/{id}")
public class NoteController {

    public record NoteRequest(String body) {}
    public record WhenRequest(String at) {}

    static final int NOTE_MAX = 10_000;

    private final MailActions actions;
    private final NoteRepo notes;
    private final MessageRepo messages;
    private final FollowUps followUps;

    public NoteController(MailActions actions, NoteRepo notes, MessageRepo messages, FollowUps followUps) {
        this.actions = actions;
        this.notes = notes;
        this.messages = messages;
        this.followUps = followUps;
    }

    // ── notes ─────────────────────────────────────────────────────

    @GetMapping("/note")
    public Map<String, Object> note(@RequestAttribute long userId, @PathVariable long id) {
        MessageRepo.Row r = actions.owned(userId, id);
        return notes.find(r.accountId(), r.threadKey()).map(NoteRepo.Note::json).orElseGet(NoteController::empty);
    }

    @PutMapping("/note")
    public Map<String, Object> saveNote(@RequestAttribute long userId, @PathVariable long id, @RequestBody NoteRequest req) {
        MessageRepo.Row r = actions.owned(userId, id);
        String body = req.body() == null ? "" : req.body().strip();
        if (body.length() > NOTE_MAX) throw ApiException.bad("Notes can be up to 10,000 characters.");
        if (body.isEmpty()) {
            notes.delete(r.accountId(), r.threadKey());
            return empty();
        }
        return notes.save(userId, r.accountId(), r.threadKey(), body).json();
    }

    @DeleteMapping("/note")
    public ResponseEntity<Void> deleteNote(@RequestAttribute long userId, @PathVariable long id) {
        MessageRepo.Row r = actions.owned(userId, id);
        notes.delete(r.accountId(), r.threadKey());
        return ResponseEntity.noContent().build();
    }

    private static Map<String, Object> empty() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("body", null);
        m.put("paNoteId", null);
        m.put("updatedAt", null);
        return m;
    }

    // ── snooze and follow-up ──────────────────────────────────────

    /** Hides the mail until then; it comes back on top of its lane, unread. No time = wake it now. */
    @PostMapping("/snooze")
    public Map<String, Object> snooze(@RequestAttribute long userId, @PathVariable long id, @RequestBody WhenRequest req) {
        MessageRepo.Row r = actions.owned(userId, id);
        if (req == null || req.at() == null || req.at().isBlank()) {
            messages.snooze(r.id(), null);
        } else {
            if (!"INBOX".equals(r.folder())) throw ApiException.bad("Only mail in your inbox can be snoozed.");
            messages.snooze(r.id(), future(req.at(), "snooze"));
        }
        return messages.find(userId, id).orElseThrow().summary();
    }

    /** "Remind me if nobody replies by then" on a mail you sent. No time = no reminder. */
    @PostMapping("/followup")
    public Map<String, Object> followUp(@RequestAttribute long userId, @PathVariable long id, @RequestBody WhenRequest req) {
        MessageRepo.Row r = actions.owned(userId, id);
        if (req == null || req.at() == null || req.at().isBlank()) {
            followUps.cancel(r.id());
        } else {
            if (!"SENT".equals(r.folder())) throw ApiException.bad("Reminders are for mail you sent.");
            followUps.set(userId, r.id(), future(req.at(), "reminder"));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("followUpAt", followUps.due(r.id()).map(Instant::toString).orElse(null));
        return m;
    }

    static OffsetDateTime future(String at, String what) {
        Instant t;
        try {
            t = Instant.parse(at.trim());
        } catch (Exception e) {
            throw ApiException.bad("The " + what + " time isn't a valid date and time.");
        }
        Instant now = Instant.now();
        if (t.isBefore(now.plusSeconds(30))) throw ApiException.bad("Pick a time in the future.");
        if (t.isAfter(now.plus(java.time.Duration.ofDays(366)))) throw ApiException.bad("Pick a time within a year.");
        return OffsetDateTime.ofInstant(t.truncatedTo(java.time.temporal.ChronoUnit.MILLIS), ZoneOffset.UTC);
    }
}
