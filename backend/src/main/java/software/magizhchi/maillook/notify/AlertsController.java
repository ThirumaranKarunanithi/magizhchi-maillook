package software.magizhchi.maillook.notify;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The alert feed for the desktop app: the same alerts phones get by Web Push, read every half minute.
 * Without {@code after} it only says where the feed is now, so a first start doesn't ring for old alerts.
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertsController {

    static final int LIMIT = 50;

    private final PushService push;

    public AlertsController(PushService push) {
        this.push = push;
    }

    @GetMapping
    public Map<String, Object> feed(@RequestAttribute long userId,
                                    @RequestParam(required = false) Long after,
                                    @RequestParam(required = false) String tz) {
        push.desktopSeen(userId, tz);
        Map<String, Object> m = new LinkedHashMap<>();
        if (after == null) {
            m.put("items", List.of());
            m.put("last", push.lastAlertId(userId));
            return m;
        }
        List<PushService.Alert> items = push.alertsAfter(userId, Math.max(0, after), LIMIT);
        m.put("items", items);
        m.put("last", items.isEmpty() ? Math.max(after, 0) : items.get(items.size() - 1).id());
        m.put("more", items.size() == LIMIT);
        return m;
    }
}
