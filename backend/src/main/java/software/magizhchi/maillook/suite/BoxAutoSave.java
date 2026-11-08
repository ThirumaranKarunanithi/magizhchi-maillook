package software.magizhchi.maillook.suite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.magizhchi.maillook.common.ApiException;
import software.magizhchi.maillook.mail.FilesController;
import software.magizhchi.maillook.mail.MailActions;
import software.magizhchi.maillook.mail.MessageRepo;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * For people whose attachments live in Magizhchi Box (Settings → "Where attachments live"), saves every
 * attachment to their own Box by itself, newest mail first and a few files at a time, into the same
 * "Magizhchi MailLook/Attachments/<month>" folders the cloud button uses. Box then reads them, so the Files
 * page can find words inside them.
 *
 * It never loops on trouble: a full Box pauses it for hours, no Magizhchi ID or an unreachable Box pauses it
 * for a while, and a file Box says is too big is skipped for good. The reason shows in Settings.
 */
@Component
public class BoxAutoSave {

    private static final Logger log = LoggerFactory.getLogger(BoxAutoSave.class);
    /** Files per person per round, so one big backlog doesn't hold up everyone else. */
    static final int FILES_PER_ROUND = 12;
    /** Larger attachments are left for the person to save by hand (the cloud button). */
    static final long MAX_AUTO_BYTES = 100L * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final BoxClient box;
    private final MailActions actions;
    private final MessageRepo messages;

    public BoxAutoSave(JdbcTemplate jdbc, BoxClient box, MailActions actions, MessageRepo messages) {
        this.jdbc = jdbc;
        this.box = box;
        this.actions = actions;
        this.messages = messages;
    }

    @Scheduled(initialDelayString = "${maillook.box.auto-save-initial-ms:90000}", fixedDelayString = "${maillook.box.auto-save-ms:120000}")
    public void tick() {
        try {
            runOnce();
        } catch (Exception e) {
            log.warn("Box auto-save round failed: {}", e.toString());
        }
    }

    /** One round over everyone whose attachments live in Box. Returns how many files were saved. */
    public int runOnce() {
        if (!box.configured()) return 0;
        List<Long> users = jdbc.queryForList("SELECT id FROM users WHERE files_store = 'box'"
                + " AND (box_auto_paused_until IS NULL OR box_auto_paused_until < ?) ORDER BY id", Long.class, OffsetDateTime.now());
        int saved = 0;
        for (long userId : users) saved += forUser(userId);
        return saved;
    }

