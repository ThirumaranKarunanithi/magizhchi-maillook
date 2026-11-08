package software.magizhchi.maillook.suite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.magizhchi.maillook.common.ApiException;
import software.magizhchi.maillook.mail.LaneClassifier;
import software.magizhchi.maillook.mail.MailSearch;
import software.magizhchi.maillook.mail.MessageRepo;
import software.magizhchi.maillook.account.AccountRepo;
import software.magizhchi.maillook.mail.DraftRepo;
import software.magizhchi.maillook.mail.Waiting;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Suite Brain connector: what Magizhchi PA can read from MailLook for a person (read-only).
 *
 * <p>{@code GET /api/suite/manifest} (suite secret) lists the tools; {@code POST /api/suite/tools/{name}}
 * runs one with a delegation token. Mail is private, so this connector can only <em>read</em>: it never
 * sends, deletes or moves anything, and message bodies are shortened to a few lines.
 */
@RestController
@RequestMapping("/api/suite")
public class SuiteBrainController {

    private static final int MAX_ROWS = 15;

    private final SuiteDelegationVerifier verifier;
    private final JdbcTemplate jdbc;
    private final MessageRepo messages;
    private final Waiting waiting;
    private final DraftRepo drafts;
    private final AccountRepo accounts;
    private final String provisionSecret;

    public SuiteBrainController(SuiteDelegationVerifier verifier, JdbcTemplate jdbc, MessageRepo messages,
                                Waiting waiting, DraftRepo drafts, AccountRepo accounts,
                                @Value("${magizhchi.provision-secret:}") String provisionSecret) {
        this.verifier = verifier;
        this.jdbc = jdbc;
        this.messages = messages;
        this.waiting = waiting;
        this.drafts = drafts;
        this.accounts = accounts;
        this.provisionSecret = provisionSecret == null ? "" : provisionSecret;
    }

    @GetMapping("/manifest")
    public Map<String, Object> manifest(@RequestHeader(value = "X-Provision-Secret", required = false) String secret) {
        if (provisionSecret.isBlank() || !MessageDigest.isEqual(
                provisionSecret.getBytes(StandardCharsets.UTF_8),
                (secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Invalid suite secret.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("app", SuiteDelegationVerifier.AUDIENCE);
        out.put("version", 1);
        out.put("tools", List.of(
                flag(tool("needs_reply", "read",
                        "Emails that need the user's reply or attention (the 'Needs me' lane, unread, last two weeks), "
                                + "plus people the user is still waiting to hear back from. Use this for a daily briefing "
                                + "or when asked 'who is waiting on me?'.",
                        Map.of(), List.of()), "briefing"),
                flag(tool("person_timeline", "read",
                        "Recent inbox mail from one person, newest first.",
                        Map.of("name", prop("string", "The person's name or email, e.g. Priya.")),
                        List.of("name")), "personSearch"),
                tool("search_mail", "read",
                        "Search the user's inbox. Understands plain text plus filters like from:name, is:unread.",
                        Map.of(
                                "q", prop("string", "Search text, e.g. 'invoice from:priya'."),
                                "unread", prop("boolean", "Only unread mail."),
                                "limit", prop("integer", "How many to return, 1-15 (default 8).")),
                        List.of()),
                tool("save_draft", "write",
                        "Save an email as a draft in MailLook for the user to review and send themselves. Nothing is sent.",
                        Map.of(
                                "to", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "Recipient email addresses."),
                                "subject", prop("string", "Subject line."),
                                "body", prop("string", "Plain-text body.")),
                        List.of("to", "subject", "body")),
                flag(tool("discard_draft", "write",
                        "Delete a draft this assistant saved. Used by Undo.",
                        Map.of("draftId", prop("integer", "The draft's id.")),
                        List.of("draftId")), "hidden")));
        return out;
    }

    @PostMapping("/tools/{name}")
    public Object run(@PathVariable String name,
                      @RequestBody(required = false) Map<String, Object> body,
                      @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Missing bearer token.");
        }
        var who = verifier.verify(authorization.substring(7).trim());
        Map<String, Object> input = body != null && body.get("input") instanceof Map<?, ?> m ? castMap(m) : Map.of();

        List<Long> ids = jdbc.queryForList("SELECT id FROM users WHERE LOWER(email) = LOWER(?)", Long.class, who.email());
        if (ids.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "No MailLook account for this person.");
        }
        long userId = ids.get(0);

        return switch (name) {
            case "needs_reply" -> needsReply(userId);
            case "search_mail" -> searchMail(userId, input);
            case "person_timeline" -> personTimeline(userId, input);
            case "save_draft" -> {
                if (!who.canWrite()) throw new ApiException(HttpStatus.FORBIDDEN, "This token is read-only.");
                yield saveDraft(userId, input);
            }
            case "discard_draft" -> {
                if (!who.canWrite()) throw new ApiException(HttpStatus.FORBIDDEN, "This token is read-only.");
                yield discardDraft(userId, input);
            }
            default -> throw new ApiException(HttpStatus.NOT_FOUND, "Unknown tool: " + name);
        };
    }

