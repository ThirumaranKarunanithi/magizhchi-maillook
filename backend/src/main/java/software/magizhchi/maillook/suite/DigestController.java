package software.magizhchi.maillook.suite;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.magizhchi.maillook.ai.AiService;
import software.magizhchi.maillook.common.ApiException;
import software.magizhchi.maillook.mail.MailActions;
import software.magizhchi.maillook.mail.MessageParser;
import software.magizhchi.maillook.mail.MessageRepo;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The weekly Scrum digest. A team that emails its status reports (the same subject every day or
 * week, dates aside) gets one digest: what each person did, does next and is stuck on, built from
 * those mails, their PDF attachments and the transcripts of the week's standups in Meet.
 */
@RestController
@RequestMapping("/api/messages/{id}/digest")
public class DigestController {

    private static final Logger log = LoggerFactory.getLogger(DigestController.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final Pattern UUID_RE = Pattern.compile("^[0-9a-fA-F-]{36}$");
    private static final Pattern STANDUP = Pattern.compile("(?i)\\b(stand-?ups?|scrum|daily sync|daily huddle)\\b");
    static final int MAX_PDFS = 12;
    static final int MAX_PDF_BYTES = 8 * 1024 * 1024;

    public record DigestRequest(String roomId, Integer days) {}

    private final MailActions actions;
    private final MessageRepo messages;
    private final AiService ai;
    private final MeetClient meet;

    public DigestController(MailActions actions, MessageRepo messages, AiService ai, MeetClient meet) {
        this.actions = actions;
        this.messages = messages;
        this.ai = ai;
        this.meet = meet;
    }

    /** Whether this mail is one of a series of reports, and which standup rooms could add to the digest. */
    @GetMapping
    public Map<String, Object> offer(@RequestAttribute long userId, @PathVariable long id) {
        MessageRepo.Row msg = actions.owned(userId, id);
        Map<String, Object> out = new LinkedHashMap<>();
        List<MessageRepo.Row> series = series(msg, 14);
        boolean available = series.size() >= 2 && ai.enabled(userId);
        out.put("available", available);
        if (!available) return out;
        out.put("title", title(msg.subject()));
        out.put("reports", series.size());
        out.put("senders", senders(series, msg.accountEmail()).size());
        List<Map<String, Object>> rooms = standupRooms(userId);
        out.put("rooms", rooms);
        out.put("roomId", guessRoom(rooms, msg.subject()));
        return out;
    }

    @PostMapping
    public Map<String, Object> build(@RequestAttribute long userId, @PathVariable long id, @RequestBody(required = false) DigestRequest req) {
        MessageRepo.Row msg = actions.owned(userId, id);
        int days = req == null || req.days() == null ? 7 : Math.max(1, Math.min(31, req.days()));
        List<MessageRepo.Row> series = series(msg, days);
        if (series.size() < 2) throw ApiException.bad("There aren't two reports like this one in the last " + days + " days to make a digest from.");
        Instant since = Instant.now().minus(Duration.ofDays(days));
        String title = title(msg.subject());

        // The reports, oldest first, with the text of any PDF they carry.
        StringBuilder material = new StringBuilder();
        int pdfs = 0;
        List<MessageRepo.Row> oldestFirst = new ArrayList<>(series);
        java.util.Collections.reverse(oldestFirst);
        for (MessageRepo.Row r : oldestFirst) {
            material.append("\n=== Report from ").append(person(r)).append(" | ")
                    .append(r.receivedAt() == null ? "" : DAY.format(r.receivedAt().atZoneSameInstant(ZONE)))
                    .append(" | Subject: ").append(nz(r.subject())).append('\n')
                    .append(cut(MessageParser.ownText(r.bodyText()), 3000)).append('\n');
            List<Map<String, Object>> files = r.attachments() == null ? List.of() : r.attachments();
            for (int i = 0; i < files.size() && pdfs < MAX_PDFS; i++) {
                String name = String.valueOf(files.get(i).getOrDefault("name", ""));
                String type = String.valueOf(files.get(i).getOrDefault("contentType", ""));
                if (!type.toLowerCase(Locale.ROOT).contains("pdf") && !name.toLowerCase(Locale.ROOT).endsWith(".pdf")) continue;
                String text = pdfText(userId, r.id(), i);
                if (text.isBlank()) continue;
                pdfs++;
                material.append("--- Attached ").append(name).append(":\n").append(cut(text, 6000)).append('\n');
            }
        }

        // The week's standups: who came, who didn't, and what was said.
        List<Map<String, Object>> attendance = new ArrayList<>();
        Set<String> to = new TreeSet<>(senders(series, msg.accountEmail()));
        int standups = 0;
        String roomName = null;
        if (req != null && req.roomId() != null && UUID_RE.matcher(req.roomId().trim()).matches() && meet.configured()) {
            String cardId = req.roomId().trim();
            roomName = meet.requireRun(userId, cardId, "read its standups into a digest").path("name").asText(null);
            MeetClient.Reply sittings = meet.as(userId, "GET", "/api/v1/cards/" + cardId + "/meetings?since=" + enc(since.toString()), null);
            if (!sittings.ok()) {
                throw ApiException.bad("Magizhchi Meet wouldn't share that room's standups (it answered " + sittings.code() + ").");
            }
            Map<String, int[]> seen = new LinkedHashMap<>();
            List<JsonNode> list = new ArrayList<>();
            if (sittings.json() != null) sittings.json().forEach(list::add);
            list.sort((a, b) -> a.path("startedAt").asText().compareTo(b.path("startedAt").asText()));
            if (list.size() > 10) list = list.subList(list.size() - 10, list.size());
            for (JsonNode s : list) {
                String from = s.path("startedAt").asText("");
                String end = s.path("endedAt").asText("");
                if (from.isBlank()) continue;
                standups++;
                material.append("\n=== Standup on ").append(DAY.format(Instant.parse(from).atZone(ZONE)))
                        .append(" (").append(s.path("minutes").asLong(0)).append(" min). Attended: ")
                        .append(names(s.path("attended"))).append(". Missed: ").append(names(s.path("missed"))).append('\n');
                s.path("attended").forEach(p -> {
                    seen.computeIfAbsent(p.path("name").asText("Someone"), k -> new int[2])[0]++;
                    addEmail(to, p, msg.accountEmail());
                });
                s.path("missed").forEach(p -> {
                    seen.computeIfAbsent(p.path("name").asText("Someone"), k -> new int[2])[1]++;
                    addEmail(to, p, msg.accountEmail());
                });
                if (!end.isBlank()) {
                    MeetClient.Reply lines = meet.as(userId, "GET", "/api/v1/cards/" + cardId + "/meetings/transcript?from="
                            + enc(from) + "&to=" + enc(end), null);
                    StringBuilder said = new StringBuilder();
                    if (lines.ok() && lines.json() != null) {
                        lines.json().forEach(l -> said.append(l.path("speaker").asText("?")).append(": ").append(l.path("text").asText()).append('\n'));
                    }
                    material.append(said.isEmpty() ? "(no captions were recorded)\n" : cut(said.toString(), 5000));
                }
            }
            int held = standups;
            seen.forEach((name, n) -> {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("name", name);
                a.put("attended", n[0]);
                a.put("missed", n[1]);
                a.put("of", held);
                attendance.add(a);
            });
        }

        JsonNode d = ai.scrumDigest(userId, roomName == null ? title : roomName, material.toString());
        String range = SHORT.format(since.atZone(ZONE)) + " – " + SHORT.format(Instant.now().atZone(ZONE));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subject", "Weekly digest: " + title + " (" + range + ")");
        out.put("body", body(d, attendance, standups, series.size(), pdfs));
        out.put("to", new ArrayList<>(to));
        out.put("digest", d);
        out.put("attendance", attendance);
        out.put("reports", series.size());
        out.put("pdfs", pdfs);
        out.put("standups", standups);
        return out;
    }

    // ── the series ────────────────────────────────────────────────

    /** Mail in the same mailbox, in the last {@code days}, whose subject is this one's with dates and numbers taken out. */
    List<MessageRepo.Row> series(MessageRepo.Row msg, int days) {
        String key = stem(msg.subject());
        if (key.length() < 4) return List.of();
        return messages.recent(msg.accountId(), OffsetDateTime.now(ZoneOffset.UTC).minusDays(days), 400).stream()
                .filter(r -> key.equals(stem(r.subject())))
                .toList();
    }

    private static final Pattern PREFIX = Pattern.compile("^((re|fwd?|fw|aw|wg)\\s*:\\s*)+");
    private static final Pattern MONTH = Pattern.compile("\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\b");
    private static final Pattern WEEKDAY = Pattern.compile("\\b(mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)(day)?\\b");
    private static final Pattern NUMBER = Pattern.compile("\\d+(st|nd|rd|th)?");

    /** "Re: Daily report – 5 Nov 2026 (Sprint 14)" → "daily report sprint". */
    static String stem(String subject) {
        String s = subject == null ? "" : subject.toLowerCase(Locale.ROOT).trim();
        s = PREFIX.matcher(s).replaceAll("");
        s = MONTH.matcher(s).replaceAll(" ");
        s = WEEKDAY.matcher(s).replaceAll(" ");
        s = NUMBER.matcher(s).replaceAll(" ");
        return s.replaceAll("[^\\p{L}]+", " ").trim().replaceAll("\\s+", " ");
    }

    /** The subject with "Re:" and the dates taken off, for the digest's own subject. */
    static String title(String subject) {
        String s = subject == null ? "" : subject.trim();
        s = s.replaceAll("(?i)^((re|fwd?|fw|aw|wg)\\s*:\\s*)+", "");
        s = s.replaceAll("(?i)\\b\\d{1,2}(st|nd|rd|th)?[\\s/.-]*(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*[\\s,/.-]*(\\d{2,4})?", "");
        s = s.replaceAll("(?i)\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*[\\s.-]*\\d{1,2}(st|nd|rd|th)?[,\\s]*(\\d{4})?", "");
        s = s.replaceAll("\\b\\d{1,4}[/.-]\\d{1,2}[/.-]\\d{1,4}\\b", "");
        s = s.replaceAll("(?i)\\b(mon|tue|wed|thu|fri|sat|sun)[a-z]*\\b", "");
        s = s.replaceAll("[\\s\\-–—:|,()]+$", "").replaceAll("\\(\\s*\\)", "").replaceAll("\\s{2,}", " ").trim();
        return s.isEmpty() ? "Team reports" : s;
    }

    // ── the digest mail ───────────────────────────────────────────

    static String body(JsonNode d, List<Map<String, Object>> attendance, int standups, int reports, int pdfs) {
        StringBuilder b = new StringBuilder("Hi team,\n\nHere's our week.\n\n");
        if (d.path("summary").isTextual()) b.append(d.path("summary").asText()).append("\n\n");
        for (JsonNode p : d.path("people")) {
            b.append(p.path("name").asText("Someone")).append('\n');
            line(b, "Done", p.path("done"));
            line(b, "Next", p.path("next"));
            line(b, "Blocked on", p.path("blockers"));
            b.append('\n');
        }
        if (d.path("risks").isArray() && !d.path("risks").isEmpty()) {
            b.append("Risks to the sprint\n");
            d.path("risks").forEach(r -> b.append("- ").append(r.asText()).append('\n'));
            b.append('\n');
        }
        if (standups > 0) {
            b.append("Standups: ").append(standups).append(" held");
            if (!attendance.isEmpty()) {
                b.append(". Attendance: ");
                List<String> parts = new ArrayList<>();
                for (Map<String, Object> a : attendance) parts.add(a.get("name") + " " + a.get("attended") + "/" + standups);
                b.append(String.join(", ", parts));
            }
            b.append(".\n");
        }
        b.append("Made from ").append(reports).append(reports == 1 ? " emailed report" : " emailed reports");
        if (pdfs > 0) b.append(" (").append(pdfs).append(pdfs == 1 ? " PDF" : " PDFs").append(")");
        if (standups > 0) b.append(" and ").append(standups).append(standups == 1 ? " standup transcript" : " standup transcripts");
        return b.append(".\n").toString();
    }

    private static void line(StringBuilder b, String label, JsonNode items) {
        if (!items.isArray() || items.isEmpty()) return;
        List<String> xs = new ArrayList<>();
        items.forEach(i -> xs.add(i.asText()));
        b.append("  ").append(label).append(": ").append(String.join("; ", xs)).append('\n');
    }

    // ── helpers ───────────────────────────────────────────────────

    private String pdfText(long userId, long messageId, int index) {
        try {
            MailActions.Download f = actions.attachment(userId, messageId, index);
            if (f.bytes().length == 0 || f.bytes().length > MAX_PDF_BYTES) return "";
            try (PDDocument doc = Loader.loadPDF(f.bytes())) {
                PDFTextStripper strip = new PDFTextStripper();
                strip.setEndPage(Math.min(doc.getNumberOfPages(), 20));
                return strip.getText(doc).trim();
            }
        } catch (Exception e) {
            log.info("Couldn't read PDF {} of mail {}: {}", index, messageId, e.toString());
            return "";
        }
    }

    private List<Map<String, Object>> standupRooms(long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!meet.configured()) return out;
        try {
            MeetClient.Reply r = meet.as(userId, "GET", "/api/v1/cards", null);
            if (!r.ok() || r.json() == null || !r.json().isArray()) return out;
            String me = meet.meetUserId(userId);
            for (JsonNode c : r.json()) {
                if (!MeetClient.canRun(c, me) || c.path("ephemeral").asBoolean(false)) continue;
                boolean scrum = "SCRUM".equals(c.path("type").asText("")) || STANDUP.matcher(c.path("name").asText("")).find();
                if (!scrum) continue;
                out.add(Map.of("id", c.path("id").asText(), "name", c.path("name").asText("")));
            }
        } catch (ApiException e) {
            log.info("Couldn't list standup rooms: {}", e.getMessage());
        }
        return out;
    }