    /** Mails with attachments the automatic save hasn't finished with (Trash and Spam left out). */
    public long backlog(long userId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM messages m JOIN accounts a ON a.id = m.account_id"
                + " WHERE a.user_id = ? AND m.attachments LIKE '[{%' AND m.folder NOT IN ('TRASH', 'SPAM')"
                + " AND NOT EXISTS (SELECT 1 FROM box_auto_done d WHERE d.message_id = m.id)", Long.class, userId);
        return n == null ? 0 : n;
    }

    int forUser(long userId) {
        List<Long> ids = jdbc.queryForList("SELECT m.id FROM messages m JOIN accounts a ON a.id = m.account_id"
                + " WHERE a.user_id = ? AND m.attachments LIKE '[{%' AND m.folder NOT IN ('TRASH', 'SPAM')"
                + " AND NOT EXISTS (SELECT 1 FROM box_auto_done d WHERE d.message_id = m.id)"
                + " ORDER BY m.received_at DESC, m.id DESC LIMIT 40", Long.class, userId);
        if (ids.isEmpty()) return 0;
        Map<Long, MessageRepo.Row> rows = new LinkedHashMap<>();
        for (MessageRepo.Row r : messages.byIds(new LinkedHashSet<>(ids))) if (r.userId() == userId) rows.put(r.id(), r);
        Set<String> saved = new HashSet<>(box.savedKeys(ids));
        Set<String> skipped = new HashSet<>(skipKeys(ids));
        int done = 0;
        for (long id : ids) {
            MessageRepo.Row msg = rows.get(id);
            if (msg == null) continue;
            if (msg.attachments() == null) {
                markDone(id);
                continue;
            }
            boolean finished = true;
            for (Map<String, Object> a : msg.attachments()) {
                int index = ((Number) a.getOrDefault("index", 0)).intValue();
                String key = id + ":" + index;
                String name = String.valueOf(a.getOrDefault("name", "attachment"));
                String type = String.valueOf(a.getOrDefault("contentType", ""));
                if (saved.contains(key) || skipped.contains(key) || FilesController.noise(name, type)) continue;
                if (a.get("size") instanceof Number n && n.longValue() > MAX_AUTO_BYTES) {
                    skip(id, index, "too_big");
                    continue;
                }
                if (done >= FILES_PER_ROUND) {
                    finished = false;
                    break;
                }
                MailActions.Download d;
                try {
                    d = actions.attachment(userId, id, index);
                } catch (ApiException e) {
                    if (e.status() == HttpStatus.NOT_FOUND) {
                        skip(id, index, "gone");
                        continue;
                    }
                    pause(userId, "unreachable", "Couldn't fetch attachments from your mailbox just now.", Duration.ofMinutes(30));
                    return done;
                } catch (RuntimeException e) {
                    skip(id, index, "failed");
                    continue;
                }
                try {
                    long fileId = box.upload(userId, d.name(), d.contentType(), d.bytes(), BoxController.folderFor(msg));
                    try {
                        box.recordSave(id, index, fileId, BoxController.folderFor(msg));
                    } catch (DuplicateKeyException ignored) {
                        // saved by hand at the same moment
                    }
                    done++;
                    clearTrouble(userId);
                } catch (ApiException e) {
                    String said = e.getMessage() == null ? "" : e.getMessage();
                    if (e.status() == HttpStatus.PAYLOAD_TOO_LARGE && said.contains("full")) {
                        pause(userId, "full", said, Duration.ofHours(6));
                        return done;
                    }
                    if (e.status() == HttpStatus.PAYLOAD_TOO_LARGE) {
                        skip(id, index, "too_big");
                        continue;
                    }
                    if (e.status() == HttpStatus.CONFLICT) {
                        pause(userId, "no_id", said, Duration.ofDays(1));
                        return done;
                    }
                    pause(userId, "unreachable", said, Duration.ofMinutes(15));
                    return done;
                }
            }
            if (!finished) return done;
            markDone(id);
        }
        return done;
    }

    private List<String> skipKeys(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        String in = String.join(",", ids.stream().map(x -> "?").toList());
        return jdbc.query("SELECT message_id, att_index FROM box_skips WHERE message_id IN (" + in + ")",
                (rs, i) -> rs.getLong(1) + ":" + rs.getInt(2), ids.toArray());
    }

    private void skip(long messageId, int index, String reason) {
        try {
            jdbc.update("INSERT INTO box_skips (message_id, att_index, reason) VALUES (?, ?, ?)", messageId, index, reason);
        } catch (DuplicateKeyException ignored) {
            // already noted
        }
    }

    private void markDone(long messageId) {
        try {
            jdbc.update("INSERT INTO box_auto_done (message_id) VALUES (?)", messageId);
        } catch (DuplicateKeyException ignored) {
            // already done
        }
    }

    private void pause(long userId, String state, String note, Duration d) {
        jdbc.update("UPDATE users SET box_auto_state = ?, box_auto_note = ?, box_auto_paused_until = ? WHERE id = ?",
                state, note == null ? null : note.length() > 400 ? note.substring(0, 400) : note, OffsetDateTime.now().plus(d), userId);
    }

    private void clearTrouble(long userId) {
        jdbc.update("UPDATE users SET box_auto_state = NULL, box_auto_note = NULL, box_auto_paused_until = NULL"
                + " WHERE id = ? AND box_auto_state IS NOT NULL", userId);
    }

    /** "box" or "local". */
    public String store(long userId) {
        List<String> s = jdbc.queryForList("SELECT files_store FROM users WHERE id = ?", String.class, userId);
        return s.isEmpty() || s.get(0) == null ? "box" : s.get(0);
    }

    /** Switching to Box clears an old pause so saving starts on the next round. */
    public void setStore(long userId, String store) {
        if (!"box".equals(store) && !"local".equals(store)) throw ApiException.bad("Attachments live in Box or on this computer.");
        jdbc.update("UPDATE users SET files_store = ?, box_auto_paused_until = NULL, box_auto_state = NULL, box_auto_note = NULL WHERE id = ?",
                store, userId);
    }

    /** The automatic save's trouble, if any: { state, note }. */
    public Map<String, Object> trouble(long userId) {
        return jdbc.query("SELECT box_auto_state, box_auto_note FROM users WHERE id = ?", rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            if (rs.next()) {
                m.put("state", rs.getString(1));
                m.put("note", rs.getString(2));
            }
            return m;
        }, userId);
    }
}
