package software.magizhchi.maillook.suite;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Just enough iCalendar (RFC 5545) to read a meeting invite and answer it: the first event's time,
 * title, place, organizer and attendees, and the REPLY that says yes, maybe or no (RFC 5546).
 */
public final class Ics {

    public record Person(String name, String email, String status) {}

    public record Event(String method, String uid, int sequence, String title, String location, String description,
                        Instant start, Instant end, boolean allDay, LocalDate startDate, LocalDate endDate,
                        Person organizer, List<Person> attendees, String status, String joinUrl) {
        public boolean cancelled() {
            return "CANCEL".equals(method) || "CANCELLED".equals(status);
        }
    }

    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final Pattern LINK = Pattern.compile("https://[^\\s<>\"']*(meet\\.google\\.com|zoom\\.us|teams\\.microsoft\\.com|teams\\.live\\.com|meet\\.magizhchi\\.software|webex\\.com)[^\\s<>\"']*");

    /** Outlook writes Windows zone names; the common ones, mapped. */
    private static final Map<String, String> WINDOWS = Map.ofEntries(
            Map.entry("india standard time", "Asia/Kolkata"),
            Map.entry("sri lanka standard time", "Asia/Colombo"),
            Map.entry("singapore standard time", "Asia/Singapore"),
            Map.entry("arabian standard time", "Asia/Dubai"),
            Map.entry("gmt standard time", "Europe/London"),
            Map.entry("w. europe standard time", "Europe/Berlin"),
            Map.entry("romance standard time", "Europe/Paris"),
            Map.entry("eastern standard time", "America/New_York"),
            Map.entry("central standard time", "America/Chicago"),
            Map.entry("mountain standard time", "America/Denver"),
            Map.entry("pacific standard time", "America/Los_Angeles"),
            Map.entry("aus eastern standard time", "Australia/Sydney"),
            Map.entry("tokyo standard time", "Asia/Tokyo"),
            Map.entry("china standard time", "Asia/Shanghai"),
            Map.entry("utc", "UTC"),
            Map.entry("coordinated universal time", "UTC"));

    private Ics() {}

    /** A content line: its name, parameters and value. */
    record Line(String name, Map<String, String> params, String value) {
        String param(String p) {
            return params.get(p);
        }
    }

    static List<Line> lines(String ics) {
        List<Line> out = new ArrayList<>();
        // Unfold: a line starting with a space or tab continues the one before.
        String unfolded = ics.replace("\r\n", "\n").replace('\r', '\n').replaceAll("\n[ \t]", "");
        for (String raw : unfolded.split("\n")) {
            if (raw.isBlank()) continue;
            int colon = valueColon(raw);
            if (colon < 0) continue;
            String head = raw.substring(0, colon);
            String value = raw.substring(colon + 1);
            String[] parts = head.split(";(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
            Map<String, String> params = new LinkedHashMap<>();
            for (int i = 1; i < parts.length; i++) {
                int eq = parts[i].indexOf('=');
                if (eq > 0) params.put(parts[i].substring(0, eq).toUpperCase(Locale.ROOT), unquote(parts[i].substring(eq + 1)));
            }
            out.add(new Line(parts[0].toUpperCase(Locale.ROOT), params, value));
        }
        return out;
    }

    /** The colon that starts the value: the first one outside quoted parameter values. */
    private static int valueColon(String s) {
        boolean q = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') q = !q;
            else if (c == ':' && !q) return i;
        }
        return -1;
    }

