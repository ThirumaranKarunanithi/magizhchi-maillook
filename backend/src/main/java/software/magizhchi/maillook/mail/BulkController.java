package software.magizhchi.maillook.mail;

import jakarta.mail.Flags;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.magizhchi.maillook.common.ApiException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Many mails in one go: archive a whole lane ("Can wait"), and whatever the person ticked in the list
 * (or "all 776 in this view"): Done, Trash, move, read/unread, flag, lane, snooze. Every answer carries
 * what Undo needs.
 */
@RestController
@RequestMapping("/api/messages/bulk")
public class BulkController {

    public record LaneRequest(String lane, Long accountId) {}
    public record MoveRequest(List<Long> ids, String folder) {}

    /** The view the list shows, for "everything in this view" instead of ticked ids. */
    public record Filter(String lane, String folder, Long accountId, String q, String from, Boolean hasAttachment,
                         Boolean unread, Boolean flagged, String after, String before, String tz) {}

    /** action: archive, trash, move (folder), seen, unseen, flag, unflag, lane (lane), snooze (at; none = wake). */
    public record ApplyRequest(List<Long> ids, Filter filter, String action, String folder, String lane, String at) {}

    /** Enough for any real lane; a bigger backlog is handled in a second press. */
    static final int MAX = 2000;
    private static final Set<String> TARGETS = Set.of("INBOX", "ARCHIVE");
    private static final Set<String> MOVE_TO = Set.of("INBOX", "ARCHIVE", "TRASH", "SPAM");
    private static final Set<String> VIEWS = Set.of("INBOX", "SENT", "ARCHIVE", "TRASH", "SPAM");

    private final MessageRepo messages;
    private final MailActions actions;
    private final FolderCursors cursors;

    public BulkController(MessageRepo messages, MailActions actions, FolderCursors cursors) {
        this.messages = messages;
        this.actions = actions;
        this.cursors = cursors;
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

    /** How many mails the view holds in all, for "Select all 776 in For my info". */
    @GetMapping("/count")
    public Map<String, Object> count(@RequestAttribute long userId,
                                     @RequestParam(defaultValue = "all") String lane,
                                     @RequestParam(required = false) Long accountId,
                                     @RequestParam(defaultValue = "INBOX") String folder,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(required = false) String from,
                                     @RequestParam(required = false) Boolean hasAttachment,
                                     @RequestParam(required = false) Boolean unread,
                                     @RequestParam(required = false) Boolean flagged,
                                     @RequestParam(required = false) String after,
                                     @RequestParam(required = false) String before,
                                     @RequestParam(required = false) String tz) {
        Filter f = new Filter(lane, folder, accountId, q, from, hasAttachment, unread, flagged, after, before, tz);
        View v = view(userId, f);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", messages.countMatching(userId, v.folder, v.lane, f.accountId(), v.search));
        out.put("max", MAX);
        return out;
    }

    /** Applies one action to the ticked mails, or to everything in the view (up to {@link #MAX} at a time). */
    @PostMapping("/apply")
    public Map<String, Object> apply(@RequestAttribute long userId, @RequestBody ApplyRequest req) {
        String action = req.action() == null ? "" : req.action().trim().toLowerCase(Locale.ROOT);
        boolean more = false;
        List<Long> ids;
        if (req.filter() != null) {
            View v = view(userId, req.filter());
            ids = messages.matchingIds(userId, v.folder, v.lane, req.filter().accountId(), v.search, MAX + 1);
            if (ids.size() > MAX) {
                more = true;
                ids = new ArrayList<>(ids.subList(0, MAX));
            }
        } else {
            if (req.ids() == null || req.ids().isEmpty()) return withMore(result(0, List.of()), false);
            ids = new ArrayList<>(new LinkedHashSet<>(req.ids()));
            if (ids.size() > MAX) throw ApiException.bad("That's too many at once. Pick up to " + MAX + ".");
        }
        if (ids.isEmpty()) return withMore(result(0, List.of()), false);

        List<MessageRepo.Row> rows = messages.byIds(new LinkedHashSet<>(ids)).stream().filter(r -> r.userId() == userId).toList();
        List<Map<String, Object>> undo = new ArrayList<>();
        List<Long> done;
        int needed;
        switch (action) {
            case "archive", "trash", "move" -> {
                String to = "archive".equals(action) ? "ARCHIVE" : "trash".equals(action) ? "TRASH" : moveTarget(req.folder());
                // Undo puts each mail back in the folder it came from.
                Map<String, List<Long>> from = new LinkedHashMap<>();
                for (MessageRepo.Row r : rows) if (!to.equals(r.folder())) from.computeIfAbsent(r.folder(), k -> new ArrayList<>()).add(r.id());
                needed = from.values().stream().mapToInt(List::size).sum();
                List<Long> movedIds = actions.moveMany(userId, ids, to);
                done = movedIds;
                if (to.startsWith("F:") && !movedIds.isEmpty()) {
                    rows.stream().filter(r -> movedIds.contains(r.id())).map(MessageRepo.Row::accountId).distinct()
                            .forEach(acc -> cursors.open(acc, to, to.substring(2)));
                }
                Set<Long> moved = new java.util.HashSet<>(movedIds);
                from.forEach((folder, list) -> {
                    List<Long> back = list.stream().filter(moved::contains).toList();
                    if (!back.isEmpty()) undo.add(step("move", "folder", folder, back));
                });
            }
            case "seen", "unseen" -> {
                boolean on = "seen".equals(action);
                needed = (int) rows.stream().filter(r -> r.seen() != on).count();
                done = actions.flagMany(userId, ids, Flags.Flag.SEEN, on);
                if (!done.isEmpty()) undo.add(step(on ? "unseen" : "seen", null, null, done));
            }
            case "flag", "unflag" -> {
                boolean on = "flag".equals(action);
                needed = (int) rows.stream().filter(r -> r.flagged() != on).count();
                done = actions.flagMany(userId, ids, Flags.Flag.FLAGGED, on);
                if (!done.isEmpty()) undo.add(step(on ? "unflag" : "flag", null, null, done));
            }
            case "lane" -> {
                if (!LaneClassifier.isLane(req.lane())) throw ApiException.bad("Unknown lane.");
                Map<String, List<Long>> before = new LinkedHashMap<>();
                done = new ArrayList<>();
                for (MessageRepo.Row r : rows) {
                    if (req.lane().equals(r.lane())) continue;
                    if (r.lane() != null) before.computeIfAbsent(r.lane(), k -> new ArrayList<>()).add(r.id());
                    messages.setLane(r.id(), req.lane(), "user");
                    done.add(r.id());
                }
                needed = done.size();
                before.forEach((lane, list) -> undo.add(step("lane", "lane", lane, list)));
            }
            case "snooze" -> {
                boolean wake = req.at() == null || req.at().isBlank();
                java.time.OffsetDateTime until = wake ? null : NoteController.future(req.at(), "snooze");
                done = new ArrayList<>();
                for (MessageRepo.Row r : rows) {
                    if (wake ? r.snoozedUntil() == null : !"INBOX".equals(r.folder())) continue;
                    messages.snooze(r.id(), until);
                    done.add(r.id());
                }
                needed = done.size();
                if (!wake && !done.isEmpty()) undo.add(step("snooze", null, null, done));
            }
            default -> throw ApiException.bad("Unknown action.");
        }
        Map<String, Object> out = result(needed, done);
        out.put("undo", undo);
        return withMore(out, more);
    }

    private static Map<String, Object> step(String action, String key, String value, List<Long> ids) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", action);
        if (key != null) m.put(key, value);
        m.put("ids", ids);
        return m;
    }

