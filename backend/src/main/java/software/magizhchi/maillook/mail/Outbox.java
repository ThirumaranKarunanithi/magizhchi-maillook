package software.magizhchi.maillook.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.magizhchi.maillook.common.ApiException;
import software.magizhchi.maillook.contacts.PeopleService;
import software.magizhchi.maillook.notify.PushService;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mail waiting to go. Pressing Send puts the mail here for the undo window (10 seconds), or until the time
 * the person chose ("Send later"); the server sends it then, whether or not MailLook is still open.
 * Undo, or Edit on a scheduled mail, takes it back out as a draft, files and all.
 */
@Service
public class Outbox {

    private static final Logger log = LoggerFactory.getLogger(Outbox.class);

    public static final int MAX_DELAY_SECONDS = 30;
    static final Duration LONGEST = Duration.ofDays(366);
    /** A send that started this long ago and never finished: the server went down mid-send. */
    static final Duration STUCK = Duration.ofMinutes(10);

    public record Item(long id, long userId, long accountId, JsonNode payload, OffsetDateTime sendAt,
                       String status, String error, OffsetDateTime createdAt) {

        public Map<String, Object> view(List<Map<String, Object>> files) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("accountId", accountId);
            m.put("to", strings(payload.path("to")));
            m.put("cc", strings(payload.path("cc")));
            m.put("subject", payload.path("subject").asText(""));
            m.put("snippet", MessageParser.snippet(MessageParser.ownText(payload.path("text").asText(""))));
            m.put("sendAt", sendAt.toInstant().toString());
            m.put("status", status);
            m.put("error", error);
            m.put("files", files);
            m.put("scheduled", Duration.between(createdAt, sendAt).getSeconds() > MAX_DELAY_SECONDS);
            return m;
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MailActions actions;
    private final MailFiles files;
    private final DraftRepo drafts;
    private final FollowUps followUps;
    private final PeopleService people;
    private final PushService push;

    public Outbox(JdbcTemplate jdbc, ObjectMapper mapper, MailActions actions, MailFiles files, DraftRepo drafts,
                  FollowUps followUps, PeopleService people, PushService push) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.actions = actions;
        this.files = files;
        this.drafts = drafts;
        this.followUps = followUps;
        this.people = people;
        this.push = push;
    }

