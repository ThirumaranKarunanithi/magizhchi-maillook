package software.magizhchi.maillook.mail;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.magizhchi.maillook.common.ApiException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Files: every attachment from every mailbox in one place, newest first, by kind or by name. */
@RestController
@RequestMapping("/api/files")
public class FilesController {

    /** Kinds the Files view filters by; matched on the file name, then on its type. */
    static final Map<String, Pattern[]> KINDS = new LinkedHashMap<>();
    static {
        KINDS.put("pdf", new Pattern[]{Pattern.compile("\\.pdf$"), Pattern.compile("pdf")});
        KINDS.put("image", new Pattern[]{Pattern.compile("\\.(png|jpe?g|gif|webp|bmp|svg|heic)$"), Pattern.compile("^image/")});
        KINDS.put("sheet", new Pattern[]{Pattern.compile("\\.(xlsx?|csv|ods)$"), Pattern.compile("sheet|excel|csv")});
        KINDS.put("doc", new Pattern[]{Pattern.compile("\\.(docx?|odt|rtf|txt|md)$"), Pattern.compile("word|document|text/plain")});
        KINDS.put("slides", new Pattern[]{Pattern.compile("\\.(pptx?|odp|key)$"), Pattern.compile("presentation|powerpoint")});
        KINDS.put("archive", new Pattern[]{Pattern.compile("\\.(zip|rar|7z|tar|gz)$"), Pattern.compile("zip|compressed")});
        KINDS.put("media", new Pattern[]{Pattern.compile("\\.(mp4|mov|webm|mp3|wav|m4a)$"), Pattern.compile("^(video|audio)/")});
    }
    /** Not files people look for: calendar invites, signatures and the mail itself. */
    private static final Pattern NOISE = Pattern.compile("(\\.(ics|vcs|p7s|asc)$)|^(invite\\.ics|smime\\.p7s|signature\\.asc)$");

    private final MessageRepo messages;
    private final software.magizhchi.maillook.suite.BoxClient box;

    public FilesController(MessageRepo messages, software.magizhchi.maillook.suite.BoxClient box) {
        this.messages = messages;
        this.box = box;
    }

    static String kind(String name, String type) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        for (var e : KINDS.entrySet()) if (e.getValue()[0].matcher(n).find()) return e.getKey();
        for (var e : KINDS.entrySet()) if (e.getValue()[1].matcher(t).find()) return e.getKey();
        return "other";
    }

    @GetMapping
    public Map<String, Object> list(@RequestAttribute long userId,
                                    @RequestParam(required = false) Long accountId,
                                    @RequestParam(required = false) String kind,
                                    @RequestParam(required = false) String q,
                                    @RequestParam(required = false) String cursor) {
        if (kind != null && !kind.isBlank() && !KINDS.containsKey(kind) && !"other".equals(kind)) throw ApiException.bad("Unknown kind of file.");
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
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> items = new ArrayList<>();
        String next = null;
        // Read mail in pages until there are enough files to show (filters can skip many mails).
        for (int page = 0; page < 5 && items.size() < 60; page++) {
            List<MessageRepo.Row> rows = messages.withFiles(userId, accountId, needle, cAt, cId, 80);
            for (MessageRepo.Row r : rows) {
                boolean nameMatchesAll = needle.isEmpty()
                        || (r.subject() != null && r.subject().toLowerCase(Locale.ROOT).contains(needle))
                        || (r.fromName() != null && r.fromName().toLowerCase(Locale.ROOT).contains(needle))
                        || r.fromEmail().toLowerCase(Locale.ROOT).contains(needle);
                for (Map<String, Object> a : r.attachments()) {
                    String name = String.valueOf(a.getOrDefault("name", "attachment"));
                    String type = String.valueOf(a.getOrDefault("contentType", ""));
                    if (NOISE.matcher(name.toLowerCase(Locale.ROOT)).find() || type.toLowerCase(Locale.ROOT).startsWith("text/calendar")) continue;
                    String k = kind(name, type);
                    if (kind != null && !kind.isBlank() && !kind.equals(k)) continue;
                    if (!nameMatchesAll && !name.toLowerCase(Locale.ROOT).contains(needle)) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("messageId", r.id());
                    m.put("index", a.get("index"));
                    m.put("name", name);
                    m.put("size", a.get("size"));
                    m.put("contentType", type);
                    m.put("kind", k);
                    m.put("accountId", r.accountId());
                    m.put("accountColor", r.accountColor());
                    m.put("folder", r.folder());
                    m.put("fromName", r.fromName());
                    m.put("fromEmail", r.fromEmail());
                    m.put("subject", r.subject());
                    m.put("receivedAt", r.receivedAt().toInstant().toString());
                    items.add(m);
                }
            }
            if (rows.size() < 80) {
                next = null;
                break;
            }
            MessageRepo.Row last = rows.get(rows.size() - 1);
            cAt = last.receivedAt();
            cId = last.id();
            next = last.receivedAt().toInstant().toEpochMilli() + ":" + last.id();
        }
        // Which are in Magizhchi Box already.
        List<Long> ids = items.stream().map(m -> (Long) m.get("messageId")).distinct().toList();
        java.util.Set<String> saved = new java.util.HashSet<>(box.savedKeys(ids));
        for (Map<String, Object> m : items) {
            m.put("inBox", m.get("index") instanceof Number n && saved.contains(m.get("messageId") + ":" + n.intValue()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("nextCursor", next);
        return out;
    }
}