    /** The standup room whose name shares a word with the reports' subject, else the only one. */
    static String guessRoom(List<Map<String, Object>> rooms, String subject) {
        if (rooms.isEmpty()) return null;
        Set<String> words = new TreeSet<>(List.of(stem(subject).split(" ")));
        words.removeAll(Set.of("daily", "weekly", "report", "reports", "status", "update", "updates", "standup", "scrum", "team", "the", "and"));
        for (Map<String, Object> r : rooms) {
            for (String w : stem(String.valueOf(r.get("name"))).split(" ")) {
                if (w.length() > 2 && words.contains(w)) return String.valueOf(r.get("id"));
            }
        }
        return rooms.size() == 1 ? String.valueOf(rooms.get(0).get("id")) : null;
    }

    private static Set<String> senders(List<MessageRepo.Row> series, String own) {
        Set<String> out = new TreeSet<>();
        for (MessageRepo.Row r : series) {
            if (r.fromEmail() != null && !r.fromEmail().equalsIgnoreCase(own)) out.add(r.fromEmail().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static void addEmail(Set<String> to, JsonNode p, String own) {
        String e = p.path("email").asText("");
        if (e.contains("@") && !e.equalsIgnoreCase(own)) to.add(e.toLowerCase(Locale.ROOT));
    }

    private static String names(JsonNode people) {
        List<String> n = new ArrayList<>();
        people.forEach(p -> n.add(p.path("name").asText("?")));
        return n.isEmpty() ? "nobody" : String.join(", ", n);
    }

    private static String person(MessageRepo.Row r) {
        return (r.fromName() == null || r.fromName().isBlank() ? "" : r.fromName() + " ") + "<" + nz(r.fromEmail()) + ">";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String cut(String s, int n) {
        return s == null ? "" : s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
