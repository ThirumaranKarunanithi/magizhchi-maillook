package software.magizhchi.maillook.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import software.magizhchi.maillook.common.ApiException;
import software.magizhchi.maillook.suite.PaClient;
import software.magizhchi.maillook.mail.LaneClassifier;
import software.magizhchi.maillook.mail.MessageParser;
import software.magizhchi.maillook.mail.MessageRepo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lane refinement, thread summaries and reply drafts in the user's own voice.
 * The AI comes from Magizhchi PA (as the signed-in person, over suite SSO); a server-wide
 * GEMINI_API_KEY is only a fallback for when PA isn't available.
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    private static final int BATCH = 25;

    private final GeminiClient gemini;
    private final PaClient pa;
    private final MessageRepo messages;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    public AiService(GeminiClient gemini, PaClient pa, MessageRepo messages) {
        this.gemini = gemini;
        this.pa = pa;
        this.messages = messages;
    }

    /** "pa", "gemini", or null when there is no AI for this user. */
    public String provider(long userId) {
        if (pa.configured() && pa.aiEnabled(userId)) return "pa";
        if (gemini.isConfigured()) return "gemini";
        return null;
    }

    public boolean enabled(long userId) {
        return provider(userId) != null;
    }

    private JsonNode llm(long userId, String system, String user, double temperature, int maxTokens) {
        String p = provider(userId);
        if ("pa".equals(p)) return pa.completeJson(userId, system, user, maxTokens);
        if ("gemini".equals(p)) return gemini.json(system, user, temperature);
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "AI comes from Magizhchi PA. Sign in with Magizhchi ID and make sure PA's AI is switched on.");
    }

    // ── lanes ─────────────────────────────────────────────────────

    /** Re-checks the heuristic lane of freshly synced mail in the background (no-op without AI). */
    public void classifyLater(long userId, List<Long> ids) {
        if (ids.isEmpty()) return;
        List<Long> recent = ids.size() > 100 ? ids.subList(ids.size() - 100, ids.size()) : ids;
        List<Long> copy = new ArrayList<>(recent);
        worker.submit(() -> {
            if (!enabled(userId)) return;
            for (int i = 0; i < copy.size(); i += BATCH) {
                try {
                    classify(userId, copy.subList(i, Math.min(copy.size(), i + BATCH)));
                } catch (Exception e) {
                    log.info("AI lane pass skipped: {}", e.getMessage());
                    return;
                }
            }
        });
    }

    private void classify(long userId, List<Long> ids) {
        List<MessageRepo.Row> rows = messages.byIds(new LinkedHashSet<>(ids)).stream()
                .filter(r -> "heuristic".equals(r.laneSource())).toList();
        if (rows.isEmpty()) return;
        StringBuilder sb = new StringBuilder("Mailbox owner: ").append(rows.get(0).accountEmail()).append("\n\nEmails:\n");
        for (MessageRepo.Row r : rows) {
            sb.append("- id ").append(r.id())
                    .append(" | from: ").append(nz(r.fromName())).append(" <").append(nz(r.fromEmail())).append(">")
                    .append(" | to: ").append(String.join(", ", r.to()))
                    .append(r.cc().isEmpty() ? "" : " | cc: " + String.join(", ", r.cc()))
                    .append(" | subject: ").append(nz(r.subject()))
                    .append(" | preview: ").append(cut(nz(r.snippet()), 200)).append('\n');
        }
        JsonNode out = llm(userId, """
                You sort email into three lanes for a busy person.
                needs_me: a real person (or a client, student, colleague) expects a reply, decision or action from the owner.
                fyi: worth knowing but no reply needed: receipts, bank/UPI alerts, OTPs, order/shipping updates, calendar notices, CCs, team broadcasts.
                can_wait: newsletters, promotions, offers, marketing, social notifications, digests,
                and cold outreach: a stranger pitching a service, product, partnership, SEO, design, leads or
                "I came across your company" mail, even when it is personally addressed and written like a letter.
                Reply as JSON: {"items":[{"id":<number>,"lane":"needs_me|fyi|can_wait"}]}. Include every id.
                """, sb.toString(), 0.0, 2000);
        for (JsonNode it : out.path("items")) {
            long id = it.path("id").asLong(-1);
            String lane = it.path("lane").asText("");
            if (id > 0 && LaneClassifier.isLane(lane) && ids.contains(id)) {
                messages.setLane(id, lane, "ai");
            }
        }
    }

    // ── summary ───────────────────────────────────────────────────

    public JsonNode summary(MessageRepo.Row msg) {
        var cached = messages.aiCache(msg.id(), "summary");
        if (cached.isPresent()) return parse(cached.get());
        List<MessageRepo.Row> thread = messages.thread(msg.accountId(), msg.threadKey());
        JsonNode out = llm(msg.userId(), """
                You summarise an email thread for the mailbox owner (called "You").
                Reply as JSON: {"summary": one plain sentence, "decision": what was decided or null,
                "actions": [{"who": person or "You", "what": short task}], "nextStep": what You should do next or null}.
                Keep it short and concrete. Use the language the emails are written in.
                """, threadText(msg, thread), 0.2, 1200);
        messages.putAiCache(msg.id(), "summary", out.toString());
        return out;
    }

    // ── drafts ────────────────────────────────────────────────────

    public JsonNode drafts(MessageRepo.Row msg) {
        List<MessageRepo.Row> thread = messages.thread(msg.accountId(), msg.threadKey());
        List<String> samples = messages.sentSamples(msg.accountId(), 6).stream()
                .map(MessageParser::ownText).filter(s -> s.length() > 20).map(s -> cut(s, 600)).toList();
        StringBuilder user = new StringBuilder(threadText(msg, thread));
        if (!samples.isEmpty()) {
            user.append("\n\nHow You usually write (match this tone, greeting and sign-off):\n");
            for (String s : samples) user.append("---\n").append(s).append('\n');
        }
        return llm(msg.userId(), """
                You draft short email replies for the mailbox owner ("You") to the latest message in the thread.
                Write three drafts: "accept" (agree / confirm / say yes), "decline" (politely say no or not now),
                "ask" (ask the one question that is missing). Plain text, no subject line, no placeholders like [Name]
                unless the name is truly unknown. Reply in the language of the latest message.
                Reply as JSON: {"drafts":[{"intent":"accept","label":"2-4 word button text","body":"..."},
                {"intent":"decline",...},{"intent":"ask",...}]}
                """, user.toString(), 0.6, 2000);
    }

    // ── classroom actions (Magizhchi Meet) ────────────────────────

    /**
     * Whether this mail asks to change a class room's weekly time, or asks to attend a class online,
     * and which of the owner's rooms it means. {@code rooms} lines look like
     * "- id <uuid> | <name> | WEEKLY SAT 15:00-17:00 Asia/Kolkata". Cached per mail; the caller
     * checks the answer against the rooms it actually has.
     */
    public JsonNode classroom(MessageRepo.Row msg, String rooms) {
        var cached = messages.aiCache(msg.id(), "classroom");
        if (cached.isPresent()) return parse(cached.get());
        List<MessageRepo.Row> thread = messages.thread(msg.accountId(), msg.threadKey());
        JsonNode out = llm(msg.userId(), """
                The mailbox owner ("You") runs these online class rooms in Magizhchi Meet:
                %s

                Read the latest email and decide if it asks for one of these:
                "reschedule": change the regular time a class/batch meets (e.g. "can we move Saturday batch to 4 PM").
                "attend_online": the sender (a student/attendee) wants to join a class online today, e.g. because of
                illness, travel or leave.
                "none": anything else, including one-off meetings, questions, or a time change already refused.
                Pick the room the email means from the list (match batch names, days, subjects); null when unsure.
                For "reschedule", newTime is the new start time as 24-hour "HH:mm" in the room's own time zone.
                Reply as JSON: {"kind":"reschedule|attend_online|none","roomId":"<id from the list>"|null,
                "newTime":"HH:mm"|null,"why":"one short sentence"}
                """.formatted(rooms), threadText(msg, thread), 0.0, 400);
        messages.putAiCache(msg.id(), "classroom", out.toString());
        return out;
    }

    /**
     * The recap of one Magizhchi Meet meeting from its captions: a short summary, what was decided,
     * and who does what by when. {@code people} lists who attended, so owners use real names.
     */
    public JsonNode meetingReport(long userId, String room, String people, String transcript) {
        return llm(userId, """
                You write the recap of an online meeting for the person who ran it ("You").
                Use only what the transcript says; never invent decisions or tasks.
                Reply as JSON: {"summary": two or three plain sentences,
                "decisions": ["short decision", ...],
                "actions": [{"owner": a name from the attendee list or "You", "task": "short task",
                "due": "YYYY-MM-DD" or null}]}.
                Empty lists when there are none. Write in the language the meeting was held in.
                """, "Meeting: " + room + "\nAttended: " + people + "\n\nTranscript:\n" + cut(transcript, 20000), 0.2, 1500);
    }

    private static String threadText(MessageRepo.Row msg, List<MessageRepo.Row> thread) {
        List<MessageRepo.Row> t = thread.isEmpty() ? List.of(msg) : thread;
        if (t.size() > 8) t = t.subList(t.size() - 8, t.size());
        StringBuilder sb = new StringBuilder("Mailbox owner (You): ").append(msg.accountEmail()).append("\n\nThread, oldest first:\n");
        for (MessageRepo.Row r : t) {
            boolean mine = msg.accountEmail().equalsIgnoreCase(nz(r.fromEmail()));
            sb.append("\n=== From: ").append(mine ? "You" : nz(r.fromName()) + " <" + nz(r.fromEmail()) + ">")
                    .append(" | ").append(r.receivedAt()).append(" | Subject: ").append(nz(r.subject())).append('\n')
                    .append(cut(MessageParser.ownText(r.bodyText()), 3000)).append('\n');
        }
        return sb.toString();
    }

    private static JsonNode parse(String s) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(s);
        } catch (Exception e) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Bad cached AI result");
        }
    }

    public Map<String, Object> status(long userId) {
        String p = provider(userId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", p != null);
        m.put("provider", p);
        m.put("message", "pa".equals(p) ? "AI by Magizhchi PA"
                : "gemini".equals(p) ? "AI by Gemini (server key)"
                : "AI comes from Magizhchi PA. Its AI is off or PA can't be reached.");
        return m;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
