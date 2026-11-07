package software.magizhchi.maillook.mail;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.magizhchi.maillook.common.ApiException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Clean-up in one go: archive a whole lane ("Can wait"), and put it back (Undo). */
@RestController
@RequestMapping("/api/messages/bulk")
public class BulkController {

    public record LaneRequest(String lane, Long accountId) {}
    public record MoveRequest(List<Long> ids, String folder) {}

    /** Enough for any real lane; a bigger backlog is archived in a second press. */
    static final int MAX = 2000;
    private static final Set<String> TARGETS = Set.of("INBOX", "ARCHIVE");

    private final MessageRepo messages;
    private final MailActions actions;

    public BulkController(MessageRepo messages, MailActions actions) {
        this.messages = messages;
        this.actions = actions;
    }

    /** Archives every Inbox mail in the lane (snoozed mail stays put). Returns the ids, for Undo. */
    @PostMapping("/archive-lane")
    public Map<String, Object> archiveLane(@RequestAttribute long userId, @RequestBody LaneRequest req) {
        if (req.lane() == null || !LaneClassifier.isLane(req.lane()) || LaneClassifier.NEEDS_ME.equals(req.lane())) {
            throw ApiException.bad("Only For my info and Can wait can be archived in one go.");
        }
        List<Long> ids = messages.laneIds(userId, req.accountId(), req.lane(), MAX);
        List<Long> moved = actions.moveMany(userId, ids, "ARCHIVE");
        return result(ids.size(), moved);
    }

    /** Moves chosen mail to the Inbox or Archive: the Undo of an archive-all. */
    @PostMapping("/move")
    public Map<String, Object> move(@RequestAttribute long userId, @RequestBody MoveRequest req) {
        if (req.folder() == null || !TARGETS.contains(req.folder())) throw ApiException.bad("Mail can be moved back to the Inbox or to Archive.");
        if (req.ids() == null || req.ids().isEmpty()) return result(0, List.of());
        if (req.ids().size() > MAX) throw ApiException.bad("That's too many at once.");
        List<Long> moved = actions.moveMany(userId, req.ids(), req.folder());
        return result(req.ids().size(), moved);
    }

    private static Map<String, Object> result(int asked, List<Long> moved) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("moved", moved.size());
        out.put("failed", asked - moved.size());
        out.put("ids", moved);
        return out;
    }
}