    private static String moveTarget(String folder) {
        String to = folder == null ? "" : folder.trim();
        if (to.startsWith("F:")) {
            if (to.length() < 3 || to.length() > 290) throw ApiException.bad("Unknown folder.");
            return to;
        }
        to = to.toUpperCase(Locale.ROOT);
        if (!MOVE_TO.contains(to)) throw ApiException.bad("Mail can be moved to Inbox, Archive, Trash, Spam or one of your folders.");
        return to;
    }

    private record View(String folder, String lane, MailSearch search) {}

    private View view(long userId, Filter f) {
        String folder = f.folder() == null || f.folder().isBlank() ? "INBOX" : f.folder();
        if (folder.startsWith("F:")) {
            if (!cursors.known(userId, folder)) throw ApiException.notFound("Folder");
        } else {
            folder = folder.toUpperCase(Locale.ROOT);
            if (!VIEWS.contains(folder)) throw ApiException.bad("Select all works in your mail folders and lanes.");
        }
        String lane = f.lane() == null || "all".equals(f.lane()) ? null : f.lane();
        if (lane != null && !LaneClassifier.isLane(lane)) throw ApiException.bad("Unknown lane.");
        MailSearch search = MailSearch.parse(f.q(), MessageController.zone(f.tz()), Instant.now())
                .with(f.from(), f.hasAttachment(), f.unread(), f.flagged(), instant(f.after()), instant(f.before()));
        return new View(folder, lane, search);
    }

    private static Instant instant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.bad("Dates look like 2026-11-07T00:00:00Z.");
        }
    }

    private static Map<String, Object> withMore(Map<String, Object> out, boolean more) {
        out.put("more", more);
        return out;
    }

    private static Map<String, Object> result(int asked, List<Long> moved) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("moved", moved.size());
        out.put("failed", asked - moved.size());
        out.put("ids", moved);
        return out;
    }
}
