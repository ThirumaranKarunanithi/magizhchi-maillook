package software.magizhchi.maillook.notify;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.magizhchi.maillook.common.ApiException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Alerts: which mail rings (off, Needs me, all) and which browsers get it. */
@RestController
@RequestMapping("/api/push")
public class PushController {

    public record Keys(String p256dh, String auth) {}
    public record SubscribeRequest(String endpoint, Keys keys) {}
    public record UnsubscribeRequest(String endpoint) {}
    public record SettingsRequest(String mode, Integer briefingHour, Boolean briefing, String tz) {}

    private final PushService push;

    public PushController(PushService push) {
        this.push = push;
    }

    @GetMapping
    public Map<String, Object> settings(@RequestAttribute long userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", push.configured());
        m.put("publicKey", push.configured() ? push.publicKey() : null);
        m.put("mode", push.mode(userId));
        m.put("devices", push.devices(userId));
        m.put("briefingHour", push.briefingHour(userId));
        // The phone app (Firebase Cloud Messaging).
        m.put("phonesConfigured", push.phonesConfigured());
        m.put("phones", push.phones(userId));
        return m;
    }

    public record PhoneRequest(String token, String platform) {}

    /** The MailLook phone app signs this phone up for alerts (it does so at every start). */
    @PostMapping("/phone")
    public Map<String, Object> phone(@RequestAttribute long userId, @RequestBody PhoneRequest req) {
        if (blank(req.token()) || req.token().length() > 500) throw ApiException.bad("This phone's alert details look wrong.");
        String platform = "ios".equalsIgnoreCase(req.platform()) ? "ios" : "android";
        push.addPhone(userId, req.token().trim(), platform);
        return settings(userId);
    }

    @PostMapping("/phone/remove")
    public Map<String, Object> removePhone(@RequestAttribute long userId, @RequestBody PhoneRequest req) {
        if (!blank(req.token())) push.removePhone(userId, req.token().trim());
        return settings(userId);
    }

    @PutMapping
    public Map<String, Object> setMode(@RequestAttribute long userId, @RequestBody SettingsRequest req) {
        if (req.mode() != null) {
            String mode = req.mode().trim().toLowerCase(java.util.Locale.ROOT);
            if (!PushService.MODES.contains(mode)) throw ApiException.bad("Alerts can be off, needs_me or all.");
            push.setMode(userId, mode);
        }
        if (req.tz() != null && !req.tz().isBlank()) {
            try {
                push.setZone(userId, java.time.ZoneId.of(req.tz().trim()).getId());
            } catch (java.time.DateTimeException e) {
                throw ApiException.bad("Unknown time zone.");
            }
        }
        if (Boolean.FALSE.equals(req.briefing())) push.setBriefingHour(userId, null);
        else if (req.briefingHour() != null) {
            if (req.briefingHour() < 4 || req.briefingHour() > 12) throw ApiException.bad("The briefing can come between 4 and 12 in the morning.");
            push.setBriefingHour(userId, req.briefingHour());
        }
        return settings(userId);
    }

    @PostMapping("/subscribe")
    public Map<String, Object> subscribe(@RequestAttribute long userId, @RequestBody SubscribeRequest req) {
        if (req.endpoint() == null || !req.endpoint().startsWith("https://") || req.endpoint().length() > 1000
                || req.keys() == null || blank(req.keys().p256dh()) || blank(req.keys().auth())
                || req.keys().p256dh().length() > 200 || req.keys().auth().length() > 100) {
            throw ApiException.bad("This browser's alert details look wrong. Try turning alerts on again.");
        }
        push.subscribe(userId, req.endpoint(), req.keys().p256dh(), req.keys().auth());
        return settings(userId);
    }

    @PostMapping("/unsubscribe")
    public Map<String, Object> unsubscribe(@RequestAttribute long userId, @RequestBody UnsubscribeRequest req) {
        if (req.endpoint() != null) push.unsubscribe(userId, req.endpoint());
        return settings(userId);
    }

    @PostMapping("/test")
    public ResponseEntity<Void> test(@RequestAttribute long userId) {
        if (!push.configured() && !push.phonesConfigured()) throw ApiException.bad("Alerts aren't set up on this server yet.");
        push.send(userId, "Alerts are on ✓", "This is how new mail that needs you will look.", "/?view=needs_me", "maillook-test");
        return ResponseEntity.accepted().build();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
