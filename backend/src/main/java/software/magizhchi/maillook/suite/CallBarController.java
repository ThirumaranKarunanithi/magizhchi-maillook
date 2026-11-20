package software.magizhchi.maillook.suite;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.magizhchi.maillook.common.ApiException;
import software.magizhchi.suite.SuiteException;
import software.magizhchi.suite.calls.SuiteCalls;
import software.magizhchi.suite.chat.SuiteChat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The call bar in MailLook: Magizhchi Meet's shared calling script ({@code meet.magizhchi.software/sdk/magizhchi-call.js})
 * rings here when someone calls this person, and Answer opens the call in Meet. This hands the signed-in person
 * their OWN ring pass, which MailLook's server gets from Meet with the suite's provision secret.
 */
@RestController
@RequestMapping("/api")
public class CallBarController {

    private final MailLookUsers users;
    private final SuiteCalls calls;
    private final SuiteChat chat;

    public CallBarController(MailLookUsers users, SuiteCalls calls, SuiteChat chat) {
        this.users = users;
        this.calls = calls;
        this.chat = chat;
    }

    /**
     * The Magizhchi bar ({@code messenger.magizhchi.software/bar/magizhchi-bar.js}): chat through Magizhchi
     * Messenger (stored in Box chat) and calls through Meet. Both passes, only ever the signed-in person's own:
     * {@code chat} (Box chat, required) and {@code ring} (Meet; null when calls are off or Meet refuses, so chat
     * still works). Under /api/calls, not /api/suite: /api/suite/** carries the suite secret, not a session.
     */
    @GetMapping("/calls/bar-pass")
    public Map<String, Object> barPass(@RequestAttribute long userId,
                                       @RequestParam(value = "device", required = false) String device,
                                       @RequestParam(value = "client", required = false) String client) {
        SuiteTokens.Identity me = users.identity(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "Sign in with Magizhchi ID to chat in MailLook."));
        if (!chat.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Chat isn't set up on this server.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            out.put("chat", chat.chatPass(me.accountId(), me.email(), me.name()).raw());
        } catch (SuiteException e) {
            // Never a 401: the page would take it as its own sign-in ending.
            HttpStatus status = HttpStatus.resolve(e.status());
            throw new ApiException(status == null || status == HttpStatus.UNAUTHORIZED ? HttpStatus.BAD_GATEWAY : status, e.getMessage());
        }
        Map<String, Object> ring = null;
        if (calls.configured()) {
            try {
                ring = calls.ringPass(me.accountId(), me.email(), me.name(), device == null ? null : device.trim()).raw();
            } catch (SuiteException e) {
                ring = null;   // calls stay off in the bar; chat carries on
            }
        }
        out.put("ring", ring);
        return out;
    }

    @GetMapping("/calls/ring-pass")
    public Map<String, Object> ringPass(@RequestAttribute long userId,
                                        @RequestParam(value = "device", required = false) String device,
                                        @RequestParam(value = "client", required = false) String client) {
        // Calls name people by their Magizhchi ID account; a MailLook user from before Magizhchi ID has none to ring.
        SuiteTokens.Identity me = users.identity(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "Sign in with Magizhchi ID to take calls in MailLook."));
        if (!calls.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Calls aren't set up on this server.");
        }
        try {
            return calls.ringPass(me.accountId(), me.email(), me.name(), device == null ? null : device.trim()).raw();
        } catch (SuiteException e) {
            HttpStatus status = HttpStatus.resolve(e.status());
            throw new ApiException(status == null ? HttpStatus.BAD_GATEWAY : status, e.getMessage());
        }
    }
}
