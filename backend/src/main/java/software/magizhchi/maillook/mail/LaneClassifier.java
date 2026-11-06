package software.magizhchi.maillook.mail;

import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * First-pass sorting into the three lanes, no AI needed:
 * <ul>
 *   <li><b>can_wait</b> — newsletters, promotions, mailing lists (List-Unsubscribe, Precedence: bulk…)</li>
 *   <li><b>fyi</b> — automated notices (no-reply senders, alerts, receipts), mail you're only CC'd on</li>
 *   <li><b>needs_me</b> — a person wrote to you directly</li>
 * </ul>
 * Sender rules (the user's own choices) and AI refinement run on top of this.
 */
public final class LaneClassifier {

    public static final String NEEDS_ME = "needs_me";
    public static final String FYI = "fyi";
    public static final String CAN_WAIT = "can_wait";

    private static final Pattern AUTOMATED_LOCAL = Pattern.compile(
            "^(no-?reply|noreply|do-?not-?reply|donotreply|notifications?|notify|alerts?|mailer-daemon|postmaster|" +
            "bounces?|newsletters?|news|updates?|marketing|digest|auto-?confirm|automated|system|robot|txn|transactions?|" +
            "statements?|ebill|e-?statements?|otp)([.+_-].*)?$");

    private LaneClassifier() {}

    public static boolean isLane(String lane) {
        return NEEDS_ME.equals(lane) || FYI.equals(lane) || CAN_WAIT.equals(lane);
    }

    public static String classify(String fromEmail, Collection<String> toEmails, int toCount,
                                  String accountEmail, Collection<String> ownEmails,
                                  String listUnsubscribe, String listId, String precedence, String autoSubmitted) {
        String from = fromEmail == null ? "" : fromEmail.toLowerCase(Locale.ROOT);
        if (ownEmails.contains(from)) return FYI;

        String prec = precedence == null ? "" : precedence.trim().toLowerCase(Locale.ROOT);
        boolean bulk = listUnsubscribe != null || listId != null
                || prec.equals("bulk") || prec.equals("list") || prec.equals("junk");
        if (bulk) return CAN_WAIT;

        int at = from.indexOf('@');
        String local = at > 0 ? from.substring(0, at) : from;
        boolean automated = AUTOMATED_LOCAL.matcher(local).matches()
                || (autoSubmitted != null && !autoSubmitted.trim().equalsIgnoreCase("no"));
        if (automated) return FYI;

        boolean direct = accountEmail != null && toEmails.contains(accountEmail.toLowerCase(Locale.ROOT));
        if (!direct || toCount > 10) return FYI;
        return NEEDS_ME;
    }
}
