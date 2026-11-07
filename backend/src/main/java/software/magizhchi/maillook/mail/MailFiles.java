package software.magizhchi.maillook.mail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.multipart.MultipartFile;
import software.magizhchi.maillook.common.ApiException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Files kept with a draft or with mail waiting in the outbox, so they survive closing the composer. */
@Repository
public class MailFiles {

    /** What one mail may carry: the same as the upload limit, and what most mail servers accept. */
    public static final long MAX_TOTAL = 25L * 1024 * 1024;
    public static final int MAX_FILES = 20;

    private final JdbcTemplate jdbc;

    public MailFiles(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static MailActions.OutFile of(MultipartFile f) {
        try {
            return new MailActions.OutFile(f.getOriginalFilename() == null || f.getOriginalFilename().isBlank() ? "attachment" : f.getOriginalFilename(),
                    f.getContentType() == null ? "application/octet-stream" : f.getContentType(), f.getBytes());
        } catch (IOException e) {
            throw ApiException.bad("A file couldn't be read: " + f.getOriginalFilename());
        }
    }

    public static List<MailActions.OutFile> of(List<MultipartFile> files) {
        List<MailActions.OutFile> out = new ArrayList<>();
        if (files != null) for (MultipartFile f : files) if (f != null && !f.isEmpty()) out.add(of(f));
        return out;
    }

    private List<Map<String, Object>> meta(String where, Object... args) {
        return jdbc.query("SELECT id, name, content_type, size FROM mail_files WHERE " + where + " ORDER BY id", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("name", rs.getString("name"));
            m.put("contentType", rs.getString("content_type"));
            m.put("size", rs.getLong("size"));
            return m;
        }, args);
    }

    private List<MailActions.OutFile> load(String where, Object... args) {
        return jdbc.query("SELECT name, content_type, data FROM mail_files WHERE " + where + " ORDER BY id",
                (rs, i) -> new MailActions.OutFile(rs.getString("name"), rs.getString("content_type"), rs.getBytes("data")), args);
    }

    public List<Map<String, Object>> forDraft(long draftId) {
        return meta("draft_id = ?", draftId);
    }

    public List<Map<String, Object>> forOutbox(long outboxId) {
        return meta("outbox_id = ?", outboxId);
    }

    public List<MailActions.OutFile> loadDraft(long draftId) {
        return load("draft_id = ?", draftId);
    }

    public List<MailActions.OutFile> loadOutbox(long outboxId) {
        return load("outbox_id = ?", outboxId);
    }

    private long total(String where, Object... args) {
        Long n = jdbc.queryForObject("SELECT COALESCE(SUM(size), 0) FROM mail_files WHERE " + where, Long.class, args);
        return n == null ? 0 : n;
    }

    private long count(String where, Object... args) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM mail_files WHERE " + where, Long.class, args);
        return n == null ? 0 : n;
    }

    /** Adds files to a draft, within the limits for one mail. Returns all of the draft's files. */
    public List<Map<String, Object>> addToDraft(long userId, long draftId, List<MailActions.OutFile> files) {
        long size = total("draft_id = ?", draftId);
        long n = count("draft_id = ?", draftId);
        for (MailActions.OutFile f : files) {
            size += f.bytes().length;
            if (++n > MAX_FILES) throw ApiException.bad("A mail can carry up to " + MAX_FILES + " files.");
            if (size > MAX_TOTAL) throw ApiException.bad("Files on one mail can add up to 25 MB. Share bigger files as a Box link.");
        }
        for (MailActions.OutFile f : files) insert(userId, draftId, null, f);
        return forDraft(draftId);
    }

    public void addToOutbox(long userId, long outboxId, List<MailActions.OutFile> files) {
        for (MailActions.OutFile f : files) insert(userId, null, outboxId, f);
    }

    private void insert(long userId, Long draftId, Long outboxId, MailActions.OutFile f) {
        String name = f.name().length() > 500 ? f.name().substring(f.name().length() - 500) : f.name();
        jdbc.update("INSERT INTO mail_files (user_id, draft_id, outbox_id, name, content_type, size, data) VALUES (?, ?, ?, ?, ?, ?, ?)",
                userId, draftId, outboxId, name, f.contentType(), (long) f.bytes().length, f.bytes());
    }

    public boolean removeFromDraft(long userId, long draftId, long fileId) {
        return jdbc.update("DELETE FROM mail_files WHERE id = ? AND draft_id = ? AND user_id = ?", fileId, draftId, userId) > 0;
    }

    public MailActions.Download download(long userId, long fileId) {
        return jdbc.query("SELECT name, content_type, data FROM mail_files WHERE id = ? AND user_id = ?",
                        (rs, i) -> new MailActions.Download(rs.getString("name"), rs.getString("content_type"), rs.getBytes("data")), fileId, userId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("File"));
    }

    public void draftToOutbox(long draftId, long outboxId) {
        jdbc.update("UPDATE mail_files SET draft_id = NULL, outbox_id = ? WHERE draft_id = ?", outboxId, draftId);
    }

    public void outboxToDraft(long outboxId, long draftId) {
        jdbc.update("UPDATE mail_files SET outbox_id = NULL, draft_id = ? WHERE outbox_id = ?", draftId, outboxId);
    }

    public long totalForDraft(long draftId) {
        return total("draft_id = ?", draftId);
    }
}
