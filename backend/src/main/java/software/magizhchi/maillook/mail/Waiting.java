package software.magizhchi.maillook.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.magizhchi.maillook.ai.AiService;
import software.magizhchi.maillook.common.ApiException;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Waiting on others: mail you sent at least two days ago (up to three weeks) that asked for something,
 * where nobody has answered and you haven't written again. AI decides whether a mail really asked
 * for an answer; without AI a question mark or a request ("please", "could you", "let me know") does.
 */
@Service
public class Waiting {

    private static final Logger log = LoggerFactory.getLogger(Waiting.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final Duration MIN_AGE = Duration.ofHours(48);
    static final Duration MAX_AGE = Duration.ofDays(21);
    private static final Pattern ASKS = Pattern.compile(
            "\\?|(?i)\\b(please|pls|kindly|could you|can you|would you|let me know|let us know|awaiting|waiting for|" +
                    "confirm|send me|share the|share your|revert|get back to me|your thoughts|any update)\\b");
    private static final Pattern ROBOT = Pattern.compile("(?i)(no-?reply|do-?not-?reply|mailer-daemon|notifications?@|bounce)");

    public record Item(MessageRepo.Row row, long days, String why) {
        public Map<String, Object> summary() {
            Map<String, Object> m = row.summary();
            m.put("waitingDays", days);
            m.put("waitingFor", why);
            return m;
        }
    }

    private final MessageRepo messages;
    private final AiService ai;

    public Waiting(MessageRepo messages, AiService ai) {
        this.messages = messages;
        this.ai = ai;
    }

    /**
     * @param askAi false to only use AI answers already worked out (and the simple rule for the rest),
     *              so counting is quick.
     */
    public List<Item> list(long userId, Long accountId, Instant now, boolean askAi) {
        List<MessageRepo.Row> sent = messages.lastWordSent(userId, accountId,
                OffsetDateTime.ofInstant(now.minus(MAX_AGE), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(now.minus(MIN_AGE), ZoneOffset.UTC), 120).stream()
                .filter(r -> r.to().stream().noneMatch(t -> ROBOT.matcher(t).find()))
                .toList();
        if (sent.isEmpty()) return List.of();
        if (askAi && ai.enabled(userId)) {
            try {
                ai.expectsReply(userId, sent); // only to save AI's verdict on each mail; read back below
            } catch (ApiException e) {
                log.info("Waiting-on-others AI check skipped: {}", e.getMessage());
            }
        }
        // One rule for the list and for the sidebar count, so they always agree: AI's saved verdict when there
        // is one, otherwise the simple "it asked something" check.
        List<Item> out = new ArrayList<>();
        for (MessageRepo.Row r : sent) {
            String why = null;
            var cached = messages.aiCache(r.id(), "expects_reply");
            if (cached.isPresent()) {
                JsonNode j;
                try {
                    j = JSON.readTree(cached.get());
                } catch (Exception e) {
                    j = JSON.createObjectNode();
                }
                if (!j.path("expects").asBoolean(false)) continue;
                why = j.path("why").isTextual() ? j.path("why").asText() : null;
            } else if (!ASKS.matcher(MessageParser.ownText(r.bodyText())).find()) {
                continue;
            }
            long days = Math.max(2, Duration.between(r.receivedAt().toInstant(), now).toDays());
            out.add(new Item(r, days, why));
        }
        return out;
    }

    /** "I'm not waiting on this one": it leaves the list for good. */
    public void dismiss(MessageRepo.Row sent) {
        if (!"SENT".equals(sent.folder())) throw ApiException.bad("Only mail you sent can be waiting on others.");
        messages.putAiCache(sent.id(), "waiting_off", "1");
    }

    /** First name or address of who the mail went to, for "Waiting on Priya". */
    public static String who(MessageRepo.Row sent) {
        if (sent.to() == null || sent.to().isEmpty()) return "them";
        String s = sent.to().get(0).trim();
        int lt = s.indexOf('<');
        String name = lt > 0 ? s.substring(0, lt).replace("\"", "").trim() : s;
        if (name.contains("@")) return name.toLowerCase(Locale.ROOT);
        String first = name.split("\\s+")[0];
        return sent.to().size() > 1 ? first + " and " + (sent.to().size() - 1) + " more" : first;
    }
}