    private Object needsReply(long userId) {
        Instant now = Instant.now();
        Set<Long> seen = new HashSet<>();
        List<MessageRepo.Row> needs = new ArrayList<>();
        for (MessageRepo.Row b : messages.back(userId, null, LaneClassifier.NEEDS_ME,
                OffsetDateTime.ofInstant(now.minus(Duration.ofDays(14)), ZoneOffset.UTC))) {
            if (seen.add(b.id())) needs.add(b);
        }
        MailSearch unread = new MailSearch(null, null, false, true, false, now.minus(Duration.ofDays(14)), null);
        for (MessageRepo.Row r : messages.list(userId, "INBOX", LaneClassifier.NEEDS_ME, null, unread, null, null, 12)) {
            if (seen.add(r.id())) needs.add(r);
        }
        long total = messages.unseenInLane(userId, null, LaneClassifier.NEEDS_ME,
                now.minus(Duration.ofDays(30)), now.plus(Duration.ofDays(1)));

        List<Waiting.Item> waits = waiting.list(userId, null, now, false);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("needsReplyTotal", total);
        out.put("needsReply", needs.stream().limit(8).map(SuiteBrainController::brief).toList());
        out.put("waitingForOthersTotal", waits.size());
        out.put("waitingForOthers", waits.stream().limit(5).map(w -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("to", Waiting.who(w.row()));
            m.put("subject", w.row().subject());
            m.put("daysWaiting", w.days());
            return m;
        }).toList());
        return out;
    }

    private Object searchMail(long userId, Map<String, Object> input) {
        String q = input.get("q") == null ? "" : input.get("q").toString();
        MailSearch parsed = MailSearch.parse(q, ZoneId.of("UTC"), Instant.now());
        if (Boolean.TRUE.equals(input.get("unread"))) {
            parsed = parsed.with(null, null, true, null, null, null);
        }
        int limit = clamp(input.get("limit"), 1, MAX_ROWS, 8);
        List<Map<String, Object>> found = messages.list(userId, "INBOX", null, null, parsed, null, null, limit)
                .stream().map(SuiteBrainController::brief).toList();
        return Map.of("count", found.size(), "messages", found);
    }

    /** The shape PA's person timeline expects: {"items":[{when,title,detail,kind}]}. */
    private Object personTimeline(long userId, Map<String, Object> input) {
        String name = input.get("name") == null ? "" : input.get("name").toString().trim();
        if (name.length() < 2) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "name is required.");
        }
        MailSearch from = new MailSearch(null, name, false, false, false, null, null);
        List<Map<String, Object>> items = messages.list(userId, "INBOX", null, null, from, null, null, 15).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("when", r.receivedAt() == null ? null : r.receivedAt().toInstant().toString());
            m.put("title", r.subject() == null || r.subject().isBlank() ? "(no subject)" : r.subject());
            m.put("detail", cut(r.snippet(), 160));
            m.put("kind", "mail");
            return m;
        }).toList();
        return Map.of("items", items);
    }

    /** Saves a draft for the person to review. This connector can never send mail. */
    private Object saveDraft(long userId, Map<String, Object> input) {
        List<String> to = new ArrayList<>();
        if (input.get("to") instanceof List<?> list) {
            for (Object o : list) {
                String a = o == null ? "" : o.toString().trim();
                if (a.contains("@") && a.length() < 200) to.add(a);
            }
        }
        if (to.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "At least one valid recipient address is needed.");
        String subject = input.get("subject") == null ? "" : input.get("subject").toString().trim();
        String body = input.get("body") == null ? "" : input.get("body").toString();
        if (body.length() > 20_000) throw new ApiException(HttpStatus.BAD_REQUEST, "That email is too long for a draft.");
        var mailboxes = accounts.forUser(userId);
        if (mailboxes.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Add a mailbox in MailLook first.");
        long id = drafts.insert(userId, new DraftRepo.Draft(0, userId, mailboxes.get(0).id(), "new", null,
                to, List.of(), List.of(), subject, body, null, null), Instant.now());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("draftId", id);
        out.put("message", "Draft saved in MailLook. Open MailLook to review and send it.");
        out.put("undo", Map.of("tool", "discard_draft", "input", Map.of("draftId", id)));
        return out;
    }

    private Object discardDraft(long userId, Map<String, Object> input) {
        long id;
        try {
            id = (long) Double.parseDouble(String.valueOf(input.get("draftId")));
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "draftId is required.");
        }
        if (!drafts.delete(userId, id)) throw new ApiException(HttpStatus.NOT_FOUND, "That draft is already gone.");
        return Map.of("ok", true, "message", "Draft discarded.");
    }

    private static Map<String, Object> brief(MessageRepo.Row r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("from", r.fromName() == null || r.fromName().isBlank() ? r.fromEmail() : r.fromName() + " <" + r.fromEmail() + ">");
        m.put("subject", r.subject());
        m.put("snippet", cut(r.snippet(), 200));
        m.put("receivedAt", r.receivedAt() == null ? null : r.receivedAt().toInstant().toString());
        m.put("unread", !r.seen());
        m.put("flagged", r.flagged());
        m.put("hasAttachments", r.attachments() != null && !r.attachments().isEmpty());
        return m;
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    private static int clamp(Object raw, int min, int max, int fallback) {
        if (raw == null) return fallback;
        try {
            return Math.max(min, Math.min(max, (int) Double.parseDouble(raw.toString())));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static Map<String, Object> tool(String name, String risk, String description,
                                            Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("risk", risk);
        t.put("description", description);
        t.put("inputSchema", schema);
        return t;
    }

/** Marks a manifest tool with a PA hint ("briefing": part of My Day, "personSearch": feeds person timelines). */
    private static Map<String, Object> flag(Map<String, Object> tool, String key) {
        tool.put(key, true);
        return tool;
    }

    private static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }
}
