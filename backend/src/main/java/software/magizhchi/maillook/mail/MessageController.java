package software.magizhchi.maillook.mail;

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

    private static final Set<String> FOLDERS = Set.of("INBOX", "SENT", "ARCHIVE", "TRASH");

    private final MessageRepo messages;
    private final MailActions actions;
    private final AiService ai;
    private final ObjectMapper mapper;

    public MessageController(MessageRepo messages, MailActions actions, AiService ai, ObjectMapper mapper) {
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
                                    @RequestParam(defaultValue = "50") int limit) {
        String f = folder.toUpperCase();
        if (!FOLDERS.contains(f)) throw ApiException.bad("Unknown folder.");
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
        List<MessageRepo.Row> rows = messages.list(userId, f, l, accountId, q, cAt, cId, n);
        String next = null;
        if (rows.size() == n) {
            MessageRepo.Row last = rows.get(rows.size() - 1);
            next = last.receivedAt().toInstant().toEpochMilli() + ":" + last.id();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", rows.stream().map(MessageRepo.Row::summary).toList());
        out.put("nextCursor", next);
        return out;
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

        Map<String, Object> happiness = new LinkedHashMap<>();
        happiness.put("score", score);
        happiness.put("label", label);
        happiness.put("needsMeUnread", needsMe);
        happiness.put("waitingOver48h", waiting);
        happiness.put("message", message);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lanes", laneOut);
        out.put("accounts", acc);
        out.put("happiness", happiness);
        return out;
    }

    @GetMapping("/messages/{id}")
    public Map<String, Object> get(@RequestAttribute long userId, @PathVariable long id) {
        MessageRepo.Row r = actions.owned(userId, id);
        Map<String, Object> out = r.summary();
        out.put("bodyHtml", r.bodyHtml());
        out.put("bodyText", r.bodyText());
        out.put("attachments", r.attachments());
        out.put("thread", messages.thread(r.accountId(), r.threadKey()).stream().map(t -> {
            Map<String, Object> m = t.summary();
            m.put("bodyHtml", t.bodyHtml());
            m.put("bodyText", t.bodyText());
            m.put("attachments", t.attachments());
            return m;
        }).toList());
        return out;
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
        try {
            o = mapper.readValue(payload, MailActions.Outgoing.class);
        } catch (Exception e) {
            throw ApiException.bad("The message couldn't be read.");
        }
        MessageRepo.Row sent = actions.send(userId, o, files == null ? List.of() : files);
        return sent == null ? Map.of("status", "sent") : sent.summary();
    }
}
