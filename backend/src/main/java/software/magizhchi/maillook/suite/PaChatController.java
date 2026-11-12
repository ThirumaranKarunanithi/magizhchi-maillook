package software.magizhchi.maillook.suite;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.magizhchi.maillook.ai.AiService;
import software.magizhchi.maillook.common.ApiException;
import software.magizhchi.maillook.contacts.ContactsController;
import software.magizhchi.maillook.mail.MessageRepo;
import software.magizhchi.maillook.mail.Sorting;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Magizhchi PA's chat inside MailLook. A request about the app itself (open a lane, find and open a mail,
 * archive it, write a reply) becomes a list of actions the page carries out, the same as pressing its
 * buttons. Anything else (calendar, tasks, notes, contacts, "send it for me") goes to PA's own assistant,
 * which can also read and send mail through MailLook's suite tools.
 */
@RestController
@RequestMapping("/api/assistant")
public class PaChatController {

    public record Turn(String role, String content) {}

    /** listIds: the mails in the list on screen, top first, so "the railway one" or "the latest" can be found. */
    public record Context(String page, String view, Long openMessageId, List<Long> listIds) {}

    public record ChatRequest(String message, List<Turn> history, Context context, Boolean voice, String tz, String language) {}

    public record TranscribeRequest(String audio, String mimeType, String language) {}

