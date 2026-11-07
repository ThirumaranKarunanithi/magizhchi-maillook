package software.magizhchi.maillook.mail;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.magizhchi.maillook.common.ApiException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Mail waiting to go: Undo in the first seconds, and the Scheduled view (send now, change the time, edit). */
@RestController
@RequestMapping("/api/outbox")
public class OutboxController {

    public record RescheduleRequest(String sendAt) {}

    private final Outbox outbox;

    public OutboxController(Outbox outbox) {
        this.outbox = outbox;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestAttribute long userId) {
        return outbox.list(userId);
    }

    /** Undo, Edit or Cancel: the mail comes back as a draft (with its files), which is returned. */
    @PostMapping("/{id}/undo")
    public Map<String, Object> undo(@RequestAttribute long userId, @PathVariable long id) {
        return outbox.takeBack(userId, id);
    }

    @PostMapping("/{id}/now")
    public Map<String, Object> now(@RequestAttribute long userId, @PathVariable long id) {
        return outbox.view(outbox.sendNow(userId, id));
    }

    @PostMapping("/{id}/reschedule")
    public Map<String, Object> reschedule(@RequestAttribute long userId, @PathVariable long id, @RequestBody RescheduleRequest req) {
        Instant at;
        try {
            at = OffsetDateTime.parse(req.sendAt()).toInstant();
        } catch (Exception e) {
            throw ApiException.bad("That send time couldn't be read.");
        }
        return outbox.view(outbox.reschedule(userId, id, at));
    }
}
