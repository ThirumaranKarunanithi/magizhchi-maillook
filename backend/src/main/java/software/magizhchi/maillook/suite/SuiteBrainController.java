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
import software.magizhchi.maillook.mail.MailActions;
import software.magizhchi.maillook.mail.Outbox;
import software.magizhchi.maillook.mail.MailActions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
    private final Outbox outbox;
    private final MailActions mailActions;
    private final ObjectMapper mapper;
    private final String provisionSecret;

    public SuiteBrainController(SuiteDelegationVerifier verifier, JdbcTemplate jdbc, MessageRepo messages,
                                Waiting waiting, DraftRepo drafts, AccountRepo accounts,
                                Outbox outbox, MailActions mailActions, ObjectMapper mapper,
                                @Value("${magizhchi.provision-secret:}") String provisionSecret) {
        this.verifier = verifier;
        this.jdbc = jdbc;
        this.messages = messages;
        this.waiting = waiting;
        this.drafts = drafts;
        this.accounts = accounts;
        this.outbox = outbox;
        this.mailActions = mailActions;
        this.mapper = mapper;
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
                tool("send_mail", "danger",
                        "Send a new email from the user's own MailLook mailbox. It goes out after a short undo window, "
                                + "or at the time you give in sendAt. Only call this once you know the recipient's real email "
                                + "address; write a clear subject and a complete, polite body in the user's voice, signed with "
                                + "their first name. The user is shown the email and presses Send, so never say it was sent "
                                + "until the result says so.",
                        Map.of(
                                "to", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "Recipient email addresses (1-5)."),
                                "subject", prop("string", "Subject line."),
                                "body", prop("string", "Plain-text body."),
                                "sendAt", prop("string", "Optional. Send later at this exact time, ISO-8601 UTC like 2026-11-09T03:30:00Z (at least a minute away)."),
                                "remindIfNoReplyDays", prop("integer", "Optional, 1-30. Remind the user if nobody has replied after this many days.")),
                        List.of("to", "subject", "body")),
                tool("reply_mail", "danger",
                        "Reply to an email in the user's inbox (keeps the conversation together). Use search_mail first to get "
                                + "the message id, and read_mail if you need what it says. Pass the sender's address as to and "
                                + "the original subject as subject. The user is shown the reply and presses Send.",
                        Map.of(
                                "messageId", prop("integer", "The id of the email being replied to (from search_mail)."),
                                "to", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "Who the reply goes to; normally just the original sender."),
                                "subject", prop("string", "The original subject (Re: is added for you)."),
                                "body", prop("string", "Plain-text reply, signed with the user's first name."),
                                "remindIfNoReplyDays", prop("integer", "Optional, 1-30. Remind the user if there is no answer.")),
                        List.of("messageId", "to", "body")),
                tool("read_mail", "read",
                        "Read one email from the inbox (the first few paragraphs) so you can summarise it or write a reply.",
                        Map.of("id", prop("integer", "The message id from search_mail or needs_reply.")),
                        List.of("id")),
                flag(tool("cancel_send", "write",
                        "Take back a mail that is still in its undo window; it returns to Drafts. Used by Undo.",
                        Map.of("outboxId", prop("integer", "The queued mail's id.")),
                        List.of("outboxId")), "hidden"),
                flag(tool("known_recipients", "read",
                        "Which of these addresses the user has emailed before (from their Sent folder).",
                        Map.of("to", Map.of("type", "array", "items", Map.of("type", "string"),
                                "description", "Email addresses to check.")),
                        List.of("to")), "hidden"),
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
            case "send_mail" -> {
                if (!who.canWrite()) throw new ApiException(HttpStatus.FORBIDDEN, "This token is read-only.");
                yield sendMail(userId, input);
            }
            case "reply_mail" -> {
                if (!who.canWrite()) throw new ApiException(HttpStatus.FORBIDDEN, "This token is read-only.");
                yield replyMail(userId, input);
            }
            case "read_mail" -> readMail(userId, input);
            case "cancel_send" -> {
                if (!who.canWrite()) throw new ApiException(HttpStatus.FORBIDDEN, "This token is read-only.");
                yield cancelSend(userId, input);
            }
            case "known_recipients" -> knownRecipients(userId, input);
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

    /** Undo window for mail sent by the assistant (seconds). */
    static final int UNDO_SECONDS = 20;
    private static final java.util.regex.Pattern ADDRESS = java.util.regex.Pattern.compile("^[^@\\s<>,;\"]+@[^@\\s<>,;\"]+\\.[^@\\s<>,;\"]+$");

    private static List<String> addresses(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                String a = o == null ? "" : o.toString().trim();
                if (ADDRESS.matcher(a).matches() && a.length() < 200 && !out.contains(a)) out.add(a);
            }
        }
        return out;
    }

    /**
     * Sends mail from the person's own mailbox. It waits in the outbox for {@link #UNDO_SECONDS} seconds (or
     * until sendAt), so Undo can still take it back to Drafts. PA only calls this after the person pressed Send.
     */
    private Object sendMail(long userId, Map<String, Object> input) {
        List<String> to = addresses(input.get("to"));
        if (to.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "At least one valid recipient address is needed.");
        String subject = input.get("subject") == null ? "" : input.get("subject").toString().trim();
        String body = input.get("body") == null ? "" : input.get("body").toString();
        return queueMail(userId, to, subject, body, null, input);
    }

    /** Reply in the same conversation; the recipient defaults to the original sender. */
    private Object replyMail(long userId, Map<String, Object> input) {
        long id;
        try {
            id = (long) Double.parseDouble(String.valueOf(input.get("messageId")));
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "messageId is required.");
        }
        MessageRepo.Row original = mailActions.owned(userId, id);
        List<String> to = addresses(input.get("to"));
        if (to.isEmpty() && original.fromEmail() != null && ADDRESS.matcher(original.fromEmail()).matches()) {
            to = List.of(original.fromEmail());
        }
        if (to.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Who should the reply go to?");
        String base = input.get("subject") == null || input.get("subject").toString().isBlank()
                ? (original.subject() == null ? "" : original.subject()) : input.get("subject").toString().trim();
        String subject = base.regionMatches(true, 0, "re:", 0, 3) ? base : "Re: " + base;
        String body = input.get("body") == null ? "" : input.get("body").toString();
        return queueMail(userId, to, subject, body, id, input);
    }

    private Object queueMail(long userId, List<String> to, String subject, String body, Long replyTo, Map<String, Object> input) {
        if (to.size() > 5) throw new ApiException(HttpStatus.BAD_REQUEST, "The assistant can send to 5 people at a time at most.");
        if (subject.isBlank() && body.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "The email is empty.");
        if (subject.length() > 300 || body.length() > 20_000) throw new ApiException(HttpStatus.BAD_REQUEST, "That email is too long.");
        var mailboxes = accounts.forUser(userId);
        if (mailboxes.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Add a mailbox in MailLook first.");
        long accountId = mailboxes.get(0).id();
        if (replyTo != null) accountId = mailActions.owned(userId, replyTo).accountId();

        Instant now = Instant.now();
        Instant at = now.plusSeconds(UNDO_SECONDS);
        boolean later = false;
        Object when = input.get("sendAt");
        if (when != null && !when.toString().isBlank()) {
            try {
                Instant t = Instant.parse(when.toString().trim());
                if (t.isBefore(now.plusSeconds(60))) throw new ApiException(HttpStatus.BAD_REQUEST, "Send-later needs a time at least a minute from now.");
                if (t.isAfter(now.plus(Duration.ofDays(366)))) throw new ApiException(HttpStatus.BAD_REQUEST, "Mail can be scheduled up to a year ahead.");
                at = t;
                later = true;
            } catch (java.time.format.DateTimeParseException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "sendAt must be a UTC time like 2026-11-09T03:30:00Z.");
            }
        }

        MailActions.Outgoing o = new MailActions.Outgoing(accountId, to, List.of(), List.of(), subject, body, replyTo, null);
        ObjectNode payload = mapper.valueToTree(o);
        int days = clamp(input.get("remindIfNoReplyDays"), 0, 30, 0);
        if (days > 0) payload.put("remindAt", at.plus(Duration.ofDays(days)).toString());
        var queued = outbox.queue(userId, payload, o, List.of(), at);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("outboxId", queued.id());
        out.put("to", to);
        out.put("undoSeconds", later ? 0 : UNDO_SECONDS);
        if (later) out.put("sendAt", at.toString());
        if (days > 0) out.put("remindInDays", days);
        out.put("message", (later ? "Scheduled for " + at + " to " : "Sending in " + UNDO_SECONDS + " seconds to ")
                + String.join(", ", to) + ". You can still undo it."
                + (days > 0 ? " I'll remind you if there's no reply in " + days + " day" + (days == 1 ? "" : "s") + "." : ""));
        out.put("undo", Map.of("tool", "cancel_send", "input", Map.of("outboxId", queued.id())));
        return out;
    }

    /** The start of one email, for summarising or writing a reply. Kept short: mail is private. */
    private Object readMail(long userId, Map<String, Object> input) {
        long id;
        try {
            id = (long) Double.parseDouble(String.valueOf(input.get("id")));
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "id is required.");
        }
        MessageRepo.Row r = mailActions.owned(userId, id);
        Map<String, Object> m = new LinkedHashMap<>(brief(r));
        m.put("to", r.to());
        String text = r.bodyText() == null || r.bodyText().isBlank() ? r.snippet() : r.bodyText();
        m.put("body", cut(text, 2500));
        return m;
    }

    private Object cancelSend(long userId, Map<String, Object> input) {
        long id;
        try {
            id = (long) Double.parseDouble(String.valueOf(input.get("outboxId")));
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "outboxId is required.");
        }
        outbox.takeBack(userId, id);   // 409 "Too late" once it has gone
        return Map.of("ok", true, "message", "Taken back. It is in your MailLook drafts.");
    }

    /** Which addresses appear in the person's Sent mail (used by PA's "only ask for new people" setting). */
    private Object knownRecipients(long userId, Map<String, Object> input) {
        List<String> known = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String a : addresses(input.get("to"))) {
            String like = "%" + a.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM (SELECT 1 FROM messages m JOIN accounts a ON a.id = m.account_id " +
                            "WHERE a.user_id = ? AND m.folder = 'SENT' AND LOWER(m.to_list) LIKE ? LIMIT 1) t",
                    Integer.class, userId, like);
            (n != null && n > 0 ? known : unknown).add(a);
        }
        return Map.of("known", known, "unknown", unknown);
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
