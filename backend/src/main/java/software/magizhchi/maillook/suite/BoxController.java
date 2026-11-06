package software.magizhchi.maillook.suite;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.magizhchi.maillook.common.ApiException;
import software.magizhchi.maillook.mail.MailActions;
import software.magizhchi.maillook.mail.MessageRepo;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * MailLook → Magizhchi Box: save an email's attachments to the person's own Box drive, in
 * "Magizhchi MailLook/Attachments/<year-month>/", and show their Box space and plan.
 */
@RestController
@RequestMapping("/api")
public class BoxController {

    static final String ROOT = "Magizhchi MailLook/Attachments/";
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private final BoxClient box;
    private final MailActions actions;

    public BoxController(BoxClient box, MailActions actions) {
        this.box = box;
        this.actions = actions;
    }

    @GetMapping("/box/status")
    public Map<String, Object> status(@RequestAttribute long userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", box.configured());
        Optional<BoxClient.Link> link = box.existing(userId);
        m.put("connected", link.isPresent());
        m.put("folder", ROOT.replaceAll("/$", ""));
        m.put("webUrl", box.webUrl(link.map(BoxClient.Link::driveId).orElse(null)));
        m.put("usage", null);
        if (link.isPresent()) {
            try {
                JsonNode u = box.usage(userId);
                if (u == null) {
                    m.put("connected", false);
                } else {
                    Map<String, Object> usage = new LinkedHashMap<>();
                    for (String f : List.of("usedBytes", "limitBytes", "usedPercent", "planCode", "planLabel",
                            "planExpiresAt", "planState", "graceEndsAt")) {
                        JsonNode v = u.get(f);
                        usage.put(f, v == null || v.isNull() ? null : v.isNumber() ? v.numberValue() : v.asText());
                    }
                    JsonNode p = u.get("pendingPayment");
                    usage.put("pendingPayment", p == null || p.isNull() ? null : Map.of(
                            "reference", p.path("reference").asText(""),
                            "planCode", p.path("planCode").asText(""),
                            "status", p.path("status").asText("")));
                    m.put("usage", usage);
                }
            } catch (ApiException e) {
                m.put("message", e.getMessage());
            }
        }
        return m;
    }

    /** Connects now (the Accounts page button). Saving a file does this by itself too. */
    @PostMapping("/box/connect")
    public Map<String, Object> connect(@RequestAttribute long userId) {
        box.link(userId);
        return status(userId);
    }

    @PostMapping("/messages/{id}/attachments/{index}/box")
    public Map<String, Object> saveOne(@RequestAttribute long userId, @PathVariable long id, @PathVariable int index) {
        MessageRepo.Row msg = actions.owned(userId, id);
        Optional<Long> already = box.savedFile(id, index);
        if (already.isPresent()) {
            return result("already", List.of(index), 0, 0, msg, userId, "It's already in your Magizhchi Box.");
        }
        String folder = folderFor(msg);
        MailActions.Download d = actions.attachment(userId, id, index);
        long fileId = box.upload(userId, d.name(), d.contentType(), d.bytes(), folder);
        record(id, index, fileId, folder);
        return result("saved", List.of(index), 1, 0, msg, userId, "Saved \"" + d.name() + "\" to Magizhchi Box › " + pretty(folder) + ".");
    }

    @PostMapping("/messages/{id}/box")
    public Map<String, Object> saveAll(@RequestAttribute long userId, @PathVariable long id) {
        MessageRepo.Row msg = actions.owned(userId, id);
        List<Map<String, Object>> atts = msg.attachments() == null ? List.of() : msg.attachments();
        if (atts.isEmpty()) throw ApiException.bad("This email has no attachments.");
        Set<String> saved = new HashSet<>(box.savedKeys(List.of(id)));
        String folder = folderFor(msg);
        List<Integer> inBox = new ArrayList<>();
        int added = 0, skipped = 0;
        String stopped = null;
        for (Map<String, Object> a : atts) {
            int index = ((Number) a.getOrDefault("index", 0)).intValue();
            if (saved.contains(id + ":" + index)) {
                inBox.add(index);
                continue;
            }
            try {
                MailActions.Download d = actions.attachment(userId, id, index);
                long fileId = box.upload(userId, d.name(), d.contentType(), d.bytes(), folder);
                record(id, index, fileId, folder);
                inBox.add(index);
                added++;
            } catch (ApiException e) {
                boolean full = e.status() == HttpStatus.PAYLOAD_TOO_LARGE && e.getMessage().contains("full");
                if (e.status() == HttpStatus.PAYLOAD_TOO_LARGE && !full) {
                    skipped++;
                    continue;
                }
                // Box is full, unreachable or refused: stop here; nothing is retried in a loop.
                if (added == 0) throw e;
                stopped = e.getMessage();
                break;
            }
        }
        String message;
        if (stopped != null) message = "Saved " + plural(added) + ", then stopped: " + stopped;
        else if (added == 0 && skipped == 0) message = "All attachments are already in your Magizhchi Box.";
        else if (added == 0) message = (skipped == 1 ? "That file is" : plural(skipped) + " are") + " too big for Magizhchi Box.";
        else message = "Saved " + plural(added) + " to Magizhchi Box › " + pretty(folder) + "."
                + (skipped > 0 ? " " + plural(skipped) + " too big for Box." : "");
        return result(stopped != null ? "partial" : added > 0 ? "saved" : "already", inBox, added, skipped, msg, userId, message);
    }

    // ── helpers ───────────────────────────────────────────────────

    private void record(long id, int index, long fileId, String folder) {
        try {
            box.recordSave(id, index, fileId, folder);
        } catch (DuplicateKeyException ignored) {
            // A second tap that raced the first one: the file is in Box either way.
        }
    }

    private Map<String, Object> result(String status, List<Integer> inBox, int added, int skipped,
                                       MessageRepo.Row msg, long userId, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("messageId", msg.id());
        m.put("inBox", inBox);
        m.put("saved", added);
        m.put("skipped", skipped);
        m.put("message", message);
        m.put("webUrl", box.webUrl(box.existing(userId).map(BoxClient.Link::driveId).orElse(null)));
        return m;
    }

    static String folderFor(MessageRepo.Row msg) {
        OffsetDateTime at = msg.receivedAt() == null ? OffsetDateTime.now() : msg.receivedAt();
        return ROOT + YearMonth.from(at.atZoneSameInstant(ZONE)) + "/";
    }

    private static String pretty(String folder) {
        return folder.replaceAll("/$", "").replace("/", " › ");
    }

    private static String plural(int n) {
        return n == 1 ? "1 file" : n + " files";
    }
}
