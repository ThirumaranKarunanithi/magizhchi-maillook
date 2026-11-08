package software.magizhchi.maillook.bills;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A bank, card or UPI alert that money went out: "Rs.6,010.92 debited from A/c XX1234 to VPA
 * actfibernet@ybl", "INR 11,140.20 spent on your credit card at ANTHROPIC", "You paid ₹2,271.50 to
 * ACT Fibernet". Read with plain patterns, free; Data is not asked.
 *
 * @param inr             rupees that went out, or null (a foreign card charge may give only dollars)
 * @param foreignCurrency USD, EUR … when the alert names one, else null
 * @param foreignAmount   the amount in that currency, or null
 * @param payee           who it went to as the alert says it (a name or a UPI ID), or null
 */
public record PaymentAlert(BigDecimal inr, String foreignCurrency, BigDecimal foreignAmount, String payee) {

    private static final Pattern OUT = Pattern.compile(
            "\\b(debited|spent|withdrawn|has been charged|charged (to|on) your|you (have )?paid|paid to|sent to|payment of [^.]{0,40}(is|was|has been) (successful|received|made)"
                    + "|txn of|transaction of|purchase of|used for a transaction|auto.?debit(ed)? of)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT = Pattern.compile(
            "\\bOTP\\b|one.time password|will be (debited|charged|deducted)|is due\\b|due (on|by|date)|\\bfailed\\b|declined|unsuccessful|reversed|refund"
                    + "|request(ed)? (money|payment)|has requested|collect request|statement|\\binvoice\\b|amount due|total due|billing period",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CREDITED = Pattern.compile("\\bcredited\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEBITED = Pattern.compile("\\bdebited\\b|\\bspent\\b|\\bpaid\\b|\\bsent\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RUPEES = Pattern.compile("(?:\\bRs\\.?|\\bINR|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOREIGN = Pattern.compile(
            "\\b(USD|EUR|GBP|SGD|AED|AUD|CAD|JPY)\\s*\\$?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)|(?<![A-Za-z])\\$\\s*([0-9][0-9,]*\\.[0-9]{2})");
    private static final Pattern PAYEE = Pattern.compile(
            "(?:\\bto\\b(?:\\s+VPA\\b)?|\\bat\\b|\\btowards\\b|\\bVPA\\b|\\bInfo:?)\\s*:?\\s*([A-Za-z0-9@._&*\\- ]{3,48}?)"
                    + "(?=\\s+(?:on|via|ref|avl|avbl|from|using|with|for|is|has|was|dated|date|upi|txn|-)\\b|\\s*[.,;()]|\\s*$)",
            Pattern.CASE_INSENSITIVE);

    /** The alert in this mail, or null when it isn't money going out. */
    public static PaymentAlert parse(String subject, String body) {
        String text = ((subject == null ? "" : subject) + "\n" + (body == null ? "" : body));
        if (text.length() > 4000) text = text.substring(0, 4000);
        text = text.replace(' ', ' ');
        if (!OUT.matcher(text).find() || NOT.matcher(text).find()) return null;
        if (CREDITED.matcher(text).find() && !DEBITED.matcher(text).find()) return null;

        BigDecimal inr = null;
        Matcher r = RUPEES.matcher(text);
        if (r.find()) inr = number(r.group(1));
        String cur = null;
        BigDecimal foreign = null;
        Matcher f = FOREIGN.matcher(text);
        if (f.find()) {
            cur = f.group(1) != null ? f.group(1).toUpperCase(Locale.ROOT) : "USD";
            foreign = number(f.group(2) != null ? f.group(2) : f.group(3));
        }
        if ((inr == null || inr.signum() <= 0) && (foreign == null || foreign.signum() <= 0)) return null;
        if (inr != null && inr.signum() <= 0) inr = null;
        if (foreign == null) cur = null;

        String payee = null;
        Matcher p = PAYEE.matcher(text);
        while (p.find()) {
            String v = p.group(1).trim().replaceAll("\\s+", " ");
            String low = v.toLowerCase(Locale.ROOT);
            if (low.startsWith("your") || low.startsWith("a/c") || low.startsWith("ac ") || low.startsWith("account") || low.startsWith("card")
                    || low.startsWith("the ") || low.matches("[x*0-9 ]+") || low.length() < 3) continue;
            payee = v;
            break;
        }
        return new PaymentAlert(inr, cur, foreign, payee);
    }

    /** Does the payee name this biller? ("actfibernet@ybl" and "ACT FIBERNET PVT" both name "act"). */
    public boolean names(String vendorFirstWord) {
        if (payee == null || vendorFirstWord == null || vendorFirstWord.length() < 3) return false;
        return payee.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "").contains(vendorFirstWord);
    }

    private static BigDecimal number(String s) {
        try {
            return new BigDecimal(s.replace(",", "")).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return null;
        }
    }
}
