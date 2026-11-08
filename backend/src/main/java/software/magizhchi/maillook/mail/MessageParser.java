package software.magizhchi.maillook.mail;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeUtility;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pulls text, HTML and the attachment list out of a MIME message. */
public final class MessageParser {

    private static final int MAX_TEXT = 100_000;
    private static final int MAX_HTML = 300_000;

    /** {@code calendar}: a calendar invite sent inside the mail (text/calendar), not as a file. */
    public record Parsed(String text, String html, List<Map<String, Object>> attachments, String calendar) {}

    private MessageParser() {}

    public static Parsed parse(Part message) throws MessagingException, IOException {
        Acc acc = new Acc();
        walk(message, acc, null);
        String text = stripMimeLeftovers(acc.text);
        String html = acc.html;
        // Some senders (bank alerts among them) put an HTML page in the "plain text" part, headers and all.
        if (text != null && looksLikeHtml(text)) {
            if (html == null) html = text;
            text = null;
        }
        if (text == null && html != null) text = htmlToText(stripMimeLeftovers(html));
        return new Parsed(cap(text, MAX_TEXT), cap(html, MAX_HTML), acc.attachments, cap(acc.calendar, 200_000));
    }

    /** Returns the n-th attachment part, walking in the same order as {@link #parse}. */
    public static Part attachment(Part message, int index) throws MessagingException, IOException {
        Acc acc = new Acc();
        acc.wanted = index;
        walk(message, acc, null);
        return acc.found;
    }

    private static final class Acc {
        String text;
        String html;
        List<Map<String, Object>> attachments = new ArrayList<>();
        String calendar;
        int wanted = -1;
        Part found;
    }

    private static void walk(Part p, Acc acc, String parentType) throws MessagingException, IOException {
        if (acc.found != null) return;
        if (p.isMimeType("multipart/*")) {
            Object content = p.getContent();
            if (content instanceof Multipart mp) {
                for (int i = 0; i < mp.getCount(); i++) walk(mp.getBodyPart(i), acc, p.getContentType());
            }
            return;
        }
        String filename = decode(p.getFileName());
        String disposition = p.getDisposition();
        boolean isAttachment = Part.ATTACHMENT.equalsIgnoreCase(disposition)
                || (filename != null && !p.isMimeType("text/plain") && !p.isMimeType("text/html"))
                || p.isMimeType("message/rfc822")
                || (filename != null && Part.ATTACHMENT.equalsIgnoreCase(disposition));
        if (isAttachment) {
            int idx = acc.attachments.size();
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("index", idx);
            a.put("name", filename != null ? filename : (p.isMimeType("message/rfc822") ? "message.eml" : "attachment-" + (idx + 1)));
            a.put("size", Math.max(0, p.getSize()));
            a.put("contentType", baseType(p.getContentType()));
            acc.attachments.add(a);
            if (acc.wanted == idx) acc.found = p;
            return;
        }
        if (p.isMimeType("text/calendar")) {
            if (acc.calendar == null) acc.calendar = asString(p);
            return;
        }
        if (p.isMimeType("text/plain") && acc.text == null) {
            acc.text = asString(p);
        } else if (p.isMimeType("text/html") && acc.html == null) {
            acc.html = asString(p);
        }
    }

