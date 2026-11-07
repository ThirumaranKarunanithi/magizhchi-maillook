package software.magizhchi.maillook.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.magizhchi.maillook.account.AccountRepo;
import software.magizhchi.maillook.common.ApiException;
import software.magizhchi.maillook.mail.MailActions;
import software.magizhchi.maillook.mail.MessageParser;
import software.magizhchi.maillook.mail.MessageRepo;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writing with AI (a whole mail from a sentence, or the draft rewritten: shorter, friendlier, more formal,
 * polished, translated) and asking your mail a question in plain words.
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    public record WriteRequest(Long accountId, String prompt, List<String> to, String subject, Long replyToId) {}
    public record RewriteRequest(String text, String style, String instruction) {}

    private final AiService ai;
    private final AccountRepo accounts;
    private final MailActions actions;
    private final MessageRepo messages;

    public AiController(AiService ai, AccountRepo accounts, MailActions actions, MessageRepo messages) {
        this.ai = ai;
        this.accounts = accounts;
        this.actions = actions;
        this.messages = messages;
    }

    private void needAi(long userId) {
        if (!ai.enabled(userId)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI comes from Magizhchi PA. Sign in with Magizhchi ID and make sure PA's AI is switched on.");
        }
    }

    @PostMapping("/write")
    public Map<String, Object> write(@RequestAttribute long userId, @RequestBody WriteRequest req) {
        if (req == null || req.prompt() == null || req.prompt().trim().length() < 3)
            throw ApiException.bad("Say in a few words what the mail should say.");
        if (req.prompt().length() > 2000) throw ApiException.bad("Keep the request under 2,000 characters.");
        needAi(userId);
        var account = req.accountId() == null ? null
                : accounts.find(req.accountId()).filter(a -> a.userId() == userId).orElseThrow(() -> ApiException.notFound("Account"));
        if (account == null) throw ApiException.bad("Pick the mailbox to write from.");
        MessageRepo.Row replyTo = req.replyToId() == null ? null : actions.owned(userId, req.replyToId());
        JsonNode out = ai.write(userId, account.id(), account.email(), req.prompt().trim(), req.to(), req.subject(), replyTo);
        String body = out.path("body").asText("").trim();
        if (body.isEmpty()) throw new ApiException(HttpStatus.BAD_GATEWAY, "The AI didn't write anything. Try saying it another way.");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subject", out.path("subject").isTextual() ? clip(out.path("subject").asText().trim(), 300) : null);
        m.put("body", body);
        return m;
    }

    @PostMapping("/rewrite")
    public Map<String, Object> rewrite(@RequestAttribute long userId, @RequestBody RewriteRequest req) {
        if (req == null || req.text() == null || req.text().isBlank()) throw ApiException.bad("Write something first, then change how it sounds.");
        if (req.text().length() > 12000) throw ApiException.bad("That draft is too long to rewrite in one go.");
        needAi(userId);
        String style = req.style() == null ? "" : req.style().trim().toLowerCase(Locale.ROOT);
        String body = ai.rewrite(userId, req.text(), style, req.instruction()).path("body").asText("").trim();
        if (body.isEmpty()) throw new ApiException(HttpStatus.BAD_GATEWAY, "The AI didn't send a new version. Try again.");
        return Map.of("body", body);
    }

    /**
     * "When is Priya's hall booking?", "invoices from Hostinger last month", "what did I promise Ravi?"
     * The question becomes search terms, the mails found are ranked, and for a question of fact
     * the answer is given with the mails it came from.
     */
    @GetMapping("/ask")
    public Map<String, Object> ask(@RequestAttribute long userId, @RequestParam String q,
                                   @RequestParam(required = false) Long accountId,
                                   @RequestParam(required = false) String tz) {
        String question = q == null ? "" : q.trim();
        if (question.length() < 3) throw ApiException.bad("Ask a question about your mail.");
        if (question.length() > 500) throw ApiException.bad("Keep the question under 500 characters.");
        needAi(userId);
        ZoneId zone = zone(tz);
        LocalDate today = LocalDate.now(zone);
        JsonNode plan = ai.searchPlan(userId, question, today.toString() + " " + today.getDayOfWeek(), zone.getId());

        List<String> words = new ArrayList<>();
        for (JsonNode w : plan.path("keywords")) if (w.isTextual() && !w.asText().isBlank()) words.add(w.asText().trim());
        String from = text(plan, "from");
        String to = text(plan, "to");
        Instant after = day(text(plan, "after"), zone, 0);
        Instant before = day(text(plan, "before"), zone, 1);
        boolean attach = plan.path("hasAttachment").asBoolean(false);
        String folder = "SENT".equalsIgnoreCase(text(plan, "folder")) ? "SENT" : "ALL";

        List<MessageRepo.Row> rows = messages.askSearch(userId, accountId, words, from, to, after, before, attach, folder, 150);
        boolean loosened = false;
        if (rows.isEmpty() && (from != null || to != null || after != null || before != null || attach)) {
            // Nothing that narrow: try the words alone.
            rows = messages.askSearch(userId, accountId, words, null, null, null, null, false, folder, 150);
            loosened = !rows.isEmpty();
        }
        List<MessageRepo.Row> ranked = rank(rows, words, from);

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("keywords", words);
        p.put("from", from);
        p.put("to", to);
        p.put("after", after == null ? null : LocalDate.ofInstant(after, zone).toString());
        p.put("before", before == null ? null : LocalDate.ofInstant(before, zone).minusDays(1).toString());
        p.put("hasAttachment", attach);
        p.put("folder", folder);
        p.put("loosened", loosened);
        out.put("plan", p);
        out.put("items", ranked.stream().limit(25).map(MessageRepo.Row::summary).toList());
        boolean wants = plan.path("wantsAnswer").asBoolean(question.endsWith("?"));
        if (wants && !ranked.isEmpty()) {
            JsonNode a = ai.answer(userId, question, ranked.subList(0, Math.min(8, ranked.size())));
            List<Long> sources = new ArrayList<>();
            for (JsonNode s : a.path("sources")) {
                long id = s.asLong(-1);
                if (ranked.stream().anyMatch(r -> r.id() == id) && !sources.contains(id)) sources.add(id);
            }
            out.put("answer", a.path("answer").asText(null));
            out.put("sources", sources);
        } else {
            out.put("answer", wants ? "Nothing in your mail matches that." : null);
            out.put("sources", List.of());
        }
        return out;
    }

    /** Subject hits count most, then the sender, then the body; newer breaks ties. */
    static List<MessageRepo.Row> rank(List<MessageRepo.Row> rows, List<String> words, String from) {
        List<String> ws = words.stream().map(w -> w.toLowerCase(Locale.ROOT)).toList();
        Map<Long, Integer> score = new LinkedHashMap<>();
        for (MessageRepo.Row r : rows) {
            String subject = lower(r.subject());
            String sender = lower(r.fromName()) + " " + lower(r.fromEmail());
            String body = lower(MessageParser.ownText(r.bodyText()));
            int s = 0;
            for (String w : ws) {
                if (subject.contains(w)) s += 4;
                if (sender.contains(w)) s += 2;
                if (body.contains(w)) s += 1;
            }
            if (from != null && sender.contains(from.toLowerCase(Locale.ROOT))) s += 2;
            score.put(r.id(), s);
        }
        List<MessageRepo.Row> out = new ArrayList<>(rows);
        out.sort(Comparator.<MessageRepo.Row>comparingInt(r -> -score.getOrDefault(r.id(), 0))
                .thenComparing(r -> r.receivedAt() == null ? Instant.EPOCH : r.receivedAt().toInstant(), Comparator.reverseOrder()));
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (!v.isTextual()) return null;
        String s = v.asText().trim();
        return s.isEmpty() || s.equalsIgnoreCase("null") ? null : s;
    }

    private static Instant day(String v, ZoneId zone, int plus) {
        if (v == null) return null;
        try {
            return LocalDate.parse(v).plusDays(plus).atStartOfDay(zone).toInstant();
        } catch (DateTimeException e) {
            return null;
        }
    }

    static ZoneId zone(String tz) {
        try {
            return tz == null || tz.isBlank() ? ZoneId.of("Asia/Kolkata") : ZoneId.of(tz);
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