    static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) n.forEach(x -> { if (!x.asText("").isBlank()) out.add(x.asText()); });
        return out;
    }

    private Item map(java.sql.ResultSet rs) throws java.sql.SQLException {
        JsonNode p;
        try {
            p = mapper.readTree(rs.getString("payload"));
        } catch (Exception e) {
            p = mapper.createObjectNode();
        }
        return new Item(rs.getLong("id"), rs.getLong("user_id"), rs.getLong("account_id"), p,
                rs.getObject("send_at", OffsetDateTime.class), rs.getString("status"), rs.getString("error"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    public Map<String, Object> view(Item i) {
        return i.view(files.forOutbox(i.id()));
    }

    public java.util.Optional<Item> find(long userId, long id) {
        return jdbc.query("SELECT * FROM outbox WHERE id = ? AND user_id = ?", (rs, n) -> map(rs), id, userId).stream().findFirst();
    }

    /** What's waiting or failed, soonest first (the Scheduled view). */
    public List<Map<String, Object>> list(long userId) {
        return jdbc.query("SELECT * FROM outbox WHERE user_id = ? AND status IN ('queued', 'failed', 'sending') ORDER BY send_at, id",
                (rs, n) -> map(rs), userId).stream().map(this::view).toList();
    }

    /** Scheduled for later or failed; mail in its 10-second undo window doesn't count. */
    public long count(long userId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE user_id = ? AND (status = 'failed' OR send_at > ?)",
                Long.class, userId, Timestamp.from(Instant.now().plusSeconds(MAX_DELAY_SECONDS)));
        return n == null ? 0 : n;
    }

    /**
     * Puts a mail in the outbox: its files (uploaded now, plus those kept with its draft) go with it and
     * the draft is done with. {@code sendAt} is when it goes.
     */
    public Item queue(long userId, ObjectNode payload, MailActions.Outgoing o, List<MailActions.OutFile> uploads, Instant sendAt) {
        actions.check(userId, o);
        Instant now = Instant.now();
        if (sendAt.isBefore(now)) sendAt = now;
        if (sendAt.isAfter(now.plus(LONGEST))) throw ApiException.bad("Mail can be scheduled up to a year ahead.");
        Long draftId = payload.path("draftId").canConvertToLong() ? payload.path("draftId").asLong() : null;
        if (draftId != null && drafts.find(userId, draftId).isEmpty()) draftId = null;
        long total = uploads.stream().mapToLong(f -> f.bytes().length).sum() + (draftId == null ? 0 : files.totalForDraft(draftId));
        if (total > MailFiles.MAX_TOTAL) throw ApiException.bad("Files on one mail can add up to 25 MB. Share bigger files as a Box link.");
        payload.remove("draftId");
        payload.remove("sendAt");
        payload.remove("delaySeconds");

        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        Instant at = sendAt;
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO outbox (user_id, account_id, payload, send_at, status, created_at) VALUES (?, ?, ?, ?, 'queued', ?)", new String[]{"id"});
            ps.setLong(1, userId);
            ps.setLong(2, o.accountId());
            ps.setString(3, payload.toString());
            ps.setTimestamp(4, Timestamp.from(at));
            ps.setTimestamp(5, Timestamp.from(now));
            return ps;
        }, keys);
        long id = keys.getKey().longValue();
        files.addToOutbox(userId, id, uploads);
        if (draftId != null) {
            files.draftToOutbox(draftId, id);
            drafts.delete(userId, draftId);
        }
        return find(userId, id).orElseThrow();
    }

    /**
     * Takes a mail back out (Undo, or Edit / Cancel on a scheduled one): it becomes a draft again, with its
     * files. 409 once it has started going.
     */
    public Map<String, Object> takeBack(long userId, long id) {
        Item i = find(userId, id).orElseThrow(() -> ApiException.notFound("Scheduled mail"));
        int claimed = jdbc.update("UPDATE outbox SET status = 'cancelled' WHERE id = ? AND user_id = ? AND status IN ('queued', 'failed')", id, userId);
        if (claimed == 0) throw new ApiException(HttpStatus.CONFLICT, "Too late: it has already gone.");
        JsonNode p = i.payload();
        String mode = p.path("mode").asText("new");
        if (!DraftController.MODES.contains(mode)) mode = "new";
        Long replyTo = p.path("replyToMessageId").canConvertToLong() ? p.path("replyToMessageId").asLong() : null;
        String invite = p.path("inviteCardId").asText(null);
        DraftRepo.Draft d = new DraftRepo.Draft(0, userId, i.accountId(), mode, replyTo,
                strings(p.path("to")), strings(p.path("cc")), strings(p.path("bcc")),
                p.path("subject").asText(""), p.path("text").asText(""), invite == null || invite.isBlank() ? null : invite, null);
        Instant now = Instant.now();
        long draftId = drafts.insert(userId, d, now);
        files.outboxToDraft(id, draftId);
        jdbc.update("DELETE FROM outbox WHERE id = ?", id);
        Map<String, Object> v = drafts.find(userId, draftId).orElseThrow().view();
        v.put("files", files.forDraft(draftId));
        if (p.hasNonNull("remindAt")) v.put("remindAt", p.path("remindAt").asText());
        return v;
    }

    /** Send now (a scheduled mail), or try again (a failed one). */
    public Item sendNow(long userId, long id) {
        int n = jdbc.update("UPDATE outbox SET status = 'queued', error = NULL, send_at = ? WHERE id = ? AND user_id = ? AND status IN ('queued', 'failed')",
                Timestamp.from(Instant.now()), id, userId);
        if (n == 0) throw new ApiException(HttpStatus.CONFLICT, "It has already gone.");
        return find(userId, id).orElseThrow();
    }

    public Item reschedule(long userId, long id, Instant sendAt) {
        Instant now = Instant.now();
        if (sendAt.isBefore(now.plusSeconds(60))) throw ApiException.bad("Pick a time at least a minute from now.");
        if (sendAt.isAfter(now.plus(LONGEST))) throw ApiException.bad("Mail can be scheduled up to a year ahead.");
        int n = jdbc.update("UPDATE outbox SET status = 'queued', error = NULL, send_at = ? WHERE id = ? AND user_id = ? AND status IN ('queued', 'failed')",
                Timestamp.from(sendAt), id, userId);
        if (n == 0) throw new ApiException(HttpStatus.CONFLICT, "It has already gone.");
        return find(userId, id).orElseThrow();
    }

    // ── sending ───────────────────────────────────────────────────

    @Scheduled(initialDelay = 5_000, fixedDelay = 2_000)
    public void tick() {
        try {
            sendDue();
        } catch (Exception e) {
            log.warn("Outbox pass failed: {}", e.getMessage());
        }
    }

    /** Sends everything that is due. Each mail is claimed first, so two servers never send it twice. */
    public int sendDue() {
        Instant now = Instant.now();
        // A send cut off by a restart: we can't know whether it left, so say so rather than send it twice.
        jdbc.update("UPDATE outbox SET status = 'failed', error = ? WHERE status = 'sending' AND send_at < ?",
                "MailLook restarted while sending this. Check Sent before trying again.", Timestamp.from(now.minus(STUCK)));
        List<Long> due = jdbc.queryForList("SELECT id FROM outbox WHERE status = 'queued' AND send_at <= ? ORDER BY send_at LIMIT 20",
                Long.class, Timestamp.from(now));
        int sent = 0;
        for (long id : due) {
            if (jdbc.update("UPDATE outbox SET status = 'sending', attempts = attempts + 1 WHERE id = ? AND status = 'queued'", id) == 0) continue;
            Item i = jdbc.query("SELECT * FROM outbox WHERE id = ?", (rs, n) -> map(rs), id).stream().findFirst().orElse(null);
            if (i == null) continue;
            if (deliver(i)) sent++;
        }
        return sent;
    }

    private boolean deliver(Item i) {
        JsonNode p = i.payload();
        MailActions.Outgoing o;
        try {
            o = mapper.treeToValue(p, MailActions.Outgoing.class);
        } catch (Exception e) {
            fail(i, "The mail couldn't be read.");
            return false;
        }
        try {
            MessageRepo.Row row = actions.deliver(i.userId(), o, files.loadOutbox(i.id()));
            people.forget(i.userId());
            String remindAt = p.path("remindAt").asText(null);
            if (row != null && remindAt != null && !remindAt.isBlank()) {
                try {
                    followUps.set(i.userId(), row.id(), NoteController.future(remindAt, "reminder"));
                } catch (ApiException ignored) {
                    // sent either way
                }
            }
            jdbc.update("DELETE FROM outbox WHERE id = ?", i.id());
            return true;
        } catch (ApiException e) {
            fail(i, e.getMessage());
        } catch (Exception e) {
            fail(i, "Couldn't send: " + e.getMessage());
        }
        return false;
    }

    private void fail(Item i, String why) {
        jdbc.update("UPDATE outbox SET status = 'failed', error = ? WHERE id = ?", why, i.id());
        String subject = i.payload().path("subject").asText("");
        try {
            push.send(i.userId(), "Couldn't send “" + (subject.isBlank() ? "(no subject)" : subject) + "”",
                    why + " Open Scheduled to try again.", "/scheduled", "maillook-outbox-" + i.id());
        } catch (Exception ignored) {
            // alerts are a nicety; the Scheduled view shows it either way
        }
    }
}
