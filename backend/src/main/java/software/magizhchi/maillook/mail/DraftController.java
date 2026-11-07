package software.magizhchi.maillook.mail;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.magizhchi.maillook.account.AccountRepo;
import software.magizhchi.maillook.common.ApiException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drafts: the composer saves here a moment after each change, and on close. They live in MailLook
 * (not the mailbox's own Drafts folder), so they follow the person across devices that use MailLook.
 */
@RestController
@RequestMapping("/api")
public class DraftController {

    public record DraftRequest(Long id, Long accountId, String mode, Long replyToMessageId,
                               List<String> to, List<String> cc, List<String> bcc,
                               String subject, String body, String inviteCardId) {}

    static final Set<String> MODES = Set.of("new", "reply", "replyAll", "forward");
    static final int MAX_DRAFTS = 500;
    static final int MAX_BODY = 500_000;

    private final DraftRepo drafts;
    private final AccountRepo accounts;
    private final MailActions actions;
    private final MailFiles files;

    public DraftController(DraftRepo drafts, AccountRepo accounts, MailActions actions, MailFiles files) {
        this.files = files;
        this.drafts = drafts;
        this.accounts = accounts;
        this.actions = actions;
    }

    @GetMapping("/drafts")
    public List<Map<String, Object>> list(@RequestAttribute long userId) {
        return drafts.forUser(userId, MAX_DRAFTS).stream().map(this::withFiles).toList();
    }

    @GetMapping("/drafts/{id}")
    public Map<String, Object> get(@RequestAttribute long userId, @PathVariable long id) {
        return withFiles(drafts.find(userId, id).orElseThrow(() -> ApiException.notFound("Draft")));
    }

    private Map<String, Object> withFiles(DraftRepo.Draft d) {
        Map<String, Object> v = d.view();
        v.put("files", files.forDraft(d.id()));
        return v;
    }

    /** Attachments are kept with the draft, so closing the composer (or the tab) doesn't lose them. */
    @PostMapping(value = "/drafts/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<Map<String, Object>> addFiles(@RequestAttribute long userId, @PathVariable long id,
                                              @RequestPart("files") List<MultipartFile> uploads) {
        drafts.find(userId, id).orElseThrow(() -> ApiException.notFound("Draft"));
        return files.addToDraft(userId, id, MailFiles.of(uploads));
    }

    @DeleteMapping("/drafts/{id}/files/{fileId}")
    public List<Map<String, Object>> removeFile(@RequestAttribute long userId, @PathVariable long id, @PathVariable long fileId) {
        drafts.find(userId, id).orElseThrow(() -> ApiException.notFound("Draft"));
        files.removeFromDraft(userId, id, fileId);
        return files.forDraft(id);
    }

    @GetMapping("/drafts/files/{fileId}")
    public ResponseEntity<byte[]> file(@RequestAttribute long userId, @PathVariable long fileId) {
        MailActions.Download d = files.download(userId, fileId);
        MediaType type;
        try {
            type = MediaType.parseMediaType(d.contentType() == null ? "application/octet-stream" : d.contentType());
        } catch (Exception e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.name(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(d.bytes());
    }

    /** Saves a draft: a new one without an id, or the same one again with its id. */
    @PutMapping("/drafts")
    public Map<String, Object> save(@RequestAttribute long userId, @RequestBody DraftRequest req) {
        String mode = req.mode() == null || req.mode().isBlank() ? "new" : req.mode();
        if (!MODES.contains(mode)) throw ApiException.bad("Unknown draft kind.");
        if (req.accountId() != null && accounts.find(req.accountId()).filter(a -> a.userId() == userId).isEmpty()) {
            throw ApiException.notFound("Account");
        }
        Long replyTo = req.replyToMessageId();
        if (replyTo != null) actions.owned(userId, replyTo);                 // their own mail only
        String body = req.body() == null ? "" : req.body();
        if (body.length() > MAX_BODY) throw ApiException.bad("This draft is too long to save.");
        String subject = req.subject() == null ? null : req.subject().length() > 2000 ? req.subject().substring(0, 2000) : req.subject();
        String invite = req.inviteCardId() == null || req.inviteCardId().isBlank() ? null : req.inviteCardId().trim();
        if (invite != null && (invite.length() > 64 || !invite.matches("[A-Za-z0-9-]+"))) invite = null;

        DraftRepo.Draft d = new DraftRepo.Draft(0, userId, req.accountId(), mode, replyTo,
                req.to(), req.cc(), req.bcc(), subject, body, invite, null);
        Instant now = Instant.now();
        long id;
        if (req.id() != null && drafts.update(userId, req.id(), d, now)) {
            id = req.id();
        } else {
            // A draft deleted elsewhere (sent from another tab) comes back as a new one rather than being lost.
            if (drafts.count(userId) >= MAX_DRAFTS) throw ApiException.bad("You have " + MAX_DRAFTS + " drafts. Send or discard some first.");
            id = drafts.insert(userId, d, now);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("savedAt", now.toString());
        return out;
    }

    @DeleteMapping("/drafts/{id}")
    public ResponseEntity<Void> delete(@RequestAttribute long userId, @PathVariable long id) {
        drafts.delete(userId, id);
        return ResponseEntity.noContent().build();
    }
}
