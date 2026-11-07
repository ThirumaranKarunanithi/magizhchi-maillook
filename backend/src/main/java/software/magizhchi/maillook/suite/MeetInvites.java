package software.magizhchi.maillook.suite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The calendar invite (iCalendar, METHOD:REQUEST) for a Meet room booked from a thread, sent as part
 * of the person's own reply. The organiser is their mailbox, so people's "Accept" and any replies
 * come back into the same thread instead of to Meet's address.
 */
@Component
public class MeetInvites {

    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    public record Booking(String cardId, String name, Instant startsAt, Instant endsAt) {}

    private final JdbcTemplate jdbc;
    private final MeetClient meet;

    public MeetInvites(JdbcTemplate jdbc, MeetClient meet) {
        this.jdbc = jdbc;
        this.meet = meet;
    }

    /** The booked room with this id, if this mailbox booked it with a start and end time. */
    public Optional<Booking> booking(long accountId, String cardId) {
        if (cardId == null || cardId.isBlank()) return Optional.empty();
        return jdbc.query("SELECT card_id, card_name, starts_at, ends_at FROM meet_links WHERE account_id = ? AND card_id = ? " +
                        "AND starts_at IS NOT NULL AND ends_at IS NOT NULL ORDER BY created_at DESC",
                (rs, i) -> new Booking(rs.getString(1), rs.getString(2),
                        rs.getObject(3, OffsetDateTime.class).toInstant(), rs.getObject(4, OffsetDateTime.class).toInstant()),
                accountId, cardId.trim()).stream().findFirst();
    }

    /** The .ics text: one event, organised by {@code organizer}, with everyone the mail goes to as attendees. */
    public String ics(Booking b, String organizer, String organizerName, Collection<String> attendees) {
        String link = meet.roomLink(b.cardId());
        StringBuilder s = new StringBuilder();
        line(s, "BEGIN:VCALENDAR");
        line(s, "PRODID:-//Magizhchi//MailLook//EN");
        line(s, "VERSION:2.0");
        line(s, "CALSCALE:GREGORIAN");
        line(s, "METHOD:REQUEST");
        line(s, "BEGIN:VEVENT");
        line(s, "UID:" + b.cardId() + "@maillook.magizhchi.software");
        line(s, "DTSTAMP:" + UTC.format(Instant.now()));
        line(s, "DTSTART:" + UTC.format(b.startsAt()));
        line(s, "DTEND:" + UTC.format(b.endsAt()));
        line(s, "SUMMARY:" + text(b.name() == null ? "Magizhchi Meet" : b.name()));
        line(s, "LOCATION:" + text(link));
        line(s, "URL:" + link);
        line(s, "DESCRIPTION:" + text("Join on Magizhchi Meet: " + link));
        line(s, "ORGANIZER;CN=" + param(organizerName == null || organizerName.isBlank() ? organizer : organizerName) + ":mailto:" + organizer);
        for (String a : attendees) {
            if (a == null || a.isBlank() || a.equalsIgnoreCase(organizer)) continue;
            line(s, "ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:" + a.trim().toLowerCase(Locale.ROOT));
        }
        line(s, "SEQUENCE:0");
        line(s, "STATUS:CONFIRMED");
        line(s, "BEGIN:VALARM");
        line(s, "TRIGGER:-PT10M");
        line(s, "ACTION:DISPLAY");
        line(s, "DESCRIPTION:" + text(b.name() == null ? "Meeting" : b.name()));
        line(s, "END:VALARM");
        line(s, "END:VEVENT");
        line(s, "END:VCALENDAR");
        return s.toString();
    }

    /** Bare addresses out of "Name <a@b>" style recipients. */
    public static List<String> addresses(Collection<String> raw) {
        return raw.stream().filter(x -> x != null && x.contains("@"))
                .map(x -> x.replaceAll("^.*<([^>]+)>.*$", "$1").trim()).toList();
    }

    /** iCalendar lines end in CRLF and fold at 75 octets. */
    private static void line(StringBuilder s, String l) {
        byte[] b = l.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (b.length <= 75) {
            s.append(l).append("\r\n");
            return;
        }
        int count = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < l.length(); ) {
            int cp = l.codePointAt(i);
            int len = new String(Character.toChars(cp)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (count + len > 74) {
                s.append(cur).append("\r\n ");
                cur.setLength(0);
                count = 0;
            }
            cur.appendCodePoint(cp);
            count += len;
            i += Character.charCount(cp);
        }
        s.append(cur).append("\r\n");
    }

    private static String text(String v) {
        return v.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n");
    }

    private static String param(String v) {
        return "\"" + v.replace("\"", "'") + "\"";
    }
}
