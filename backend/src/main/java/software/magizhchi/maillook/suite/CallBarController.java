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

    public CallBarController(MailLookUsers users, SuiteCalls calls) {
        this.users = users;
        this.calls = calls;
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