    private static String asString(Part p) throws MessagingException, IOException {
        Object c;
        try {
            c = p.getContent();
        } catch (Exception e) {
            // Unknown charset etc.: read the raw bytes as UTF-8.
            try (InputStream in = p.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        if (c instanceof String s) return s;
        if (c instanceof InputStream in) {
            try (in) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static final Pattern SCRIPT_STYLE = Pattern.compile("(?is)<(script|style|head)[^>]*>.*?</\\1>");
    private static final Pattern BREAKS = Pattern.compile("(?i)<\\s*(br|/p|/div|/tr|/li|/h[1-6])\\s*/?>");
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");

    public static String htmlToText(String html) {
        if (html == null) return null;
        String t = SCRIPT_STYLE.matcher(html).replaceAll(" ");
        t = BREAKS.matcher(t).replaceAll("\n");
        t = TAGS.matcher(t).replaceAll(" ");
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&zwnj;", "");
        t = t.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n\\s*\\n+", "\n\n");
        return t.trim();
    }

    // MIME headers or boundaries left at the top of a body by a sender that built its mail by hand:
    // "Content-Type: text/html; charset=utf-8", a bare "text/html; charset=utf-8", "--=_boundary".
    private static final Pattern MIME_LINE = Pattern.compile(
            "(?i)^\\s*(content-[a-z-]+\\s*:.*|mime-version\\s*:.*|text/(html|plain)\\s*;?\\s*(charset\\s*=\\s*\"?[\\w-]+\"?)?\\s*|--[\\w=._+-]{8,}(--)?\\s*)$");
    private static final Pattern HTML_START = Pattern.compile("(?is)^\\s*(<!doctype|<html|<head|<body|<table|<div|<p[ >]|<meta|<style|<center|<span)");

    /** The body without MIME header lines or boundaries at its top. */
    public static String stripMimeLeftovers(String body) {
        if (body == null) return null;
        String[] lines = body.split("\\r?\\n", -1);
        int i = 0;
        while (i < lines.length && i < 12 && (lines[i].isBlank() || MIME_LINE.matcher(lines[i]).matches())) i++;
        return i == 0 ? body : String.join("\n", java.util.Arrays.copyOfRange(lines, i, lines.length));
    }

    static boolean looksLikeHtml(String text) {
        return HTML_START.matcher(text).find();
    }

    /** The preview line of a mail, from its text (or, failing that, its HTML). */
    public static String snippet(String text, String html) {
        String t = stripMimeLeftovers(text);
        if ((t == null || t.isBlank() || looksLikeHtml(t)) && html != null) t = htmlToText(stripMimeLeftovers(html));
        else if (t != null && looksLikeHtml(t)) t = htmlToText(t);
        return snippet(t);
    }

    public static String snippet(String text) {
        if (text == null) return "";
        text = stripMimeLeftovers(text);
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (l.startsWith(">") || MIME_LINE.matcher(l).matches()) continue;
            if (l.matches("(?i)^on .{5,200} wrote:$")) break;
            if (!l.isEmpty()) sb.append(l).append(' ');
            if (sb.length() > 240) break;
        }
        String s = sb.toString().replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /** Strips quoted replies so tone samples and AI prompts only see what the person wrote. */
    public static String ownText(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (l.matches("(?i)^on .{5,200} wrote:$") || l.startsWith("-----Original Message") || l.startsWith("---------- Forwarded")) break;
            if (l.startsWith(">")) continue;
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    public static List<String> addresses(Address[] addrs) {
        List<String> out = new ArrayList<>();
        if (addrs == null) return out;
        for (Address a : addrs) {
            if (a instanceof InternetAddress ia) {
                String personal = ia.getPersonal();
                out.add(personal != null && !personal.isBlank() ? personal + " <" + ia.getAddress() + ">" : ia.getAddress());
            } else {
                out.add(a.toString());
            }
        }
        return out;
    }

    private static final Pattern ANGLE = Pattern.compile("<([^>]+)>");

    /** Bare lower-case email from "Name &lt;a@b&gt;" or "a@b". */
    public static String emailOnly(String s) {
        if (s == null) return null;
        Matcher m = ANGLE.matcher(s);
        String e = m.find() ? m.group(1) : s;
        return e.trim().toLowerCase(Locale.ROOT);
    }

    public static String header(Message m, String name) throws MessagingException {
        String[] h = m.getHeader(name);
        if (h == null || h.length == 0) return null;
        return h[0];
    }

    private static String decode(String s) {
        if (s == null) return null;
        try {
            return MimeUtility.decodeText(s);
        } catch (Exception e) {
            return s;
        }
    }

    private static String baseType(String ct) {
        if (ct == null) return "application/octet-stream";
        int semi = ct.indexOf(';');
        return (semi > 0 ? ct.substring(0, semi) : ct).trim().toLowerCase(Locale.ROOT);
    }

    private static String cap(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
