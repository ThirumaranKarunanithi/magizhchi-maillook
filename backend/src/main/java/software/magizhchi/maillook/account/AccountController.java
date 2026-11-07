package software.magizhchi.maillook.account;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.magizhchi.maillook.common.ApiException;
import software.magizhchi.maillook.mail.SyncService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AccountController {

    public record DetectRequest(String email) {}
    public record BulkRequest(List<AccountService.NewAccount> accounts) {}
    public record PatchRequest(String displayName, String color, String password, String signature) {}

    private final AccountService service;
    private final ProviderDetector detector;
    private final SyncService sync;
    private final OAuthTokens oauth;

    public AccountController(AccountService service, ProviderDetector detector, SyncService sync, OAuthTokens oauth) {
        this.service = service;
        this.detector = detector;
        this.sync = sync;
        this.oauth = oauth;
    }

    @GetMapping("/accounts")
    public List<Map<String, Object>> list(@RequestAttribute long userId) {
        return service.list(userId);
    }

    @PostMapping("/accounts/detect")
    public Map<String, Object> detect(@RequestBody DetectRequest req) {
        ProviderDetector.Preset p = detector.detect(AccountService.normalize(req.email()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", p.provider());
        m.put("imapHost", p.imapHost());
        m.put("imapPort", p.imapPort());
        m.put("smtpHost", p.smtpHost());
        m.put("smtpPort", p.smtpPort());
        m.put("note", p.note());
        // "google" / "microsoft" when this server can sign in there, so the form offers that button.
        m.put("oauth", oauth.offers(p.provider()) ? p.provider() : null);
        m.put("help", SignInHelp.forProvider(p.provider()));
        return m;
    }

    @PostMapping("/accounts")
    public Map<String, Object> add(@RequestAttribute long userId, @RequestBody AccountService.NewAccount req) {
        return AccountService.view(service.add(userId, req), 0);
    }

    @PostMapping("/accounts/bulk")
    public Map<String, Object> bulk(@RequestAttribute long userId, @RequestBody BulkRequest req) {
        if (req.accounts() == null || req.accounts().isEmpty()) throw ApiException.bad("Add at least one mailbox.");
        if (req.accounts().size() > 100) throw ApiException.bad("Add up to 100 mailboxes at a time.");
        return Map.of("results", service.addMany(userId, req.accounts()));
    }

    @PatchMapping("/accounts/{id}")
    public Map<String, Object> patch(@RequestAttribute long userId, @PathVariable long id, @RequestBody PatchRequest req) {
        if (req.signature() != null) service.setSignature(userId, id, req.signature());
        Map<String, Object> view = AccountService.view(service.update(userId, id, req.displayName(), req.color(), req.password()), 0);
        view.put("signature", service.signature(id));
        return view;
    }

    @DeleteMapping("/accounts/{id}")
    public ResponseEntity<Void> delete(@RequestAttribute long userId, @PathVariable long id) {
        service.remove(userId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/accounts/{id}/sync")
    public ResponseEntity<Void> syncOne(@RequestAttribute long userId, @PathVariable long id) {
        service.owned(userId, id);
        sync.request(id);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/sync")
    public ResponseEntity<Void> syncAll(@RequestAttribute long userId) {
        sync.requestForUser(userId);
        return ResponseEntity.accepted().build();
    }
}
