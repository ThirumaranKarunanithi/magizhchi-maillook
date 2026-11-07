package software.magizhchi.maillook.mail;

import jakarta.mail.Store;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.magizhchi.maillook.account.Account;
import software.magizhchi.maillook.account.AccountRepo;
import software.magizhchi.maillook.common.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The mailbox's own folders (and Gmail labels). Listing asks the mail server, so the answer is kept for
 * a few minutes. Opening one starts syncing it; from then on it syncs with the rest.
 */
@RestController
@RequestMapping("/api")
public class FolderController {

    public record OpenRequest(Long accountId, String path) {}

    private record Listed(List<String> names, long at) {}

    static final long CACHE_MS = 5 * 60_000L;

    private final AccountRepo accounts;
    private final ImapClient imap;
    private final FolderCursors cursors;
    private final SyncService sync;
    private final Map<Long, Listed> cache = new ConcurrentHashMap<>();

    public FolderController(AccountRepo accounts, ImapClient imap, FolderCursors cursors, SyncService sync) {
        this.accounts = accounts;
        this.imap = imap;
        this.cursors = cursors;
        this.sync = sync;
    }

    @GetMapping("/folders")
    public List<Map<String, Object>> list(@RequestAttribute long userId, @RequestParam(defaultValue = "false") boolean refresh) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Account a : accounts.forUser(userId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("accountId", a.id());
            m.put("email", a.email());
            Set<String> opened = cursors.forAccount(a.id()).stream().map(FolderCursors.Cursor::code)
                    .filter(c -> c.startsWith("F:")).collect(Collectors.toSet());
            List<Map<String, Object>> folders = new ArrayList<>();
            try {
                for (String name : names(a, refresh)) {
                    String code = "F:" + name;
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("code", code);
                    f.put("path", name);
                    f.put("name", shortName(name));
                    f.put("opened", opened.contains(code));
                    folders.add(f);
                }
            } catch (ApiException e) {
                m.put("error", e.getMessage());
            }
            m.put("folders", folders);
            out.add(m);
        }
        return out;
    }

    /** Starts syncing one of the mailbox's folders; returns its code for the list endpoint. */
    @PostMapping("/folders/open")
    public Map<String, Object> open(@RequestAttribute long userId, @RequestBody OpenRequest req) {
        if (req.accountId() == null || req.path() == null || req.path().isBlank()) throw ApiException.bad("Pick a folder.");
        Account a = accounts.find(req.accountId()).filter(x -> x.userId() == userId).orElseThrow(() -> ApiException.notFound("Account"));
        String path = req.path();
        if (!names(a, false).contains(path)) throw ApiException.notFound("Folder");
        String code = "F:" + path;
        cursors.open(a.id(), code, path);
        sync.request(a.id());
        return Map.of("code", code, "name", shortName(path));
    }

    private List<String> names(Account a, boolean refresh) {
        Listed l = cache.get(a.id());
        if (!refresh && l != null && System.currentTimeMillis() - l.at() < CACHE_MS) return l.names();
        Store store = null;
        try {
            store = imap.connect(a);
            List<String> names = imap.ownFolders(store);
            cache.put(a.id(), new Listed(names, System.currentTimeMillis()));
            return names;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw imap.friendly(e, a.imapHost());
        } finally {
            ImapClient.closeQuietly(store);
        }
    }

    /** "Work/Clients/Acme" shows as "Acme"; the full path stays in the tooltip. */
    static String shortName(String path) {
        int slash = path.lastIndexOf('/');
        if (slash >= 0 && slash < path.length() - 1) return path.substring(slash + 1);
        // Servers that nest with dots put everything under "INBOX."; other dots are part of the name.
        if (path.regionMatches(true, 0, "INBOX.", 0, 6) && !path.endsWith(".")) return path.substring(path.lastIndexOf('.') + 1);
        return path;
    }
}
