package software.magizhchi.maillook.mail;

import com.fasterxml.jackson.databind.JsonNode;
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
    /** Not a file anyone keeps: calendar invites, signatures. */
    public static boolean noise(String name, String type) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return NOISE.matcher(n).find() || t.startsWith("text/calendar") || t.startsWith("application/pkcs7-signature") || t.startsWith("application/pgp-signature");
    }

    private static final Pattern NOISE = Pattern.compile("(\\.(ics|vcs|p7s|asc)$)|^(invite\\.ics|smime\\.p7s|signature\\.asc)$");

    private final MessageRepo messages;
    private final software.magizhchi.maillook.suite.BoxClient box;
    private final software.magizhchi.maillook.suite.BoxAutoSave auto;

    /** Where MailLook files attachments in Box (the same place the cloud button saves to). */
    static final String BOX_ROOT = "Magizhchi MailLook/Attachments/";

    public FilesController(MessageRepo messages, software.magizhchi.maillook.suite.BoxClient box,
                           software.magizhchi.maillook.suite.BoxAutoSave auto) {
        this.messages = messages;
        this.box = box;
        this.auto = auto;
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
        // Attachments live in Box: the list comes from Box. Until Box is connected (or if it can't be
        // reached) the mail's own list stands in, and says why.
        String note = null;
        if (box.configured() && "box".equals(auto.store(userId))) {
            if (box.existing(userId).isPresent()) {
                Map<String, Object> fromBox = fromBox(userId, accountId, kind, q, cursor);
                if (fromBox != null) return fromBox;
                note = "unreachable";
            } else {
                note = "connecting";
            }
            if (cursor != null && !cursor.isBlank() && !cursor.contains(":")) cursor = null;   // a Box page number
        }
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
        out.put("source", "mail");
        if (note != null) out.put("boxNote", note);
        out.put("items", items);
        out.put("nextCursor", next);
        return out;
    }

    /** The Files list from their Box: MailLook's attachment folders, or Box's name search. Null if Box failed. */
    private Map<String, Object> fromBox(long userId, Long accountId, String kind, String q, String cursor) {
        String needle = q == null ? "" : q.trim();
        List<JsonNode> files = new ArrayList<>();
        String next = null;
        if (!needle.isEmpty()) {
            software.magizhchi.maillook.suite.BoxClient.Reply r = box.searchNames(userId, needle);
            if (!r.ok() || r.json() == null) return null;
            long drive = box.existing(userId).map(software.magizhchi.maillook.suite.BoxClient.Link::driveId).orElse(0L);
            for (JsonNode f : r.json()) if (f.path("conversationId").asLong() == drive) files.add(f);
        } else {
            int page = 0;
            if (cursor != null && !cursor.isBlank()) {
                try {
                    page = Math.max(0, Integer.parseInt(cursor));
                } catch (NumberFormatException e) {
                    page = 0;
                }
            }
            // Read Box pages until there are enough files of this kind to show.
            for (int i = 0; i < 4; i++) {
                software.magizhchi.maillook.suite.BoxClient.Reply r = box.listFiles(userId, BOX_ROOT, page, 60);
                if (!r.ok() || r.json() == null) {
                    if (i == 0) return null;
                    break;
                }
                for (JsonNode f : r.json().path("content")) {
                    if (kind == null || kind.isBlank() || kind.equals(kind(f.path("originalFileName").asText(""), f.path("contentType").asText("")))) files.add(f);
                }
                page++;
                boolean last = r.json().path("last").asBoolean(true);
                next = last ? null : String.valueOf(page);
                if (last || files.size() >= 60) break;
            }
        }
        List<Long> ids = files.stream().map(f -> f.path("id").asLong()).toList();
        Map<Long, long[]> from = box.savedFrom(ids);
        Map<Long, MessageRepo.Row> mails = new java.util.HashMap<>();
        for (MessageRepo.Row m : messages.byIds(new java.util.LinkedHashSet<>(from.values().stream().map(v -> v[0]).toList()))) {
            if (m.userId() == userId) mails.put(m.id(), m);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode f : files) {
            long id = f.path("id").asLong();
            String name = f.path("originalFileName").asText("file");
            String type = f.path("contentType").asText("");
            String k = kind(name, type);
            if (kind != null && !kind.isBlank() && !kind.equals(k)) continue;
            long[] src = from.get(id);
            MessageRepo.Row mail = src == null ? null : mails.get(src[0]);
            if (accountId != null && (mail == null || mail.accountId() != accountId)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("boxFileId", id);
            m.put("name", name);
            m.put("size", f.path("fileSizeBytes").asLong(0));
            m.put("contentType", type);
            m.put("kind", k);
            m.put("folderPath", f.path("folderPath").asText(""));
            m.put("receivedAt", f.path("sentAt").asText(null));
            m.put("inBox", true);
            if (mail != null) {
                m.put("messageId", mail.id());
                m.put("index", (int) src[1]);
                m.put("accountId", mail.accountId());
                m.put("accountColor", mail.accountColor());
                m.put("folder", mail.folder());
                m.put("fromName", mail.fromName());
                m.put("fromEmail", mail.fromEmail());
                m.put("subject", mail.subject());
                if (mail.receivedAt() != null) m.put("receivedAt", mail.receivedAt().toInstant().toString());
            }
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "box");
        out.put("items", items);
        out.put("nextCursor", next);
        out.put("webUrl", box.existing(userId).map(l -> box.webUrl(l.driveId())).orElse(null));
        return out;
    }

    /** Downloads a file from Box: a short-lived download link from Box. */
    @GetMapping("/box/{fileId}/download")
    public Map<String, Object> downloadFromBox(@RequestAttribute long userId, @org.springframework.web.bind.annotation.PathVariable long fileId) {
        String url = box.downloadUrl(userId, fileId).orElseThrow(() ->
                new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "Magizhchi Box couldn't send that file just now."));
        return Map.of("url", url);
    }

    /**
     * Words inside documents, found by Magizhchi Box's own content search over the person's Box drive.
     * Files MailLook saved there are tied back to their mail. Not connected to Box: connected=false.
     */
    @GetMapping("/inside")
    public Map<String, Object> inside(@RequestAttribute long userId, @RequestParam String q) {
        Map<String, Object> out = new LinkedHashMap<>();
        String needle = q == null ? "" : q.trim();
        out.put("connected", box.existing(userId).isPresent());
        out.put("webUrl", box.existing(userId).map(l -> box.webUrl(l.driveId())).orElse(null));
        out.put("results", List.of());
        if (needle.length() < 2 || !(Boolean) out.get("connected")) return out;
        if (needle.length() > 200) throw ApiException.bad("That's a long search. Try a few words.");
        software.magizhchi.maillook.suite.BoxClient.Reply r = box.searchInside(userId, needle);
        if (r.code() == 0) {
            out.put("connected", box.existing(userId).isPresent());
            out.put("unreachable", box.existing(userId).isPresent());
            return out;
        }
        if (!r.ok() || r.json() == null) {
            out.put("error", r.code() == 403 ? "Magizhchi Box doesn't allow searching inside files in your drive." : "Magizhchi Box couldn't search just now.");
            return out;
        }
        JsonNode j = r.json();
        out.put("enabled", j.path("enabled").asBoolean(false));
        out.put("pending", j.path("pending").asInt(0));
        List<String> terms = new ArrayList<>();
        j.path("terms").forEach(t -> terms.add(t.asText()));
        out.put("terms", terms);

        List<Long> fileIds = new ArrayList<>();
        j.path("results").forEach(x -> fileIds.add(x.path("file").path("id").asLong()));
        Map<Long, long[]> from = box.savedFrom(fileIds);
        Map<Long, MessageRepo.Row> mails = new java.util.HashMap<>();
        for (MessageRepo.Row m : messages.byIds(new java.util.LinkedHashSet<>(from.values().stream().map(v -> v[0]).toList()))) {
            if (m.userId() == userId) mails.put(m.id(), m);
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode x : j.path("results")) {
            JsonNode f = x.path("file");
            long id = f.path("id").asLong();
            String name = f.path("originalFileName").asText("file");
            String type = f.path("contentType").asText("");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("boxFileId", id);
            m.put("name", name);
            m.put("contentType", type);
            m.put("kind", kind(name, type));
            m.put("size", f.path("fileSizeBytes").asLong(0));
            m.put("folderPath", f.path("folderPath").asText(""));
            m.put("at", f.path("sentAt").asText(null));
            List<Map<String, Object>> matches = new ArrayList<>();
            for (JsonNode h : x.path("matches")) {
                Map<String, Object> hit = new LinkedHashMap<>();
                hit.put("page", h.path("page").asInt(0));
                hit.put("kind", h.path("kind").asText(""));
                hit.put("snippet", h.path("snippet").asText(""));
                matches.add(hit);
            }
            m.put("matches", matches);
            long[] src = from.get(id);
            MessageRepo.Row mail = src == null ? null : mails.get(src[0]);
            if (mail != null) {
                m.put("messageId", mail.id());
                m.put("index", (int) src[1]);
                m.put("folder", mail.folder());
                m.put("subject", mail.subject());
                m.put("fromName", mail.fromName());
                m.put("fromEmail", mail.fromEmail());
                m.put("accountColor", mail.accountColor());
            }
            results.add(m);
        }
        out.put("results", results);
        return out;
    }

    /** Opens a file found in Box: a short-lived viewing link from Box. */
    @GetMapping("/box/{fileId}/open")
    public Map<String, Object> openInBox(@RequestAttribute long userId, @org.springframework.web.bind.annotation.PathVariable long fileId) {
        String url = box.previewUrl(userId, fileId).orElseThrow(() ->
                new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "Magizhchi Box couldn't open that file just now."));
        return Map.of("url", url);
    }
}
