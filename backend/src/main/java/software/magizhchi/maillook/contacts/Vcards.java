package software.magizhchi.maillook.contacts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Magizhchi Box's contact book, read the way the Box app reads it: every .vcf in Synced/Mobile/Contacts/
 * (phone backups and cards saved in Box), oldest first, merged into one book. Edits never rewrite old
 * files; a new card says which ones it replaces (X-MBOX-REPLACES) or deletes (X-MBOX-DELETED).
 */
public final class Vcards {

    public static final String FOLDER = "Synced/Mobile/Contacts/";

    public static final class Card {
        public String name;
        public final List<String> tels = new ArrayList<>();
        public final List<String> emails = new ArrayList<>();
        public String org;
        public String bday;
        public String address;
        public String note;
        public String photo;
        List<String> replaces = List.of();
        List<String> deletes = List.of();
        boolean authored;

        public String key() {
            return (name == null ? "" : name.trim().toLowerCase(Locale.ROOT)) + "|" + normTel(tels.isEmpty() ? "" : tels.get(0));
        }
    }

    private Vcards() {}

    static String normTel(String t) {
        String d = String.valueOf(t).replaceAll("\\D", "");
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }

    /** Key lists join with "||" (keys hold a "|"); old cards used one "|" and are read in pairs. */
    static List<String> keyList(String raw) {
        List<String> out = new ArrayList<>();
        if (raw.contains("||")) {
            for (String k : raw.split("\\|\\|")) if (!k.isEmpty()) out.add(k);
            return out;
        }
        String[] t = raw.split("\\|", -1);
        if (t.length % 2 != 0) return List.of(raw);
        for (int i = 0; i < t.length - 1; i += 2) out.add(t[i] + "|" + t[i + 1]);
        return out;
    }

    private static final Pattern LINE = Pattern.compile("^([^:;]+)(?:;[^:]*)?:(.*)$");

    private static String unesc(String s) {
        return s.replaceAll("(?i)\\\\n", "\n").replaceAll("\\\\([,;\\\\])", "$1");
    }

    public static List<Card> parse(String text) {
        List<Card> cards = new ArrayList<>();
        if (text == null) return cards;
        String unfolded = text.replaceAll("\\r?\\n[ \\t]", "");
        String[] chunks = unfolded.split("(?i)BEGIN:VCARD");
        for (int i = 1; i < chunks.length; i++) {
            Card c = new Card();
            for (String raw : chunks[i].split("\\r?\\n")) {
                Matcher m = LINE.matcher(raw.trim());
                if (!m.matches()) continue;
                String key = m.group(1).toUpperCase(Locale.ROOT);
                String rawVal = m.group(2).trim();
                if (rawVal.isEmpty()) continue;
                if (key.equals("PHOTO")) {
                    if (c.photo == null) c.photo = rawVal.startsWith("data:") && rawVal.contains(",") ? rawVal.substring(rawVal.indexOf(',') + 1) : rawVal;
                    continue;
                }
                String val = unesc(rawVal);
                switch (key) {
                    case "FN" -> { if (c.name == null) c.name = val; }
                    case "N" -> {
                        if (c.name == null) {
                            List<String> parts = new ArrayList<>();
                            for (String p : val.split(";")) if (!p.isEmpty()) parts.add(p);
                            c.name = String.join(" ", parts);
                        }
                    }
                    case "TEL" -> c.tels.add(val);
                    case "EMAIL" -> c.emails.add(val.trim());
                    case "ORG" -> { if (c.org == null) c.org = val.replaceAll(";+$", ""); }
                    case "BDAY" -> { if (c.bday == null) c.bday = val; }
                    case "ADR" -> {
                        if (c.address == null) {
                            List<String> parts = new ArrayList<>();
                            for (String p : val.split(";")) if (!p.isEmpty()) parts.add(p);
                            c.address = String.join(", ", parts);
                        }
                    }
                    case "NOTE" -> { if (c.note == null) c.note = val; }
                    case "X-MBOX-REPLACES" -> c.replaces = keyList(rawVal);
                    case "X-MBOX-DELETED" -> c.deletes = keyList(rawVal);
                    case "X-MBOX-AUTHORED" -> c.authored = true;
                    default -> { }
                }
            }
            if (c.name != null || !c.tels.isEmpty() || !c.deletes.isEmpty()) cards.add(c);
        }
        return cards;
    }

    /** Merges cards in time order into the book, exactly as Box does. */
    public static Map<String, Card> merge(List<Card> all) {
        Set<String> suppressed = new HashSet<>();
        for (Card c : all) {
            suppressed.addAll(c.replaces);
            suppressed.addAll(c.deletes);
        }
        Map<String, Card> book = new LinkedHashMap<>();
        for (Card c : all) {
            if (!c.deletes.isEmpty()) {
                for (String k : c.deletes) book.remove(k);
                continue;
            }
            String k = c.key();
            if (!c.authored && suppressed.contains(k)) continue;
            for (String rk : c.replaces) book.remove(rk);
            book.put(k, c);
        }
        return book;
    }

    /** A new card for Box, marked as written in an app (so Box's merge never hides it). */
    public static String build(Card c, List<String> replaces) {
        List<String> lines = new ArrayList<>();
        lines.add("BEGIN:VCARD");
        lines.add("VERSION:3.0");
        lines.add("FN:" + esc(c.name));
        for (String t : c.tels) lines.add("TEL:" + esc(t));
        for (String e : c.emails) lines.add("EMAIL:" + esc(e));
        if (c.org != null) lines.add("ORG:" + esc(c.org));
        if (c.bday != null) lines.add("BDAY:" + c.bday);
        if (c.address != null) lines.add("ADR:;;" + esc(c.address) + ";;;;");
        if (c.note != null) lines.add("NOTE:" + esc(c.note));
        if (c.photo != null) lines.add("PHOTO;ENCODING=b;TYPE=JPEG:" + c.photo);
        if (replaces != null && !replaces.isEmpty()) lines.add("X-MBOX-REPLACES:" + String.join("||", replaces));
        lines.add("X-MBOX-AUTHORED:1");
        lines.add("END:VCARD");
        return String.join("\r\n", lines) + "\r\n";
    }

    private static String esc(String s) {
        return String.valueOf(s).replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\n", "\\n");
    }
}
