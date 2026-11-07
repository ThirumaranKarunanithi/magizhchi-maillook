package software.magizhchi.maillook.bills;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The free first look: does this mail look like a bill? Word rules only, no AI, so every new mail
 * can be checked and only the likely ones are sent to Magizhchi Data (which costs credits).
 *
 * <p>It leans towards yes: a false yes costs one read that Data answers "other"; a false no is a
 * bill the person never hears about. The person can still say "This is a bill" on any mail.
 */
public final class BillDetector {

    private BillDetector() {}

    /** Words that make a subject or file name a bill on their own. */
    private static final Pattern STRONG = Pattern.compile(
            "\\b(invoice|tax invoice|bill|billing statement|e-?bill|payment due|amount due|due date|renewal|" +
            "subscription renew\\w*|pay now|proforma|outstanding|receipt)\\b|பில்|கட்டண", Pattern.CASE_INSENSITIVE);

    /** Words that point to a bill when there's also an amount in the mail. */
    private static final Pattern BODY = Pattern.compile(
            "\\b(invoice|bill amount|amount due|total due|amount payable|net payable|due date|due on|due by|" +
            "pay before|payable|gstin|hsn|sac|cgst|sgst|igst|outstanding|last date)\\b|கட்டணம்|செலுத்த",
            Pattern.CASE_INSENSITIVE);

    /** An amount of money: ₹ 1,180, Rs. 499, INR 2000.00, USD 12.50, $12. */
    private static final Pattern MONEY = Pattern.compile(
            "(₹|\\brs\\.?|\\binr\\b|\\busd\\b|\\$)\\s?[0-9][0-9,]*(\\.[0-9]{1,2})?", Pattern.CASE_INSENSITIVE);

    /** Mail that talks about money but is not a bill to pay. Bank alerts wait for Phase 2. */
    private static final Pattern NOT = Pattern.compile(
            "\\b(otp|one[- ]time password|verification code|debited|credited|has been debited|has been credited|" +
            "upi txn|deployment|build failed|crashed|webinar|newsletter|sale ends|cashback offer|" +
            "pre-?approved|loan offer|credit card offer|quotation|job alert)\\b|\\d+\\s?% off", Pattern.CASE_INSENSITIVE);

    private static final Pattern BILL_FILE = Pattern.compile("(invoice|bill|receipt|inv[-_ ]?\\d|statement)",
            Pattern.CASE_INSENSITIVE);

    /**
     * @param senderRule TRUE when the person said this sender always sends bills, FALSE never, null unknown
     */
    public static boolean looksLikeBill(String subject, String body, List<String> attachmentNames, Boolean senderRule) {
        if (Boolean.FALSE.equals(senderRule)) return false;
        if (Boolean.TRUE.equals(senderRule)) return true;
        String s = subject == null ? "" : subject;
        String b = body == null ? "" : body.length() > 20_000 ? body.substring(0, 20_000) : body;
        if (NOT.matcher(s).find()) return false;

        boolean strongSubject = STRONG.matcher(s).find();
        boolean billFile = attachmentNames != null && attachmentNames.stream()
                .anyMatch(n -> n != null && BILL_FILE.matcher(n).find() && readable(n));
        boolean money = MONEY.matcher(s).find() || MONEY.matcher(b).find();
        boolean bodyWords = BODY.matcher(b).find();

        if (strongSubject && (money || bodyWords || billFile)) return true;
        // A PDF or photo named like an invoice is reason enough, even forwarded with no words.
        if (billFile) return true;
        // No bill words in the subject: the body must say so clearly, and not be a bank alert.
        return bodyWords && money && !NOT.matcher(b.length() > 2000 ? b.substring(0, 2000) : b).find();
    }

    /** Files Data can read: PDFs and pictures. */
    public static boolean readable(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.endsWith(".pdf") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp");
    }
}
