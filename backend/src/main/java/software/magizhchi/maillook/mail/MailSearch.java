package software.magizhchi.maillook.mail;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the search box asks for. Plain words match subject, sender and body; a few operators narrow it
 * the way people already type them elsewhere: from:, has:attachment, is:unread, is:flagged (is:starred),
 * after:2026-11-01, before:2026-11-30, newer_than:7d (d, w, m, y).
 */
public record MailSearch(String text, String from, boolean hasAttachment, boolean unread, boolean flagged,
                         Instant after, Instant before) {

    public static final MailSearch NONE = new MailSearch(null, null, false, false, false, null, null);

    private static final Pattern OP = Pattern.compile("(?i)(?<![\\w@.])(from|has|is|after|before|newer_than|older_than):(\"[^\"]*\"|\\S+)");

    public boolean isEmpty() {
        return (text == null || text.isBlank()) && (from == null || from.isBlank()) && !hasAttachment && !unread && !flagged
                && after == null && before == null;
    }

    /** Reads the operators out of the box's text; what's left is the plain text search. */
    public static MailSearch parse(String q, ZoneId zone, Instant now) {
        if (q == null || q.isBlank()) return NONE;
        String from = null;
        boolean attach = false, unread = false, flagged = false;
        Instant after = null, before = null;
        StringBuilder rest = new StringBuilder();
        Matcher m = OP.matcher(q);
        int last = 0;
        while (m.find()) {
            String key = m.group(1).toLowerCase(Locale.ROOT);
            String val = m.group(2);
            if (val.startsWith("\"")) val = val.substring(1, val.length() - 1);
            String v = val.trim().toLowerCase(Locale.ROOT);
            boolean used = true;
            switch (key) {
                case "from" -> from = val.trim();
                case "has" -> { if (v.startsWith("attach")) attach = true; else used = false; }
                case "is" -> {
                    if (v.equals("unread")) unread = true;
                    else if (v.equals("flagged") || v.equals("starred")) flagged = true;
                    else used = false;
                }
                case "after" -> { Instant t = day(v, zone, 0); if (t != null) after = t; else used = false; }
                case "before" -> { Instant t = day(v, zone, 0); if (t != null) before = t; else used = false; }
                case "newer_than" -> { Duration d = span(v); if (d != null) after = now.minus(d); else used = false; }
                case "older_than" -> { Duration d = span(v); if (d != null) before = now.minus(d); else used = false; }
                default -> used = false;
            }
            if (used) {
                rest.append(q, last, m.start()).append(' ');
                last = m.end();
            }
        }
        rest.append(q.substring(last));
        String text = rest.toString().replaceAll("\\s+", " ").trim();
        return new MailSearch(text.isEmpty() ? null : text, from, attach, unread, flagged, after, before);
    }

    /** The search box's operators plus the filter chips; a chip set on either side counts. */
    public MailSearch with(String fromParam, Boolean attachParam, Boolean unreadParam, Boolean flaggedParam,
                           Instant afterParam, Instant beforeParam) {
        return new MailSearch(text,
                fromParam != null && !fromParam.isBlank() ? fromParam.trim() : from,
                hasAttachment || Boolean.TRUE.equals(attachParam),
                unread || Boolean.TRUE.equals(unreadParam),
                flagged || Boolean.TRUE.equals(flaggedParam),
                later(after, afterParam), earlier(before, beforeParam));
    }

    private static Instant later(Instant a, Instant b) {
        return a == null ? b : b == null ? a : a.isAfter(b) ? a : b;
    }

    private static Instant earlier(Instant a, Instant b) {
        return a == null ? b : b == null ? a : a.isBefore(b) ? a : b;
    }

    /** 2026-11-01 or 2026/11/01, as the start of that day where the person is. */
    static Instant day(String v, ZoneId zone, int plusDays) {
        try {
            return LocalDate.parse(v.replace('/', '-')).plusDays(plusDays).atStartOfDay(zone).toInstant();
        } catch (DateTimeException e) {
            return null;
        }
    }

    static Duration span(String v) {
        Matcher m = Pattern.compile("^(\\d{1,4})([dwmy])$").matcher(v);
        if (!m.matches()) return null;
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "d" -> Duration.ofDays(n);
            case "w" -> Duration.ofDays(7 * n);
            case "m" -> Duration.ofDays(30 * n);
            default -> Duration.ofDays(365 * n);
        };
    }

    /** LIKE pattern for a piece of text, with the wildcards people didn't mean taken out. */
    static String like(String s) {
        return "%" + s.trim().toLowerCase(Locale.ROOT).replace("%", "").replace("_", "") + "%";
    }

    /** Appends this search's conditions on messages alias m. */
    void appendTo(StringBuilder sql, List<Object> args) {
        if (text != null && !text.isBlank()) {
            String l = like(text);
            sql.append(" AND (LOWER(m.subject) LIKE ? OR LOWER(m.from_email) LIKE ? OR LOWER(m.from_name) LIKE ? OR LOWER(m.body_text) LIKE ?)");
            args.addAll(List.of(l, l, l, l));
        }
        if (from != null && !from.isBlank()) {
            String l = like(from);
            sql.append(" AND (LOWER(m.from_email) LIKE ? OR LOWER(m.from_name) LIKE ?)");
            args.addAll(List.of(l, l));
        }
        if (hasAttachment) sql.append(" AND m.attachments LIKE '[{%'");          // a JSON list with something in it
        if (unread) sql.append(" AND m.seen = FALSE");
        if (flagged) sql.append(" AND m.flagged = TRUE");
        if (after != null) {
            sql.append(" AND m.received_at >= ?");
            args.add(java.time.OffsetDateTime.ofInstant(after, java.time.ZoneOffset.UTC));
        }
        if (before != null) {
            sql.append(" AND m.received_at < ?");
            args.add(java.time.OffsetDateTime.ofInstant(before, java.time.ZoneOffset.UTC));
        }
    }
}
