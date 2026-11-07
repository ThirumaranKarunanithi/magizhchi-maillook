package software.magizhchi.maillook.mail;

import software.magizhchi.maillook.suite.BoxClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import software.magizhchi.maillook.ai.AiService;
import software.magizhchi.maillook.common.ApiException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class MessageController {

    public record SeenRequest(Boolean seen) {}
    public record FlagRequest(Boolean flagged) {}
    public record LaneRequest(String lane, Boolean rememberSender) {}

    public record MoveRequest(String folder) {}

    private static final Set<String> FOLDERS = Set.of("INBOX", "SENT", "ARCHIVE", "TRASH", "SPAM");
    /** Where mail can be moved to (not Sent: that is only for mail you sent). */
    private static final Set<String> MOVE_TO = Set.of("INBOX", "ARCHIVE", "TRASH", "SPAM");

    private final MessageRepo messages;
    private final MailActions actions;
    private final AiService ai;
    private final ObjectMapper mapper;
    private final BoxClient box;

    private final software.magizhchi.maillook.suite.MeetSoon meetSoon;
    private final FolderCursors cursors;
    private final DraftRepo drafts;
    private final FollowUps followUps;
    private final NoteRepo notes;
    private final software.magizhchi.maillook.contacts.PeopleService people;
    private final Waiting waiting;
    private final Outbox outbox;
    private final MailFiles mailFiles;

    public MessageController(MessageRepo messages, MailActions actions, AiService ai, ObjectMapper mapper, BoxClient box,
                             software.magizhchi.maillook.suite.MeetSoon meetSoon, FolderCursors cursors, DraftRepo drafts,
                             FollowUps followUps, NoteRepo notes, software.magizhchi.maillook.contacts.PeopleService people,
                             Waiting waiting, Outbox outbox, MailFiles mailFiles) {
        this.waiting = waiting;
        this.outbox = outbox;
        this.mailFiles = mailFiles;
        this.people = people;
        this.followUps = followUps;
        this.notes = notes;
        this.meetSoon = meetSoon;
        this.cursors = cursors;
        this.drafts = drafts;
        this.box = box;
        this.messages = messages;
        this.actions = actions;
        this.ai = ai;
        this.mapper = mapper;
    }

    @GetMapping("/messages")
    public Map<String, Object> list(@RequestAttribute long userId,
                                    @RequestParam(defaultValue = "all") String lane,
                                    @RequestParam(required = false) Long accountId,
                                    @RequestParam(defaultValue = "INBOX") String folder,
                                    @RequestParam(required = false) String q,
                                    @RequestParam(required = false) String cursor,
                                    @RequestParam(defaultValue = "50") int limit,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) Boolean hasAttachment,
                                    @RequestParam(required = false) Boolean unread,
                                    @RequestParam(required = false) Boolean flagged,
                                    @RequestParam(required = false) String after,
                                    @RequestParam(required = false) String before,
                                    @RequestParam(required = false) String tz) {
        if ("WAITING".equalsIgnoreCase(folder)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("items", waiting.list(userId, accountId, Instant.now(), true).stream().map(Waiting.Item::summary).toList());
            out.put("nextCursor", null);
            return out;
        }
        if ("SNOOZED".equalsIgnoreCase(folder)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("items", messages.snoozed(userId, accountId, 200).stream().map(MessageRepo.Row::summary).toList());
            out.put("nextCursor", null);
            return out;
        }
        String f = folderCode(userId, folder);
        Instant now = Instant.now();
        MailSearch search = MailSearch.parse(q, zone(tz), now)
                .with(from, hasAttachment, unread, flagged, instant(after), instant(before));
        String l = "all".equals(lane) ? null : lane;
        if (l != null && !LaneClassifier.isLane(l)) throw ApiException.bad("Unknown lane.");
        OffsetDateTime cAt = null;
        Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] p = cursor.split(":");
                cAt = OffsetDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(p[0])), ZoneOffset.UTC);
                cId = Long.parseLong(p[1]);
            } catch (Exception e) {
                throw ApiException.bad("Bad cursor.");
            }
        }
        int n = Math.max(1, Math.min(100, limit));
        List<MessageRepo.Row> rows = messages.list(userId, f, l, accountId, search, cAt, cId, n);
        String next = null;
        if (rows.size() == n) {
            MessageRepo.Row last = rows.get(rows.size() - 1);
            next = last.receivedAt().toInstant().toEpochMilli() + ":" + last.id();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        // A meeting about to start puts its mail first in Needs me (first page only, not while searching).
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        java.util.Set<Long> pinned = new java.util.HashSet<>();
        if (cAt == null && search.isEmpty() && "INBOX".equals(f) && (l == null || LaneClassifier.NEEDS_ME.equals(l))) {
            for (var p : meetSoon.soon(userId, accountId, Instant.now())) {
                if (!pinned.add(p.row().id())) continue;
                Map<String, Object> m = p.row().summary();
                m.put("meetingStartsAt", p.startsAt().toString());
                items.add(m);
            }
        }
        // Back from snooze, or nobody replied: on top of the first page until opened.
        if (cAt == null && search.isEmpty() && "INBOX".equals(f)) {
            OffsetDateTime since = OffsetDateTime.ofInstant(now.minus(Duration.ofDays(14)), ZoneOffset.UTC);
            for (MessageRepo.Row b : messages.back(userId, accountId, l, since)) {
                if (pinned.add(b.id())) items.add(b.summary());
            }
        }
        for (MessageRepo.Row r : rows) if (!pinned.contains(r.id())) items.add(r.summary());
        out.put("items", items);
        out.put("nextCursor", next);
        return out;
    }

    /** INBOX, SENT, ARCHIVE, TRASH, SPAM, or "F:" + a folder one of their mailboxes has opened. */
    private String folderCode(long userId, String folder) {
        if (folder != null && folder.startsWith("F:")) {
            if (!cursors.known(userId, folder)) throw ApiException.notFound("Folder");
            return folder;
        }
        String f = folder == null ? "INBOX" : folder.toUpperCase(java.util.Locale.ROOT);
        if (!FOLDERS.contains(f)) throw ApiException.bad("Unknown folder.");
        return f;
    }

    static java.time.ZoneId zone(String tz) {
        try {
            return tz == null || tz.isBlank() ? ZoneOffset.UTC : java.time.ZoneId.of(tz);
        } catch (java.time.DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    private static Instant instant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.bad("Dates look like 2026-11-07T00:00:00Z.");
        }
    }

    @GetMapping("/counts")
    public Map<String, Object> counts(@RequestAttribute long userId, @RequestParam(required = false) Long accountId) {
        Map<String, long[]> lanes = messages.laneCounts(userId, accountId);
        Map<String, Object> laneOut = new LinkedHashMap<>();
        for (String l : List.of(LaneClassifier.NEEDS_ME, LaneClassifier.FYI, LaneClassifier.CAN_WAIT)) {
            long[] c = lanes.getOrDefault(l, new long[]{0, 0});
            laneOut.put(l, Map.of("total", c[0], "unread", c[1]));
        }
        Map<String, Object> acc = new LinkedHashMap<>();
        messages.unreadByAccount(userId).forEach((id, unread) -> acc.put(String.valueOf(id), Map.of("unread", unread)));

        Instant now = Instant.now();
        long needsMe = messages.unseenInLane(userId, accountId, LaneClassifier.NEEDS_ME, now.minus(Duration.ofDays(30)), now.plus(Duration.ofDays(1)));
        long waiting = messages.unseenInLane(userId, accountId, LaneClassifier.NEEDS_ME, now.minus(Duration.ofDays(14)), now.minus(Duration.ofHours(48)));
        long fyiUnread = messages.unseenInLane(userId, accountId, LaneClassifier.FYI, now.minus(Duration.ofDays(30)), now.plus(Duration.ofDays(1)));
        long canWaitUnread = messages.unseenInLane(userId, accountId, LaneClassifier.CAN_WAIT, now.minus(Duration.ofDays(30)), now.plus(Duration.ofDays(1)));
        long score = 100 - Math.min(40, needsMe * 5) - Math.min(30, waiting * 6) - Math.min(20, canWaitUnread / 10) - Math.min(10, fyiUnread / 10);
        score = Math.max(0, score);
        String label = score >= 75 ? "Calm" : score >= 45 ? "Busy" : "Overloaded";
        String message;
        if (needsMe == 0) message = "Nobody is waiting on you. Enjoy the calm.";
        else if (needsMe == 1) message = "1 person is waiting on you";
        else message = needsMe + " people are waiting on you";
        if (waiting > 0) message += waiting == 1 ? ", 1 for over 2 days" : ", " + waiting + " for over 2 days";

        long waitingOnOthers = this.waiting.list(userId, accountId, now, false).size();

        Map<String, Object> happiness = new LinkedHashMap<>();
        happiness.put("score", score);
        happiness.put("label", label);
        happiness.put("needsMeUnread", needsMe);
        happiness.put("waitingOver48h", waiting);
        happiness.put("message", message);
        happiness.put("waitingOnOthers", waitingOnOthers);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lanes", laneOut);
        out.put("accounts", acc);
        out.put("happiness", happiness);
        out.put("scheduled", outbox.count(userId));
        return out;
    }

    @GetMapping("/messages/{id}")
    public Map<String, Object> get(@RequestAttribute long userId, @PathVariable long id) {
        MessageRepo.Row r = actions.owned(userId, id);
        List<MessageRepo.Row> thread = messages.thread(r.accountId(), r.threadKey());
        List<Long> ids = new java.util.ArrayList<>(thread.stream().map(MessageRepo.Row::id).toList());
        if (!ids.contains(r.id())) ids.add(r.id());
        java.util.Set<String> inBox = new java.util.HashSet<>(box.savedKeys(ids));
        if (r.backAt() != null) messages.clearBack(r.id());
        Map<String, Object> out = r.summary();
        out.put("followUpAt", followUps.due(r.id()).map(Instant::toString).orElse(null));
        out.put("note", notes.find(r.accountId(), r.threadKey()).map(NoteRepo.Note::json).orElse(null));
        out.put("invite", messages.calendar(r.id()).isPresent() || hasIcs(r));
        if ("SENT".equals(r.folder())) {
            waiting.list(userId, r.accountId(), Instant.now(), false).stream().filter(w -> w.row().id() == r.id()).findFirst()
                    .ifPresent(w -> {
                        out.put("waitingDays", w.days());
                        out.put("waitingFor", w.why());
                    });
        }
        out.put("bodyHtml", r.bodyHtml());
        out.put("bodyText", r.bodyText());
        out.put("attachments", withBox(r, inBox));
        out.put("thread", thread.stream().map(t -> {
            Map<String, Object> m = t.summary();
            m.put("bodyHtml", t.bodyHtml());
            m.put("bodyText", t.bodyText());
            m.put("attachments", withBox(t, inBox));
            return m;
        }).toList());
        return out;
    }

    /** A calendar invite sent as a file (invite.ics). */
    static boolean hasIcs(MessageRepo.Row r) {
        if (r.attachments() == null) return false;
        return r.attachments().stream().anyMatch(a -> {
            String type = String.valueOf(a.getOrDefault("contentType", "")).toLowerCase(java.util.Locale.ROOT);
            String name = String.valueOf(a.getOrDefault("name", "")).toLowerCase(java.util.Locale.ROOT);
            return type.equals("text/calendar") || type.equals("application/ics") || name.endsWith(".ics");
        });
    }

    /** The message's attachments, each marked inBox when it was saved to Magizhchi Box. */
    private static List<Map<String, Object>> withBox(MessageRepo.Row r, java.util.Set<String> inBox) {
        if (r.attachments() == null) return List.of();
        return r.attachments().stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>(a);
            Object idx = a.get("index");
            m.put("inBox", idx instanceof Number n && inBox.contains(r.id() + ":" + n.intValue()));
            return m;
        }).toList();
    }

    @GetMapping("/messages/{id}/attachments/{index}")
    public ResponseEntity<byte[]> attachment(@RequestAttribute long userId, @PathVariable long id, @PathVariable int index) {
        MailActions.Download d = actions.attachment(userId, id, index);
        MediaType type;
        try {
            type = MediaType.parseMediaType(d.contentType());
        } catch (Exception e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.name(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(type)
                .body(d.bytes());
    }

    @PostMapping("/messages/{id}/seen")
    public ResponseEntity<Void> seen(@RequestAttribute long userId, @PathVariable long id, @RequestBody SeenRequest req) {
        actions.setSeen(userId, id, req.seen() == null || req.seen());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/messages/{id}/flag")
    public ResponseEntity<Void> flag(@RequestAttribute long userId, @PathVariable long id, @RequestBody FlagRequest req) {
        actions.setFlagged(userId, id, req.flagged() == null || req.flagged());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/messages/{id}/archive")
    public ResponseEntity<Void> archive(@RequestAttribute long userId, @PathVariable long id) {
        actions.archive(userId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/messages/{id}/trash")
    public ResponseEntity<Void> trash(@RequestAttribute long userId, @PathVariable long id) {
        actions.trash(userId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/messages/{id}/move")
    public ResponseEntity<Void> move(@RequestAttribute long userId, @PathVariable long id, @RequestBody MoveRequest req) {
        String to = req.folder() == null ? "" : req.folder().trim();
        if (to.startsWith("F:")) {
            if (to.length() < 3 || to.length() > 290) throw ApiException.bad("Unknown folder.");
        } else {
            to = to.toUpperCase(java.util.Locale.ROOT);
            if (!MOVE_TO.contains(to)) throw ApiException.bad("Mail can be moved to Inbox, Archive, Trash, Spam or one of your folders.");
        }
        actions.moveTo(userId, id, to);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/messages/{id}/lane")
    public ResponseEntity<Void> lane(@RequestAttribute long userId, @PathVariable long id, @RequestBody LaneRequest req) {
        if (!LaneClassifier.isLane(req.lane())) throw ApiException.bad("Unknown lane.");
        MessageRepo.Row r = actions.owned(userId, id);
        if (Boolean.TRUE.equals(req.rememberSender()) && r.fromEmail() != null) {
            messages.putSenderRule(userId, r.fromEmail(), req.lane());
            messages.applySenderRule(userId, r.fromEmail(), req.lane());
        }
        messages.setLane(id, req.lane(), "user");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/messages/{id}/unsubscribe")
    public Map<String, Object> unsubscribe(@RequestAttribute long userId, @PathVariable long id) {
        return actions.unsubscribe(userId, id);
    }

    @GetMapping("/subscriptions")
    public List<Map<String, Object>> subscriptions(@RequestAttribute long userId) {
        return messages.subscriptions(userId);
    }

    // ── AI ────────────────────────────────────────────────────────

    @GetMapping("/ai/status")
    public Map<String, Object> aiStatus(@RequestAttribute long userId) {
        return ai.status(userId);
    }

    @PostMapping("/messages/{id}/summary")
    public JsonNode summary(@RequestAttribute long userId, @PathVariable long id) {
        return ai.summary(actions.owned(userId, id));
    }

    @PostMapping("/messages/{id}/drafts")
    public JsonNode drafts(@RequestAttribute long userId, @PathVariable long id) {
        return ai.drafts(actions.owned(userId, id));
    }

    // ── send ──────────────────────────────────────────────────────

    @PostMapping(value = "/send", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> send(@RequestAttribute long userId,
                                    @RequestPart("payload") String payload,
                                    @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        MailActions.Outgoing o;
        JsonNode tree;
        try {
            o = mapper.readValue(payload, MailActions.Outgoing.class);
            tree = mapper.readTree(payload);
        } catch (Exception e) {
            throw ApiException.bad("The message couldn't be read.");
        }
        List<MailActions.OutFile> uploads = new java.util.ArrayList<>(MailFiles.of(files));

        // Undo send and Send later: the mail waits in the outbox and the server sends it when it's due.
        if (tree.hasNonNull("sendAt") || tree.has("delaySeconds")) {
            Instant at;
            if (tree.hasNonNull("sendAt")) {
                try {
                    at = OffsetDateTime.parse(tree.path("sendAt").asText()).toInstant();
                } catch (Exception e) {
                    throw ApiException.bad("That send time couldn't be read.");
                }
            } else {
                at = Instant.now().plusSeconds(Math.max(0, Math.min(Outbox.MAX_DELAY_SECONDS, tree.path("delaySeconds").asInt(0))));
            }
            Map<String, Object> v = new LinkedHashMap<>(outbox.view(outbox.queue(userId, (com.fasterxml.jackson.databind.node.ObjectNode) tree, o, uploads, at)));
            v.put("queued", true);
            return v;
        }

        // Files kept with the draft go too.
        JsonNode draftId = tree.path("draftId");
        if (draftId.canConvertToLong() && drafts.find(userId, draftId.asLong()).isPresent()) uploads.addAll(mailFiles.loadDraft(draftId.asLong()));
        MessageRepo.Row sent = actions.deliver(userId, o, uploads);
        people.forget(userId);              // who they write to just changed
        // The draft it came from is done with.
        if (draftId.canConvertToLong()) drafts.delete(userId, draftId.asLong());
        // "Remind me if nobody replies by …"
        String remindAt = tree.path("remindAt").asText(null);
        if (sent != null && remindAt != null && !remindAt.isBlank()) {
            try {
                followUps.set(userId, sent.id(), NoteController.future(remindAt, "reminder"));
            } catch (ApiException ignored) {
                // The mail is sent either way; a bad reminder time isn't worth failing it.
            }
        }
        return sent == null ? Map.of("status", "sent") : sent.summary();
    }
}