    static final Set<String> PLACES = Set.of("needs_me", "fyi", "can_wait", "all", "sent", "archive", "spam", "trash", "drafts",
            "snoozed", "waiting", "today", "files", "bills", "forms", "subscriptions", "calendar", "contacts", "accounts", "scheduled");
    static final Set<String> ACTS = Set.of("archive", "trash", "spam", "flag", "unflag", "read", "unread", "snooze", "lane");
    static final Set<String> LANES = Set.of("needs_me", "fyi", "can_wait");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@<>\",;]+@[^\\s@<>\",;]+\\.[^\\s@<>\",;]{2,}$");

    private final AiService ai;
    private final PaClient pa;
    private final MessageRepo messages;
    private final ContactsController contacts;
    private final JdbcTemplate jdbc;
    private final Sorting sorting;

    public PaChatController(AiService ai, PaClient pa, MessageRepo messages, ContactsController contacts, JdbcTemplate jdbc,
                            Sorting sorting) {
        this.sorting = sorting;
        this.ai = ai;
        this.pa = pa;
        this.messages = messages;
        this.contacts = contacts;
        this.jdbc = jdbc;
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestAttribute long userId, @RequestBody ChatRequest req) {
        String message = req.message() == null ? "" : req.message().trim();
        if (message.isEmpty()) throw ApiException.bad("Say or type what you'd like.");
        if (message.length() > 2000) throw ApiException.bad("That's a long message. Keep it under 2000 characters.");
        if (!ai.enabled(userId)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Magizhchi PA's AI isn't on for you yet. Sign in with Magizhchi ID and switch on AI in Magizhchi PA.");
        }
        ZoneId zone = zone(req.tz());
        List<Turn> history = history(req.history());
        Context ctx = req.context() == null ? new Context(null, null, null, null) : req.context();
        MessageRepo.Row open = ctx.openMessageId() == null ? null : messages.find(userId, ctx.openMessageId()).orElse(null);

        Seen seen = seen(userId, ctx);
        JsonNode plan = ai.appPlan(userId, system(zone), prompt(userId, message, history, ctx, open, seen, zone));

        Map<String, Object> out = new LinkedHashMap<>();
        if (plan.path("askPa").asBoolean(false) && pa.configured()) {
            out.putAll(askPa(userId, message, history, req, zone));
            return out;
        }
        List<String> notes = new ArrayList<>();
        List<Map<String, Object>> actions = new ArrayList<>();
        // "it" after "open the invoice" means the invoice: each opened mail becomes the one on screen.
        MessageRepo.Row[] onScreen = {open};
        for (JsonNode a : plan.path("actions")) {
            Map<String, Object> act = resolve(userId, a, onScreen, zone, notes);
            if (act != null) actions.add(act);
            if (actions.size() >= 6) break;
        }
        String say = text(plan, "say");
        // "Okay, marking it read" with nothing done reads like a lie: then say only what went wrong
        if (!notes.isEmpty()) say = actions.isEmpty() && plan.path("actions").size() > 0 ? String.join(" ", notes)
                : (say == null ? "" : say + " ") + String.join(" ", notes);
        out.put("by", "maillook");
        out.put("reply", say == null || say.isBlank() ? (actions.isEmpty() ? "I'm not sure how to do that here. Try saying it another way." : "Done.") : say.trim());
        out.put("actions", actions);
        return out;
    }

    // ── what the page does ────────────────────────────────────────────

    private Map<String, Object> resolve(long userId, JsonNode a, MessageRepo.Row[] onScreen, ZoneId zone, List<String> notes) {
        MessageRepo.Row open = onScreen[0];
        String type = text(a, "type");
        if (type == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        switch (type) {
            case "go" -> {
                String to = lower(text(a, "to"));
                String sec = sectionLane(userId, text(a, "to"));
                if (sec != null) to = sec;
                else if (to == null || !PLACES.contains(to)) return null;
                m.put("to", to);
            }
            case "search" -> {
                String q = text(a, "q");
                if (q == null) return null;
                m.put("q", cut(q, 200));
            }
            case "open" -> {
                MessageRepo.Row row = target(userId, a, open);
                if (row == null) {
                    notes.add("I couldn't find that mail.");
                    return null;
                }
                mail(m, row);
                onScreen[0] = row;
            }
            case "mail" -> {
                String act = lower(text(a, "act"));
                if (act == null || !ACTS.contains(act)) return null;
                MessageRepo.Row row = target(userId, a, open);
                if (row == null) {
                    notes.add(open == null && meansOpen(a) ? "Open a mail first, or tell me which one." : "I couldn't find that mail.");
                    return null;
                }
                m.put("act", act);
                mail(m, row);
                if (act.equals("lane")) {
                    String lane = lower(text(a, "lane"));
                    String sec = sectionLane(userId, text(a, "lane"));
                    if (sec != null) lane = sec;
                    else if (lane == null || !LANES.contains(lane)) return null;
                    m.put("lane", lane);
                }
                if (act.equals("snooze")) {
                    String at = until(text(a, "until"), zone);
                    if (at == null) {
                        notes.add("Tell me until when, like \"tomorrow 9 am\".");
                        return null;
                    }
                    m.put("at", at);
                }
            }
            case "compose" -> {
                m.put("to", people(userId, a.path("to"), notes));
                m.put("cc", people(userId, a.path("cc"), notes));
                m.put("subject", cut(text(a, "subject"), 300));
                m.put("body", cut(text(a, "body"), 8000));
            }
            case "reply", "forward" -> {
                MessageRepo.Row row = target(userId, a, open);
                if (row == null) {
                    notes.add(open == null && meansOpen(a) ? "Open the mail to answer first, or tell me which one." : "I couldn't find that mail.");
                    return null;
                }
                mail(m, row);
                m.put("all", a.path("all").asBoolean(false));
                m.put("body", cut(text(a, "body"), 8000));
                if (type.equals("forward")) m.put("to", people(userId, a.path("to"), notes));
            }
            case "ask" -> {
                String q = text(a, "q");
                if (q == null) return null;
                m.put("q", cut(q, 500));
            }
            case "section" -> {
                // Done here, not by the page: the sidebar shows it on its next refresh.
                try {
                    Sorting.Section sec = sorting.createSection(userId, text(a, "name"), text(a, "icon"), null);
                    m.put("lane", sec.lane());
                    m.put("name", sec.name());
                    m.put("icon", sec.icon());
                } catch (ApiException e) {
                    notes.add(e.getMessage());
                    return null;
                }
            }
            case "rule" -> {
                try {
                    String lane = null;
                    String name = text(a, "section");
                    if (name != null) {
                        lane = sectionLane(userId, name);
                        if (lane == null && LANES.contains(lower(name))) lane = lower(name);
                        if (lane == null) lane = sorting.createSection(userId, name, text(a, "icon"), null).lane();
                    }
                    Sorting.Rule r = sorting.createRule(userId, new Sorting.RuleIn(text(a, "from"), text(a, "subject"), text(a, "to"),
                            text(a, "words"), lane, a.path("markRead").asBoolean(false), a.path("archive").asBoolean(false), true), "pa");
                    int applied = a.path("applyNow").asBoolean(true) ? sorting.applyExisting(userId, r.id()) : 0;
                    m.put("ruleId", r.id());
                    m.put("lane", r.lane());
                    m.put("section", lane == null ? null : sectionName(userId, lane));
                    m.put("applied", applied);
                    m.put("summary", describe(r, lane == null ? null : sectionName(userId, lane)));
                } catch (ApiException e) {
                    notes.add(e.getMessage());
                    return null;
                }
            }
            default -> {
                return null;
            }
        }
        return m;
    }

    /** "Meet notices", "s12" or a built-in lane's own name -> the person's section's lane, or null. */
    private String sectionLane(long userId, String name) {
        if (name == null) return null;
        String n = name.trim();
        if (Sorting.isSectionLane(n.toLowerCase(Locale.ROOT))) return sorting.isTarget(userId, n.toLowerCase(Locale.ROOT)) ? n.toLowerCase(Locale.ROOT) : null;
        Sorting.Section s = sorting.byName(userId, n);
        return s == null ? null : s.lane();
    }

    private String sectionName(long userId, String lane) {
        if (lane == null) return null;
        switch (lane) {
            case "needs_me": return "Needs me";
            case "fyi": return "For my info";
            case "can_wait": return "Can wait";
            default:
                for (Sorting.Section s : sorting.sections(userId)) if (s.lane().equals(lane)) return s.name();
                return lane;
        }
    }

    /** "Mail from no-reply@magizhchi.software goes to Meet notices, marked read." */
    static String describe(Sorting.Rule r, String section) {
        List<String> when = new ArrayList<>();
        if (r.from() != null) when.add("from " + r.from());
        if (r.subject() != null) when.add("with \"" + r.subject() + "\" in the subject");
        if (r.to() != null) when.add("sent to " + r.to());
        if (r.words() != null) when.add("mentioning \"" + r.words() + "\"");
        List<String> what = new ArrayList<>();
        if (section != null) what.add("goes to " + section);
        if (r.markRead()) what.add("is marked read");
        if (r.archive()) what.add("is archived");
        return "Mail " + String.join(", ", when) + " " + String.join(" and ", what) + ".";
    }

    private static void mail(Map<String, Object> m, MessageRepo.Row row) {
        m.put("messageId", row.id());
        m.put("subject", row.subject());
        m.put("from", row.fromName() != null && !row.fromName().isBlank() ? row.fromName() : row.fromEmail());
    }

    private static boolean meansOpen(JsonNode a) {
        return !a.path("find").isObject() && !a.path("which").isObject() && a.path("id").asLong(0) <= 0;
    }

    /** "which": "open" means the mail on screen; "find" searches for one. */
    private MessageRepo.Row target(long userId, JsonNode a, MessageRepo.Row open) {
        JsonNode which = a.path("which");
        JsonNode find = a.path("find");
        long id = a.path("id").asLong(which.isNumber() ? which.asLong() : find.path("id").asLong(0));
        if (id > 0) return messages.find(userId, id).orElse(null);
        boolean wantsOpen = "open".equalsIgnoreCase(which.asText("")) || (find.isMissingNode() || find.isNull()) && which.isMissingNode();
        if (find.isObject()) return find(userId, find);
        if (which.isObject()) return find(userId, which);
        return wantsOpen ? open : null;
    }

    MessageRepo.Row find(long userId, JsonNode f) {
        List<String> words = new ArrayList<>();
        for (JsonNode w : f.path("words")) if (w.isTextual() && w.asText().trim().length() >= 2) words.add(w.asText().trim().toLowerCase(Locale.ROOT));
        String from = text(f, "from");
        String folder = "SENT".equalsIgnoreCase(text(f, "folder")) ? "SENT" : "ALL";
        String lane = lower(text(f, "lane"));
        if (lane != null && !LANES.contains(lane)) lane = null;
        if (words.isEmpty() && from == null) {
            // "the latest mail in Needs me"
            if (lane == null) return null;
            List<Long> top = messages.laneIds(userId, null, lane, 1);
            return top.isEmpty() ? null : messages.find(userId, top.get(0)).orElse(null);
        }
        List<MessageRepo.Row> rows = messages.askSearch(userId, null, words, from, null, null, null, false, folder, 40);
        if (rows.isEmpty() && from != null && !words.isEmpty()) rows = messages.askSearch(userId, null, words, null, null, null, null, false, folder, 40);
        if (lane != null) {
            String l = lane;
            List<MessageRepo.Row> inLane = rows.stream().filter(r -> l.equals(r.lane()) && "INBOX".equals(r.folder())).toList();
            if (!inLane.isEmpty()) rows = inLane;
        }
        if (rows.isEmpty()) return null;
        // Most words matched first (subject and sender count double), newest among equals.
        return rows.stream().max(Comparator.comparingInt((MessageRepo.Row r) -> score(r, words))
                .thenComparing(r -> r.receivedAt() == null ? java.time.OffsetDateTime.MIN : r.receivedAt())).orElse(null);
    }

    private static int score(MessageRepo.Row r, List<String> words) {
        String head = ((r.subject() == null ? "" : r.subject()) + " " + (r.fromName() == null ? "" : r.fromName()) + " "
                + (r.fromEmail() == null ? "" : r.fromEmail())).toLowerCase(Locale.ROOT);
        String body = r.bodyText() == null ? "" : r.bodyText().toLowerCase(Locale.ROOT);
        int s = 0;
        for (String w : words) s += head.contains(w) ? 2 : body.contains(w) ? 1 : 0;
        return s;
    }

    /** Names become addresses from the address book; addresses stay as they are. */
    private List<String> people(long userId, JsonNode list, List<String> notes) {
        List<String> out = new ArrayList<>();
        if (!list.isArray()) return out;
        for (JsonNode p : list) {
            String s = p.asText("").trim();
            if (s.isEmpty() || out.size() >= 20) continue;
            if (EMAIL.matcher(s).matches()) {
                out.add(s);
                continue;
            }
            Object items = contacts.suggest(userId, s, null, null, 1).get("items");
            String email = items instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> first && first.get("email") != null
                    ? first.get("email").toString() : null;
            if (email != null) out.add(email);
            else notes.add("I don't know " + s + "'s address, so add it in To.");
        }
        return out;
    }

    // ── PA's own assistant ────────────────────────────────────────────

    private Map<String, Object> askPa(long userId, String message, List<Turn> history, ChatRequest req, ZoneId zone) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("history", history.stream().map(t -> Map.of("role", t.role(), "content", t.content())).toList());
        body.put("clientZone", zone.getId());
        body.put("client", "web");
        if (Boolean.TRUE.equals(req.voice())) body.put("voice", true);
        if (req.language() != null && !req.language().isBlank()) body.put("language", cut(req.language(), 32));
        PaClient.Reply r = pa.as(userId, "POST", "/api/v1/assistant/chat", body);
        if (r.code() == 503) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Magizhchi PA is busy or its AI is off. " + nz(r.message()));
        if (r.code() == 0) throw new ApiException(HttpStatus.BAD_GATEWAY, "Can't reach Magizhchi PA right now.");
        if (!r.ok() || r.json() == null) throw new ApiException(HttpStatus.BAD_GATEWAY, "Magizhchi PA answered " + r.code() + (r.message() == null ? "." : ": " + r.message()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("by", "pa");
        out.put("reply", r.json().path("reply").asText(""));
        List<Map<String, Object>> done = new ArrayList<>();
        for (JsonNode a : r.json().path("actions")) {
            Map<String, Object> x = new LinkedHashMap<>();
            for (String k : List.of("type", "summary", "decisionId", "activityId", "undoSeconds", "email", "subject", "body", "target")) {
                JsonNode v = a.path(k);
                if (!v.isMissingNode() && !v.isNull()) x.put(k, v.isNumber() ? v.numberValue() : v.asText());
            }
            List<String> files = new ArrayList<>();
            a.path("attachments").forEach(f -> files.add(f.asText()));
            if (!files.isEmpty()) x.put("attachments", files);
            done.add(x);
        }
        out.put("paActions", done);
        out.put("actions", List.of());
        return out;
    }

    /**
     * The person's Magizhchi PA plan (quick asks today, points this month) and where to upgrade, for the top of
     * the chat. Never fails the panel: when PA can't be asked it says so with available = false.
     */
    @GetMapping("/plan")
    public Map<String, Object> plan(@RequestAttribute long userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        String web = pa.webUrl() == null || pa.webUrl().isBlank() ? "https://pa.magizhchi.software" : pa.webUrl();
        out.put("upgradeUrl", web + "/pricing");
        out.put("available", false);
        if (!pa.configured()) return out;
        try {
            PaClient.Reply r = pa.as(userId, "GET", "/api/v1/plan", null);
            if (r.ok() && r.json() != null && r.json().isObject()) {
                r.json().fields().forEachRemaining(e -> out.put(e.getKey(),
                        e.getValue().isNull() ? null : e.getValue().isNumber() ? e.getValue().numberValue()
                                : e.getValue().isBoolean() ? e.getValue().asBoolean() : e.getValue().asText()));
                out.put("available", true);
            }
        } catch (RuntimeException ignored) {
            // the chat works without the plan line
        }
        return out;
    }

    /** Send or Discard for a mail PA is holding for the person's OK. */
    @PostMapping("/decisions/{id}/{answer}")
    public Object decide(@RequestAttribute long userId, @PathVariable String id, @PathVariable String answer) {
        if (!answer.equals("approve") && !answer.equals("decline")) throw ApiException.bad("Answer approve or decline.");
        return passOn(pa.as(userId, "POST", "/api/v1/decisions/" + uuid(id) + "/" + answer, answer.equals("approve") ? Map.of() : null));
    }

    /** Undo for a mail PA just sent. */
    @PostMapping("/activity/{id}/undo")
    public Object undo(@RequestAttribute long userId, @PathVariable String id) {
        return passOn(pa.as(userId, "POST", "/api/v1/suite/activity/" + uuid(id) + "/undo", null));
    }

    /** Speech to text through PA, which understands Tamil and English mixed. The audio isn't kept. */
    @PostMapping("/transcribe")
    public Map<String, Object> transcribe(@RequestAttribute long userId, @RequestBody TranscribeRequest req) {
        if (req.audio() == null || req.audio().isBlank()) throw ApiException.bad("Nothing was recorded.");
        if (req.audio().length() > 7_500_000) throw ApiException.bad("That recording is too long. Keep it under a minute.");
        String type = req.mimeType() == null ? "" : req.mimeType().toLowerCase(Locale.ROOT).replaceAll(";.*$", "").trim();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("audio", req.audio());
        body.put("mimeType", type);
        if (req.language() != null && !req.language().isBlank()) body.put("language", cut(req.language(), 32));
        PaClient.Reply r = pa.as(userId, "POST", "/api/v1/assistant/transcribe", body);
        if (r.code() == 503) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Speaking isn't switched on in Magizhchi PA yet. Type instead.");
        if (!r.ok() || r.json() == null) throw new ApiException(HttpStatus.BAD_GATEWAY, "Couldn't make out the recording" + (r.message() == null ? "." : ": " + r.message()));
        return Map.of("text", r.json().path("text").asText(""));
    }

    private static Object passOn(PaClient.Reply r) {
        if (r.code() == 0) throw new ApiException(HttpStatus.BAD_GATEWAY, "Can't reach Magizhchi PA right now.");
        if (!r.ok()) throw new ApiException(r.code() == 404 ? HttpStatus.NOT_FOUND : r.code() == 409 ? HttpStatus.CONFLICT : HttpStatus.BAD_GATEWAY,
                r.message() == null ? "Magizhchi PA answered " + r.code() + "." : r.message());
        return r.json() == null ? Map.of() : r.json();
    }

    // ── the prompt ────────────────────────────────────────────────────

    private static String system(ZoneId zone) {
        LocalDateTime now = LocalDateTime.now(zone).withSecond(0).withNano(0);
        return """
                You are Magizhchi PA, working inside Magizhchi MailLook (a mail app). Now is %s %s (%s).
                Turn the person's words into actions on the app. Reply with ONE JSON object:
                {"say": "a short, warm reply in the person's language (one or two sentences)", "actions": [...], "askPa": false}

                Actions (use only these):
                {"type":"go","to": one of needs_me, fyi, can_wait, all, sent, archive, spam, trash, drafts, snoozed, waiting,
                   today, files, bills, forms, subscriptions, calendar, contacts, accounts, scheduled}
                   (needs_me = "Needs me" lane, fyi = "For my info", can_wait = "Can wait", today = today's briefing, accounts = settings/mailboxes)
                {"type":"search","q":"words"}  show the mail list filtered by these words
                Point at a mail in one of three ways: "id": <a number from the mails listed below> (best), "which":"open" (the mail
                on screen), or "find":{"words":["subject or body words"],"from":"sender or null","folder":"ALL" or "SENT","lane":"needs_me, fyi, can_wait or null"}.
                {"type":"open","id":123}  (or "which"/"find" as above)
                {"type":"mail","act": archive|trash|spam|flag|unflag|read|unread|snooze|lane, "id":123 (or "which"/"find"),
                   "until":"YYYY-MM-DDTHH:MM" (snooze only, the person's local time), "lane": needs_me|fyi|can_wait (lane only)}
                {"type":"compose","to":["address or person's name"],"cc":[],"subject":"...","body":"the mail text, ready to send, no signature"}
                {"type":"reply","id":123 (or "which"/"find"),"all":false,"body":"the reply text, no signature"}
                {"type":"forward","id":123 (or "which"/"find"),"to":["..."],"body":"a short note or empty"}
                {"type":"ask","q":"the question"}  a question to answer from the person's mail (amounts, dates, who said what)
                {"type":"section","name":"Meet notices","icon":"🎥"}  make a new section in the sidebar (icon: one of %s)
                {"type":"rule","from":"sender address, @domain or name, or null","subject":"words in the subject or null",
                   "to":"recipient or null","words":"words anywhere in the mail or null","section":"a section's name (new ones are made),
                   or needs_me|fyi|can_wait, or null","icon":"for a new section","markRead":false,"archive":false,"applyNow":true}
                   an automation: from now on, mail that fits goes there by itself. applyNow also files the mail already here.
                   Commas in a condition mean "any of" (from:"amazon.in, flipkart.com").

                Rules:
                - "this mail", "it", "this one" mean the open mail (which:"open"); after you open a mail, "it" is that mail.
                - Use an id from the mails listed below whenever one fits: "the railway mail" = the one from Railway; "the latest",
                  "the last one", "the newest" = the top one (of the lane they name, or of the list on screen). Never invent ids.
                - Be decisive: when one mail clearly fits, act on it and name it in "say" (sender and subject).
                  Don't ask them to open a mail or to name it when the mails below already show it.
                - "yes", "ok", "do it", "go ahead" after you offered something means do exactly that now.
                - "say" tells what you did, in the past tense, without claiming more than the actions do.
                - Compose, reply and forward only open a draft on screen; the person presses Send. Write the whole text in body.
                - If the person asks what the open mail says, or to summarise or explain it, answer in "say" from its text below, with no action.
                - Several steps are fine, in order (for example open then archive).
                - Set "askPa": true, with no actions and empty "say", for: calendar, meetings, events, tasks, to-dos, reminders,
                  notes, phone calls, WhatsApp, contacts' numbers, files in Magizhchi Box, or when the person wants a mail SENT
                  right away without seeing a draft first, or anything that isn't about operating this mail app.
                - Only when two or more mails fit equally well, ask one short question in "say" that names them, with no actions.
                - "always put mail from X in a separate folder/section", "move all Y mails to Z from now on", "auto-archive",
                  "mark X as read automatically": make a rule (with a new section when they name one or none fits; name it
                  after what the mail is, e.g. "Meet notices", with a fitting icon). Never say you can't; say what the rule
                  does and how many mails it moved. The person can change it under Automations.
                - "go to <their section>" = {"type":"go","to":"<section name>"}; moving one mail there = act "lane" with "lane":"<section name>".
                """.formatted(now.toLocalDate(), now.toLocalTime(), now.getDayOfWeek(), String.join(" ", Sorting.ICONS));
    }

    /** The mails PA can point at by id: the list on screen, and the newest few in each lane. */
    record Seen(List<MessageRepo.Row> list, Map<String, List<MessageRepo.Row>> lanes) {}

    private Seen seen(long userId, Context ctx) {
        List<MessageRepo.Row> list = new ArrayList<>();
        if (ctx.listIds() != null) {
            for (Long id : ctx.listIds()) {
                if (id == null || list.size() >= 20) continue;
                messages.find(userId, id).ifPresent(list::add);
            }
        }
        Map<String, List<MessageRepo.Row>> lanes = new LinkedHashMap<>();
        for (String lane : List.of("needs_me", "fyi", "can_wait")) {
            List<MessageRepo.Row> rows = new ArrayList<>();
            for (Long id : messages.laneIds(userId, null, lane, 5)) messages.find(userId, id).ifPresent(rows::add);
            lanes.put(lane, rows);
        }
        return new Seen(list, lanes);
    }

    private static void line(StringBuilder p, MessageRepo.Row r, ZoneId zone) {
        p.append("  id ").append(r.id()).append(" · ").append(r.seen() ? "read" : "UNREAD").append(r.flagged() ? " · flagged" : "")
                .append(" · from ").append(cut(r.fromName() != null && !r.fromName().isBlank() ? r.fromName() : nz(r.fromEmail()), 60))
                .append(" · \"").append(cut(nz(r.subject()), 120)).append("\" · ")
                .append(r.receivedAt() == null ? "" : r.receivedAt().atZoneSameInstant(zone).toLocalDateTime().withSecond(0).withNano(0))
                .append(r.folder() != null && !r.folder().equals("INBOX") ? " · in " + r.folder() : "").append('\n');
    }

    private String prompt(long userId, String message, List<Turn> history, Context ctx, MessageRepo.Row open, Seen seen, ZoneId zone) {
        StringBuilder p = new StringBuilder();
        List<String> mine = jdbc.queryForList("SELECT email FROM accounts WHERE user_id = ? ORDER BY id", String.class, userId);
        p.append("Their mailboxes: ").append(String.join(", ", mine)).append('\n');
        List<Sorting.Section> secs = sorting.sections(userId);
        if (!secs.isEmpty()) {
            p.append("Their own sections: ").append(String.join(", ", secs.stream().map(s -> s.icon() + " " + s.name()).toList())).append('\n');
        }
        List<Sorting.Rule> rules = sorting.rules(userId);
        if (!rules.isEmpty()) {
            p.append("Their automations:\n");
            for (Sorting.Rule r : rules.subList(0, Math.min(rules.size(), 15))) {
                p.append("  ").append(r.enabled() ? "" : "(off) ").append(describe(r, sectionName(userId, r.lane()))).append('\n');
            }
        }
        p.append("On screen: ").append(ctx.page() == null ? "mail" : cut(ctx.page(), 40));
        if (ctx.view() != null) p.append(" · ").append(cut(ctx.view(), 60));
        p.append('\n');
        if (open != null) {
            p.append("The open mail: from ").append(nz(open.fromName())).append(" <").append(nz(open.fromEmail())).append(">, subject \"")
                    .append(nz(open.subject())).append("\", ").append(open.receivedAt() == null ? "" : open.receivedAt().atZoneSameInstant(zone).toLocalDate())
                    .append("\n---\n").append(cut(open.bodyText() == null ? nz(open.snippet()) : open.bodyText(), 3500)).append("\n---\n");
        } else {
            p.append("No mail is open.\n");
        }
        if (!seen.list().isEmpty()) {
            p.append("The list on screen, top first (the top one is the newest):\n");
            for (MessageRepo.Row r : seen.list()) line(p, r, zone);
        }
        Map<String, String> names = Map.of("needs_me", "Needs me", "fyi", "For my info", "can_wait", "Can wait");
        seen.lanes().forEach((lane, rows) -> {
            if (rows.isEmpty()) return;
            p.append("Newest in ").append(names.get(lane)).append(" (lane ").append(lane).append("), newest first:\n");
            for (MessageRepo.Row r : rows) line(p, r, zone);
        });
        if (!history.isEmpty()) {
            p.append("Earlier in this chat:\n");
            for (Turn t : history.subList(Math.max(0, history.size() - 8), history.size())) {
                p.append(t.role().equals("user") ? "Person: " : "You: ").append(cut(t.content(), 600)).append('\n');
            }
        }
        p.append("The person says: ").append(message);
        return p.toString();
    }

    // ── small helpers ─────────────────────────────────────────────────

    private static List<Turn> history(List<Turn> in) {
        List<Turn> out = new ArrayList<>();
        if (in == null) return out;
        for (Turn t : in) {
            if (t == null || t.content() == null || t.content().isBlank()) continue;
            String role = "assistant".equals(t.role()) ? "assistant" : "user";
            out.add(new Turn(role, cut(t.content(), 4000)));
        }
        return out.size() > 12 ? out.subList(out.size() - 12, out.size()) : out;
    }

    static String until(String local, ZoneId zone) {
        if (local == null) return null;
        try {
            LocalDateTime t = local.length() == 10 ? LocalDate.parse(local).atTime(9, 0) : LocalDateTime.parse(local.length() == 16 ? local + ":00" : local);
            var at = t.atZone(zone).toInstant();
            return at.isAfter(java.time.Instant.now()) ? at.toString() : null;
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static String uuid(String id) {
        try {
            return java.util.UUID.fromString(id).toString();
        } catch (IllegalArgumentException e) {
            throw ApiException.bad("That isn't a PA reference.");
        }
    }

    static ZoneId zone(String tz) {
        try {
            return tz == null || tz.isBlank() ? ZoneId.of("Asia/Kolkata") : ZoneId.of(tz);
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (!v.isTextual()) return null;
        String s = v.asText().trim();
        return s.isEmpty() || s.equalsIgnoreCase("null") ? null : s;
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String cut(String s, int n) {
        if (s == null) return null;
        return s.length() > n ? s.substring(0, n) : s;
    }
}
