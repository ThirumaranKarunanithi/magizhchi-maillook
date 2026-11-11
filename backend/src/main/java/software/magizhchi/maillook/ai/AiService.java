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
     * Does the latest mail ask to talk or meet? With {@code offered} (the times You already proposed,
     * numbered from 1), the question is instead which of those the sender picked. Cached per mail.
     */
    public JsonNode meetingRequest(MessageRepo.Row msg, String offered) {
        String kind = offered == null ? "meeting_request" : "meeting_pick";
        var cached = messages.aiCache(msg.id(), kind);
        if (cached.isPresent()) return parse(cached.get());
        List<MessageRepo.Row> thread = messages.thread(msg.accountId(), msg.threadKey());
        String system = offered == null ? """
                Decide if the latest email asks the mailbox owner ("You") for a call or meeting that has no time
                agreed yet: "can we talk?", "call me", "let's meet", "can I get 15 minutes", "discuss on a call".
                Not a request: newsletters, automated invites that already have a time, or a one-way update.
                Reply as JSON: {"kind":"request"|"none","minutes": how long they want (15-120, default 30),
                "topic":"3-6 word meeting name"|null,"why":"one short sentence"}
                """ : """
                You (the mailbox owner) offered these meeting times earlier in the thread:
                %s
                Read the latest email. If the sender accepts one of them, give its number.
                Reply as JSON: {"kind":"picked"|"none","pick": number or null,"why":"one short sentence"}
                """.formatted(offered);
        JsonNode out = llm(msg.userId(), system, threadText(msg, thread), 0.0, 300);
        messages.putAiCache(msg.id(), kind, out.toString());
        return out;
    }

    /** What You need before a meeting about this thread: a short summary and the questions still open. Cached. */
    public JsonNode briefing(MessageRepo.Row msg) {
        var cached = messages.aiCache(msg.id(), "briefing");
        if (cached.isPresent()) return parse(cached.get());
        List<MessageRepo.Row> thread = messages.thread(msg.accountId(), msg.threadKey());
        JsonNode out = llm(msg.userId(), """
                You prepare the mailbox owner ("You") for a meeting about this email thread.
                Reply as JSON: {"summary": two plain sentences on what the thread is about and where it stands,
                "questions": ["each question or decision still open in the thread, short", ...]} (at most 6 questions,
                empty list if none). Use the language the emails are written in.
                """, threadText(msg, thread), 0.2, 900);
        messages.putAiCache(msg.id(), "briefing", out.toString());
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

    /**
     * The weekly Scrum digest: what each person did, does next and is stuck on, from their emailed
     * reports and the standup transcripts of the same week.
     */
    public JsonNode scrumDigest(long userId, String team, String material) {
        return llm(userId, """
                You write a team's weekly Scrum digest for its lead ("You") from the team's emailed status reports
                and the transcripts of the week's standup meetings. Use only what the material says; never invent work.
                Merge what one person said in several reports and standups into one entry.
                Reply as JSON: {"summary": two plain sentences on how the week went,
                "people": [{"name": the person's name as written, "done": ["short item", ...],
                "next": ["short item", ...], "blockers": ["short item", ...]}],
                "risks": ["anything that puts the sprint goal at risk, short", ...]}.
                At most 6 items per list. Empty lists when there are none. Write in the language the reports use.
                """, "Team: " + team + "\n\n" + cut(material, 40000), 0.2, 2500);
    }

    /**
     * Reads a thread as an event to host on Meet (a placement drive, webinar, orientation...):
     * its title, time, length and seats, and which of the given addresses are the people taking part.
     */
    public JsonNode eventDetails(MessageRepo.Row msg, String candidates) {
        var cached = messages.aiCache(msg.id(), "event");
        if (cached.isPresent()) return parse(cached.get());
        List<MessageRepo.Row> thread = messages.thread(msg.accountId(), msg.threadKey());
        JsonNode out = llm(msg.userId(), """
                You decide whether this email thread organises one event that the mailbox owner ("You") should host as an
                online session for many people: a placement or recruitment drive, a webinar, an orientation, an exam briefing,
                a workshop. An ordinary meeting between a few people is NOT an event.
                Reply as JSON: {"isEvent": true|false, "title": short title, "start": "YYYY-MM-DDTHH:MM" local time or null,
                "zone": IANA time zone if the mail says one or null, "minutes": length in minutes or null,
                "seats": number of places if the mail says one or null,
                "applicants": [the addresses, copied exactly from the candidate list, of the people taking part]}.
                """, threadText(msg, thread) + "\n\nCandidate addresses found in the thread:\n" + candidates, 0.1, 1500);
        messages.putAiCache(msg.id(), "event", out.toString());
        return out;
    }

    // ── writing ───────────────────────────────────────────────────

    /** How the person usually writes from this mailbox, for matching their voice. */
    private String voice(long accountId) {
        List<String> samples = messages.sentSamples(accountId, 6).stream()
                .map(MessageParser::ownText).filter(s -> s.length() > 20).map(s -> cut(s, 600)).toList();
        if (samples.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\nHow You usually write (match this tone, greeting and sign-off):\n");
        for (String s : samples) sb.append("---\n").append(s).append('\n');
        return sb.toString();
    }

    /**
     * A whole new mail (or a reply, with {@code replyTo}) from what the person asked for, in their voice.
     * Returns {"subject", "body"}.
     */
    public JsonNode write(long userId, long accountId, String accountEmail, String prompt, List<String> to,
                          String subject, MessageRepo.Row replyTo) {
        StringBuilder user = new StringBuilder("Mailbox owner (You): ").append(accountEmail).append('\n');
        user.append("What You want to say: ").append(cut(prompt, 2000)).append('\n');
        if (to != null && !to.isEmpty()) user.append("To: ").append(String.join(", ", to)).append('\n');
        if (subject != null && !subject.isBlank()) user.append("Subject so far: ").append(cut(subject, 300)).append('\n');
        if (replyTo != null) {
            user.append("\nThis is a reply to the thread below.\n")
                    .append(threadText(replyTo, messages.thread(replyTo.accountId(), replyTo.threadKey())));
        }
        user.append(voice(accountId));
        return llm(userId, """
                You write one email for the mailbox owner ("You") from their short instruction.
                Say what they asked for, clearly and warmly, in plain words; keep it as short as the instruction allows.
                Use the recipients' first names when they are known. Never invent facts, dates, prices or promises
                that the instruction or thread doesn't give; leave a short [placeholder] only when something truly needed is missing.
                Write in the language of the instruction unless it asks for another one.
                End with You's usual sign-off when the samples show one, else a simple one with no name.
                Reply as JSON: {"subject": "short subject line, or the existing one", "body": "plain text email body"}
                """, user.toString(), 0.6, 1800);
    }

    /** Ways to rewrite a draft; anything else is taken as the person's own instruction. */
    static final Map<String, String> STYLES = Map.of(
            "shorter", "Make it shorter and easier to read. Keep every fact, date, number and request.",
            "friendly", "Make it warmer and friendlier, still professional.",
            "formal", "Make it more formal and polite, as for a client, principal or official.",
            "confident", "Make it clear and confident: no hedging or over-apologising. Keep it polite.",
            "polish", "Only fix spelling, grammar and punctuation. Change nothing else.",
            "tamil", "Translate it into natural Tamil (Tamil script). Keep names, numbers and email addresses as they are.",
            "english", "Translate it into natural, simple English. Keep names and numbers as they are.");

    /** The same draft, rewritten in the chosen style. Returns {"body"}. */
    public JsonNode rewrite(long userId, String text, String style, String instruction) {
        String how = STYLES.get(style);
        if (how == null) how = cut(instruction == null ? "" : instruction.trim(), 500);
        if (how.isBlank()) throw ApiException.bad("Pick how to change it.");
        return llm(userId, """
                You rewrite an email draft for its writer. %s
                Keep the greeting, the sign-off and any signature lines, links, email addresses and phone numbers as they are,
                unless the instruction is to translate. Never add facts that aren't in the draft.
                Reply as JSON: {"body": "the rewritten draft, plain text"}
                """.formatted(how), "Draft:\n" + cut(text, 12000), "polish".equals(style) ? 0.1 : 0.5, 2500);
    }

    // ── one-tap replies and translation ───────────────────────────

    /**
     * Three very short ready answers to the latest mail ("Yes, works for me", "Thanks, got it"), in the
     * language it was written in, in the owner's voice. Cached per mail. Returns {"replies": [{"label", "body"}]}.
     */
    public JsonNode quickReplies(MessageRepo.Row msg) {
        var cached = messages.aiCache(msg.id(), "quick");
        if (cached.isPresent()) return parse(cached.get());
        List<MessageRepo.Row> thread = messages.thread(msg.accountId(), msg.threadKey());
        JsonNode out = llm(msg.userId(), """
                You suggest three one-tap replies the mailbox owner ("You") could send to the latest message in the thread.
                Each is one or two short sentences (under 25 words), complete and ready to send, with no greeting line
                and no sign-off. Make them genuinely different (for example: yes / a question / not now), and fit what
                the mail actually asks. Don't promise anything specific the thread doesn't support.
                If the mail needs no reply (a newsletter, receipt, automatic notice), reply {"replies": []}.
                Write in the language of the latest message.
                Reply as JSON: {"replies": [{"label": "2-4 word button text", "body": "the reply"}]}
                """, threadText(msg, thread), 0.5, 700);
        messages.putAiCache(msg.id(), "quick", out.toString());
        return out;
    }

    /** The mail in English or Tamil. Cached per mail and language. Returns {"subject", "body", "from"}. */
    public JsonNode translate(MessageRepo.Row msg, String to) {
        String kind = "tr_" + to;
        var cached = messages.aiCache(msg.id(), kind);
        if (cached.isPresent()) return parse(cached.get());
        String body = msg.bodyText() != null && !msg.bodyText().isBlank() ? msg.bodyText()
                : msg.bodyHtml() == null ? "" : MessageParser.htmlToText(msg.bodyHtml());
        String lang = "ta".equals(to) ? "natural Tamil (Tamil script)" : "natural, simple English";
        JsonNode out = llm(msg.userId(), """
                You translate an email into %s for the person who received it.
                Keep names, numbers, dates, prices, links, email addresses and phone numbers exactly as they are.
                Keep the paragraphs and lists. Translate quoted earlier mail too, if any. Add nothing and leave nothing out.
                Reply as JSON: {"subject": "the subject, translated", "body": "the email, translated, plain text", "from": "the language it was in, in English, e.g. Tamil"}
                """.formatted(lang), "Subject: " + nz(msg.subject()) + "\n\n" + cut(body, 14000), 0.2, 4000);
        messages.putAiCache(msg.id(), kind, out.toString());
        return out;
    }

    // ── asking your mail ──────────────────────────────────────────

    /**
     * Turns a question about the person's mail into search terms: words to look for (with the
     * spellings and synonyms mail would use), who it is from or to, a date window and the folder.
     */
    public JsonNode searchPlan(long userId, String question, String today, String zone) {
        return llm(userId, """
                You turn a person's question about their own email into a database search. Today is %s (%s).
                Reply as JSON: {"keywords": [up to 6 single words or short phrases that would appear in the matching emails:
                include synonyms, the other spelling, and the likely subject words; never stop-words; empty when the person
                only filters by sender or date],
                "from": a person's name or email address the mail is from, or null,
                "to": a name or address the person wrote to, or null,
                "after": "YYYY-MM-DD" or null, "before": "YYYY-MM-DD" or null,
                "hasAttachment": true only when they ask for a file, bill, invoice, PDF, photo or attachment,
                "folder": "SENT" when they ask about mail they sent, else "ALL",
                "wantsAnswer": true when the question asks for a fact (an amount, a date, a code, a decision), false for "show me / find"}
                """.formatted(today, zone), cut(question, 500), 0.0, 500);
    }

    /** PA chat inside MailLook: turns what the person said into app actions ({"say", "actions", "askPa"}). */
    public JsonNode appPlan(long userId, String system, String prompt) {
        try {
            return llm(userId, system, prompt, 0.2, 1500);
        } catch (ApiException e) {
            // one more go when the answer came back as prose instead of the JSON plan
            if (!software.magizhchi.maillook.suite.PaClient.UNREADABLE.equals(e.getMessage())) throw e;
            return llm(userId, system, prompt + "\n\nAnswer with the JSON object only: no words before or after it, no code fences.", 0.0, 1500);
        }
    }

    /** Answers a question from the mails found, citing them by id. Returns {"answer", "sources": [ids]}. */
    public JsonNode answer(long userId, String question, List<MessageRepo.Row> rows) {
        StringBuilder sb = new StringBuilder("Question: ").append(cut(question, 500)).append("\n\nEmails:\n");
        for (MessageRepo.Row r : rows) {
            sb.append("\n=== id ").append(r.id()).append(" | ").append(r.receivedAt() == null ? "" : r.receivedAt().toLocalDate())
                    .append(" | from: ").append(nz(r.fromName())).append(" <").append(nz(r.fromEmail())).append(">")
                    .append(" | to: ").append(String.join(", ", r.to()))
                    .append(" | subject: ").append(nz(r.subject())).append('\n')
                    .append(cut(MessageParser.ownText(r.bodyText()), 1500)).append('\n');
        }
        return llm(userId, """
                You answer the mailbox owner's question using only the emails given. Be short: one or two sentences,
                with the exact figure, date, code or name when there is one. If the emails don't answer it, say so plainly.
                Reply as JSON: {"answer": "...", "sources": [ids of the emails the answer comes from, most useful first, at most 3]}
                Write in the language of the question.
                """, sb.toString(), 0.1, 700);
    }

    // ── waiting on others ─────────────────────────────────────────

    /**
     * Which of these sent mails expect an answer from someone (a question, a request, a decision asked for),
     * as opposed to replies that close a conversation ("Thanks!", "Done", "Sent the file"). Cached per mail.
     * @return message id → the one-line reason, for the ones that expect a reply.
     */
    public Map<Long, String> expectsReply(long userId, List<MessageRepo.Row> sent) {
        Map<Long, String> out = new LinkedHashMap<>();
        List<MessageRepo.Row> ask = new ArrayList<>();
        for (MessageRepo.Row r : sent) {
            var c = messages.aiCache(r.id(), "expects_reply");
            if (c.isPresent()) {
                JsonNode j = parse(c.get());
                if (j.path("expects").asBoolean(false)) out.put(r.id(), j.path("why").asText(null));
            } else {
                ask.add(r);
            }
        }
        for (int i = 0; i < ask.size(); i += BATCH) {
            List<MessageRepo.Row> part = ask.subList(i, Math.min(ask.size(), i + BATCH));
            StringBuilder sb = new StringBuilder("Emails You sent:\n");
            for (MessageRepo.Row r : part) {
                sb.append("\n=== id ").append(r.id()).append(" | to: ").append(String.join(", ", r.to()))
                        .append(" | subject: ").append(nz(r.subject())).append('\n')
                        .append(cut(MessageParser.ownText(r.bodyText()), 800)).append('\n');
            }
            JsonNode res = llm(userId, """
                    For each email the mailbox owner ("You") sent, decide whether You are now waiting for the other person
                    to answer: You asked a question, asked them to do or send something, proposed something that needs a yes
                    or no, or asked for a decision or a date. Not waiting: thanks, confirmations, "done", sharing a file
                    with nothing asked, newsletters, automatic replies.
                    Reply as JSON: {"items":[{"id":<number>,"expects":true|false,"why":"what You are waiting for, 3-8 words"}]}.
                    Include every id.
                    """, sb.toString(), 0.0, 1800);
            for (JsonNode it : res.path("items")) {
                long id = it.path("id").asLong(-1);
                if (part.stream().noneMatch(r -> r.id() == id)) continue;
                boolean expects = it.path("expects").asBoolean(false);
                String why = it.path("why").isTextual() ? cut(it.path("why").asText(), 120) : null;
                Map<String, Object> save = new LinkedHashMap<>();
                save.put("expects", expects);
                save.put("why", why);
                try {
                    messages.putAiCache(id, "expects_reply", new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(save));
                } catch (Exception ignored) {
                }
                if (expects) out.put(id, why);
            }
        }
        return out;
    }

    /** A short, kind follow-up to a mail that got no answer. Returns {"body"}. */
    public JsonNode nudge(MessageRepo.Row sent, long days) {
        List<MessageRepo.Row> thread = messages.thread(sent.accountId(), sent.threadKey());
        return llm(sent.userId(), """
                The mailbox owner ("You") sent the last email in this thread %d days ago and nobody has answered.
                Write a short, friendly follow-up (two to four sentences) that reminds them of what You asked and makes it
                easy to answer. No guilt, no "just checking in" cliché as the whole message. No subject line.
                Reply as JSON: {"body": "plain text"}. Write in the language of the thread.
                """.formatted(days), threadText(sent, thread) + voice(sent.accountId()), 0.6, 700);
    }

    // ── daily briefing ────────────────────────────────────────────

    /**
     * The morning read: a short overview of the day and one line per mail that needs the person.
     * {@code material} lists the mails (with ids), the calendar and the tasks. Returns
     * {"summary", "items": [{"id", "line"}]}.
     */
    public JsonNode dailyBriefing(long userId, String name, String material) {
        return llm(userId, """
                You write %s's morning briefing for their email and day. Be warm, calm and brief.
                "summary": two or three plain sentences: what matters most today (the meetings, the people waiting the longest,
                anything due), then one encouraging note. Never invent anything not in the material.
                "items": for each email under "Needs you", one line (max 14 words) saying what the person must do, e.g.
                "Confirm the hall booking for Saturday's batch with Priya".
                Reply as JSON: {"summary": "...", "items": [{"id": <number>, "line": "..."}]}
                Write in English unless most of the mail is in another language.
                """.formatted(name == null || name.isBlank() ? "the person" : name), cut(material, 30000), 0.4, 1500);
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