    /** The first event in the invite, or null when there is none. {@code zone} is for times with no zone. */
    public static Event parse(String ics, ZoneId zone) {
        if (ics == null || ics.isBlank()) return null;
        String method = null;
        boolean inEvent = false, done = false, nested = false;
        String uid = null, title = null, location = null, description = null, status = null, url = null;
        int sequence = 0;
        Line dtStart = null, dtEnd = null;
        String duration = null;
        Person organizer = null;
        List<Person> attendees = new ArrayList<>();
        for (Line l : lines(ics)) {
            if (done) break;
            switch (l.name()) {
                case "METHOD" -> { if (!inEvent) method = l.value().trim().toUpperCase(Locale.ROOT); }
                case "BEGIN" -> {
                    if ("VEVENT".equalsIgnoreCase(l.value().trim())) inEvent = true;
                    else if (inEvent) nested = true;           // a VALARM inside the event
                }
                case "END" -> {
                    if ("VEVENT".equalsIgnoreCase(l.value().trim())) done = inEvent;
                    else if (inEvent) nested = false;
                }
                default -> {
                    if (!inEvent || nested) continue;
                    switch (l.name()) {
                        case "UID" -> uid = l.value().trim();
                        case "SEQUENCE" -> { try { sequence = Integer.parseInt(l.value().trim()); } catch (NumberFormatException ignored) { } }
                        case "SUMMARY" -> title = text(l.value());
                        case "LOCATION" -> location = text(l.value());
                        case "DESCRIPTION" -> description = text(l.value());
                        case "STATUS" -> status = l.value().trim().toUpperCase(Locale.ROOT);
                        case "URL" -> url = l.value().trim();
                        case "DTSTART" -> dtStart = l;
                        case "DTEND" -> dtEnd = l;
                        case "DURATION" -> duration = l.value().trim();
                        case "ORGANIZER" -> organizer = person(l);
                        case "ATTENDEE" -> attendees.add(person(l));
                        default -> { }
                    }
                }
            }
        }
        if (!inEvent || dtStart == null) return null;
        boolean allDay = "DATE".equalsIgnoreCase(dtStart.param("VALUE")) || dtStart.value().trim().length() == 8;
        Instant start, end;
        LocalDate startDate = null, endDate = null;
        if (allDay) {
            startDate = LocalDate.parse(dtStart.value().trim().substring(0, 8), DAY);
            endDate = dtEnd != null ? LocalDate.parse(dtEnd.value().trim().substring(0, 8), DAY) : startDate.plusDays(1);
            start = startDate.atStartOfDay(zone).toInstant();
            end = endDate.atStartOfDay(zone).toInstant();
        } else {
            start = instant(dtStart, zone);
            if (dtEnd != null) end = instant(dtEnd, zone);
            else if (duration != null) end = start.plus(duration(duration));
            else end = start.plus(Duration.ofHours(1));
        }
        String join = null;
        for (String s : new String[]{location, url, description}) {
            if (s == null) continue;
            Matcher m = LINK.matcher(s);
            if (m.find()) {
                join = m.group();
                break;
            }
        }
        return new Event(method == null ? "PUBLISH" : method, uid, sequence, title, location, description, start, end, allDay,
                startDate, endDate, organizer, attendees, status, join);
    }

    static Instant instant(Line l, ZoneId fallback) {
        String v = l.value().trim();
        if (v.endsWith("Z")) return LocalDateTime.parse(v.substring(0, v.length() - 1), LOCAL).toInstant(ZoneOffset.UTC);
        LocalDateTime t = LocalDateTime.parse(v.length() > 15 ? v.substring(0, 15) : v, LOCAL);
        return t.atZone(zone(l.param("TZID"), fallback)).toInstant();
    }

    static ZoneId zone(String tzid, ZoneId fallback) {
        if (tzid == null || tzid.isBlank()) return fallback;
        String t = tzid.trim();
        String mapped = WINDOWS.get(t.toLowerCase(Locale.ROOT));
        try {
            return ZoneId.of(mapped != null ? mapped : t);
        } catch (DateTimeException e) {
            // "/mozilla.org/20050126_1/Asia/Kolkata" and the like: try the tail.
            String[] parts = t.split("/");
            if (parts.length >= 2) {
                try {
                    return ZoneId.of(parts[parts.length - 2] + "/" + parts[parts.length - 1]);
                } catch (DateTimeException ignored) {
                }
            }
            return fallback;
        }
    }

    static Duration duration(String v) {
        String s = v.toUpperCase(Locale.ROOT);
        Matcher w = Pattern.compile("^P(\\d+)W$").matcher(s);
        if (w.matches()) return Duration.ofDays(7L * Integer.parseInt(w.group(1)));
        try {
            return Duration.parse(s);
        } catch (Exception e) {
            return Duration.ofHours(1);
        }
    }

    private static Person person(Line l) {
        String v = l.value().trim();
        String email = v.toLowerCase(Locale.ROOT).startsWith("mailto:") ? v.substring(7).trim() : (v.contains("@") ? v : null);
        String status = l.param("PARTSTAT");
        return new Person(l.param("CN"), email == null ? null : email.toLowerCase(Locale.ROOT),
                status == null ? null : status.toUpperCase(Locale.ROOT));
    }

    private static String unquote(String s) {
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    /** TEXT values: \n, \, \; and \\ escapes. */
    static String text(String v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length()) {
                char n = v.charAt(++i);
                sb.append(n == 'n' || n == 'N' ? '\n' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n");
    }

    /** PARTSTAT for an answer: accepted, tentative or declined. */
    public static String partstat(String answer) {
        return switch (answer) {
            case "accepted" -> "ACCEPTED";
            case "tentative" -> "TENTATIVE";
            case "declined" -> "DECLINED";
            default -> throw new IllegalArgumentException(answer);
        };
    }

    /** The REPLY that tells the organizer's calendar how this attendee answered. */
    public static String reply(Event e, String myEmail, String myName, String answer, String comment, Instant now) {
        StringBuilder sb = new StringBuilder();
        line(sb, "BEGIN:VCALENDAR");
        line(sb, "PRODID:-//Magizhchi//MailLook//EN");
        line(sb, "VERSION:2.0");
        line(sb, "METHOD:REPLY");
        line(sb, "BEGIN:VEVENT");
        line(sb, "UID:" + e.uid());
        line(sb, "SEQUENCE:" + e.sequence());
        line(sb, "DTSTAMP:" + UTC.format(now));
        if (e.allDay()) {
            line(sb, "DTSTART;VALUE=DATE:" + DAY.format(e.startDate()));
            line(sb, "DTEND;VALUE=DATE:" + DAY.format(e.endDate()));
        } else {
            line(sb, "DTSTART:" + UTC.format(e.start()));
            line(sb, "DTEND:" + UTC.format(e.end()));
        }
        if (e.title() != null) line(sb, "SUMMARY:" + escape(e.title()));
        if (e.organizer() != null && e.organizer().email() != null) {
            line(sb, "ORGANIZER" + (e.organizer().name() == null ? "" : ";CN=" + cn(e.organizer().name())) + ":mailto:" + e.organizer().email());
        }
        line(sb, "ATTENDEE;PARTSTAT=" + partstat(answer) + (myName == null || myName.isBlank() ? "" : ";CN=" + cn(myName))
                + ":mailto:" + myEmail.toLowerCase(Locale.ROOT));
        if (comment != null && !comment.isBlank()) line(sb, "COMMENT:" + escape(comment.trim()));
        line(sb, "END:VEVENT");
        line(sb, "END:VCALENDAR");
        return sb.toString();
    }

    private static String cn(String name) {
        return "\"" + name.replace("\"", "'") + "\"";
    }

    /** Lines longer than 75 octets are folded, as the standard asks. */
    private static void line(StringBuilder sb, String s) {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (b.length <= 75) {
            sb.append(s).append("\r\n");
            return;
        }
        int count = 0;
        boolean first = true;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int len = new String(Character.toChars(cp)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (count + len > (first ? 75 : 74)) {
                sb.append(cur).append("\r\n ");
                cur.setLength(0);
                count = 0;
                first = false;
            }
            cur.appendCodePoint(cp);
            count += len;
            i += Character.charCount(cp);
        }
        sb.append(cur).append("\r\n");
    }
}
