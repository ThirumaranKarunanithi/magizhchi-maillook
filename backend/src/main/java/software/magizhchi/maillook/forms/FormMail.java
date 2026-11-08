package software.magizhchi.maillook.forms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import software.magizhchi.maillook.common.ApiException;
import software.magizhchi.maillook.mail.MailActions;
import software.magizhchi.maillook.mail.MessageParser;
import software.magizhchi.maillook.mail.MessageRepo;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Magizhchi Forms form or poll put in a mail. Everyone the mail goes to gets their own copy, with
 * their own signed link, so Forms can tell who filled it in ("12 of 40 filled") and a reminder goes
 * only to the rest. The mail gets an HTML card (a fill button, or one button per answer) next to the
 * plain text, which keeps the links for mail apps that show text only.
 */
@Service
public class FormMail implements MailActions.FormSender {

    public static final int MAX_PEOPLE = 100;
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"]+");

    private final MailActions actions;
    private final FormsClient forms;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public FormMail(MailActions actions, FormsClient forms, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.actions = actions;
        this.forms = forms;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @PostConstruct
    void register() {
        actions.formSender(this);
    }

    /** What the Composer sends as {@code form}. */
    public record Card(String formId, String kind, String title, String url, String emailKey, String slug,
                       String question, List<String> choices) {
        static Card of(JsonNode n) {
            List<String> choices = new ArrayList<>();
            n.path("choices").forEach(c -> choices.add(c.asText()));
            String kind = "poll".equals(n.path("kind").asText()) ? "poll" : "form";
            return new Card(n.path("formId").asText(), kind, n.path("title").asText("Form"), n.path("url").asText(null),
                    blank(n.path("emailKey").asText(null)), blank(n.path("slug").asText(null)), n.path("question").asText(null), choices);
        }

        private static String blank(String s) {
            return s == null || s.isBlank() ? null : s;
        }
    }

    @Override
    public MessageRepo.Row send(long userId, MailActions.Outgoing o, List<MailActions.OutFile> files) {
        if (!forms.configured()) throw ApiException.bad("Magizhchi Forms isn't connected, so the form can't go with this mail.");
        Card card = Card.of(o.form());
        if ("poll".equals(card.kind()) && (card.slug() == null || card.choices().size() < 2))
            throw ApiException.bad("That poll is missing its answers.");
        if ("form".equals(card.kind()) && (card.url() == null || !card.url().startsWith("https://") && !card.url().startsWith("http://localhost")))
            throw ApiException.bad("That form has no link.");
        Map<String, String> people = people(o);
        if (people.isEmpty()) throw ApiException.bad("Add at least one recipient.");
        if (people.size() > MAX_PEOPLE) throw ApiException.bad("A form can go to " + MAX_PEOPLE + " people at a time.");

        MessageRepo.Row first = null;
        List<Long> ids = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Map.Entry<String, String> p : people.entrySet()) {
            try {
                MessageRepo.Row row = sendOne(userId, o, files, card, p.getKey(), p.getValue(), o.subject(), o.text());
                if (row != null) {
                    ids.add(row.id());
                    if (first == null) first = row;
                }
            } catch (ApiException e) {
                if (first == null && e.status() == HttpStatus.UNAUTHORIZED) throw e;
                failed.add(p.getKey());
            }
        }
        if (ids.isEmpty()) throw new ApiException(HttpStatus.BAD_GATEWAY, "Couldn't send the form to anyone. Check the addresses and try again.");
        record(userId, o, card, new ArrayList<>(people.keySet()), ids);
        if (!failed.isEmpty() && first != null) {
            // Sent to the rest; the person sees who it didn't reach on the Forms page.
            jdbc.update("UPDATE form_sends SET subject = ? WHERE id = (SELECT MAX(id) FROM form_sends WHERE user_id = ?)",
                    clip((o.subject() == null ? "" : o.subject()) + " (not sent to " + String.join(", ", failed) + ")", 480), userId);
        }
        return first;
    }

    private MessageRepo.Row sendOne(long userId, MailActions.Outgoing o, List<MailActions.OutFile> files, Card card,
                                    String email, String address, String subject, String text) {
        String token = forms.personToken(card.formId(), email);
        StringBuilder plain = new StringBuilder(text == null ? "" : text.stripTrailing());
        StringBuilder box = new StringBuilder();
        if ("poll".equals(card.kind())) {
            String q = card.question() == null ? card.title() : card.question();
            plain.append("\n\n").append(q).append('\n');
            box.append(cardTop(q, "Tap your answer"));
            for (int i = 0; i < card.choices().size(); i++) {
                String link = pollLink(card, token, i);
                plain.append("  ").append(card.choices().get(i)).append(": ").append(link).append('\n');
                box.append(button(card.choices().get(i), link, i == 0));
            }
        } else {
            String link = formLink(card, email, token);
            plain.append("\n\n").append(card.title()).append("\nFill it in: ").append(link).append('\n');
            box.append(cardTop(card.title(), "Magizhchi Forms")).append(button("Fill in the form", link, true));
        }
        box.append("</td></tr></table>");
        String html = "<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:15px;line-height:1.5;color:#1f2d33\">"
                + textToHtml(text == null ? "" : text) + box + "</div>";
        MailActions.Outgoing one = new MailActions.Outgoing(o.accountId(), List.of(address), List.of(), List.of(),
                subject, plain.toString(), o.replyToMessageId(), o.inviteCardId(), null);
        return actions.deliver(userId, one, files, html);
    }

    String formLink(Card card, String email, String token) {
        StringBuilder u = new StringBuilder(card.url());
        char sep = card.url().contains("?") ? '&' : '?';
        if (card.emailKey() != null && card.emailKey().matches("[a-z][a-z0-9_]*")) {
            u.append(sep).append(card.emailKey()).append('=').append(enc(email));
            sep = '&';
        }
        u.append(sep).append("ml=").append(enc(token)).append("&src=maillook");
        return u.toString();
    }

    String pollLink(Card card, String token, int choice) {
        return forms.api() + "/api/public/maillook/poll/" + enc(card.slug()) + "?t=" + enc(token) + "&c=" + choice;
    }

    /** Everyone the mail goes to, once each: bare address → the address as written. */
    static Map<String, String> people(MailActions.Outgoing o) {
        Map<String, String> out = new LinkedHashMap<>();
        for (List<String> list : Arrays.asList(o.to(), o.cc(), o.bcc())) {
            if (list == null) continue;
            for (String a : list) {
                String e = MessageParser.emailOnly(a);
                if (e != null && e.contains("@")) out.putIfAbsent(e, a);
            }
        }
        return out;
    }

    private void record(long userId, MailActions.Outgoing o, Card card, List<String> people, List<Long> ids) {
        try {
            String recipients = mapper.writeValueAsString(people);
            String messageIds = mapper.writeValueAsString(ids);
            GeneratedKeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("INSERT INTO form_sends (user_id, account_id, form_id, kind, title, url, email_key, "
                        + "subject, recipients, message_ids, sent_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", new String[]{"id"});
                ps.setLong(1, userId);
                ps.setLong(2, o.accountId());
                ps.setString(3, clip(card.formId(), 60));
                ps.setString(4, card.kind());
                ps.setString(5, clip("poll".equals(card.kind()) && card.question() != null ? card.question() : card.title(), 300));
                // A poll keeps its slug here: its buttons lead to Forms' poll page, not a fill link.
                String where = "poll".equals(card.kind()) ? card.slug() : card.url();
                ps.setString(6, where == null ? null : clip(where, 600));
                ps.setString(7, card.emailKey());
                ps.setString(8, o.subject() == null ? null : clip(o.subject(), 500));
                ps.setString(9, recipients);
                ps.setString(10, messageIds);
                ps.setTimestamp(11, Timestamp.from(Instant.now()));
                return ps;
            }, keys);
        } catch (Exception e) {
            // the mail went; only the tracking is missing
        }
    }

    // ── tracking ─────────────────────────────────────────────────

    public record Sent(long id, long accountId, String formId, String kind, String title, String url, String emailKey,
                       String subject, List<String> recipients, List<Long> messageIds, int filled, Instant sentAt, Instant remindedAt) {}

    public List<Sent> sent(long userId, int limit) {
        return jdbc.query("SELECT * FROM form_sends WHERE user_id = ? ORDER BY sent_at DESC, id DESC LIMIT ?", (rs, n) -> map(rs), userId, limit);
    }

    public Optional<Sent> find(long userId, long id) {
        return jdbc.query("SELECT * FROM form_sends WHERE user_id = ? AND id = ?", (rs, n) -> map(rs), userId, id).stream().findFirst();
    }

    /** The form or poll a Sent copy carried. */
    public Optional<Sent> forMessage(long userId, long messageId) {
        String like = "%" + messageId + "%";
        return jdbc.query("SELECT * FROM form_sends WHERE user_id = ? AND message_ids LIKE ? ORDER BY id DESC LIMIT 5",
                (rs, n) -> map(rs), userId, like).stream().filter(s -> s.messageIds().contains(messageId)).findFirst();
    }

    private Sent map(java.sql.ResultSet rs) throws java.sql.SQLException {
        List<String> people = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        try {
            mapper.readTree(rs.getString("recipients")).forEach(x -> people.add(x.asText()));
            String m = rs.getString("message_ids");
            if (m != null) mapper.readTree(m).forEach(x -> ids.add(x.asLong()));
        } catch (Exception ignored) {
            // shown with what could be read
        }
        Timestamp r = rs.getTimestamp("reminded_at");
        return new Sent(rs.getLong("id"), rs.getLong("account_id"), rs.getString("form_id"), rs.getString("kind"), rs.getString("title"),
                rs.getString("url"), rs.getString("email_key"), rs.getString("subject"), people, ids, rs.getInt("filled"),
                rs.getTimestamp("sent_at").toInstant(), r == null ? null : r.toInstant());
    }

    /** Who of the people it went to has filled it: Forms' answer, and the count kept for the list. */
    public Map<String, JsonNode> filled(String ownerEmail, Sent s) {
        FormsClient.Reply r = forms.post("/filled", Map.of("email", ownerEmail, "formId", s.formId(), "emails", s.recipients()));
        if (!r.ok()) throw new ApiException(r.code() == 404 ? HttpStatus.NOT_FOUND : HttpStatus.BAD_GATEWAY,
                r.code() == 404 ? "That form is gone from Magizhchi Forms, or you no longer have it." : "Magizhchi Forms didn't answer. Try again in a minute.");
        Map<String, JsonNode> out = new LinkedHashMap<>();
        r.json().path("filled").forEach(f -> out.put(f.path("email").asText(), f));
        if (out.size() != s.filled()) jdbc.update("UPDATE form_sends SET filled = ? WHERE id = ?", out.size(), s.id());
        return out;
    }

    /** A short reminder, each with their own link again, to those who haven't filled it. */
    public int remind(long userId, String ownerEmail, Sent s) {
        if (s.remindedAt() != null && s.remindedAt().isAfter(Instant.now().minusSeconds(3600)))
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "A reminder went less than an hour ago. Give people a little time.");
        Map<String, JsonNode> done = filled(ownerEmail, s);
        List<String> left = s.recipients().stream().filter(e -> !done.containsKey(e)).toList();
        if (left.isEmpty()) return 0;
        Card card;
        if ("poll".equals(s.kind())) {
            FormsClient.Reply t = forms.get("/poll-tally", Map.of("email", ownerEmail, "formId", s.formId()));
            if (!t.ok()) throw new ApiException(HttpStatus.BAD_GATEWAY, "Magizhchi Forms didn't answer. Try again in a minute.");
            List<String> choices = new ArrayList<>();
            t.json().path("choices").forEach(c -> choices.add(c.path("choice").asText()));
            if (s.url() == null) throw ApiException.bad("That poll can't be found to remind people.");
            card = new Card(s.formId(), "poll", s.title(), null, null, s.url(), s.title(), choices);
        } else {
            card = new Card(s.formId(), "form", s.title(), s.url(), s.emailKey(), null, null, List.of());
        }
        String subject = "Reminder: " + (s.subject() == null || s.subject().isBlank() ? s.title() : s.subject().replaceAll(" \\(not sent to .*\\)$", ""));
        String text = "poll".equals(s.kind()) ? "A quick reminder: your answer is still wanted. One tap is enough."
                : "A quick reminder to fill in “" + s.title() + "”. It only takes a minute.";
        MailActions.Outgoing o = new MailActions.Outgoing(s.accountId(), List.of(), List.of(), List.of(), subject, text, null, null, null);
        int sent = 0;
        for (String e : left) {
            try {
                sendOne(userId, o, List.of(), card, e, e, subject, text);
                sent++;
            } catch (ApiException ignored) {
                // the count says how many went
            }
        }
        jdbc.update("UPDATE form_sends SET reminded_at = ? WHERE id = ?", Timestamp.from(Instant.now()), s.id());
        return sent;
    }

    // ── HTML ─────────────────────────────────────────────────────

    private static String cardTop(String title, String note) {
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:20px 0;max-width:460px;width:100%;"
                + "border:1px solid #d6e2e6;border-radius:12px;border-collapse:separate\"><tr><td style=\"padding:18px 20px\">"
                + "<div style=\"font-size:12px;color:#5b6b73;margin-bottom:4px\">" + esc(note) + "</div>"
                + "<div style=\"font-size:18px;font-weight:bold;color:#0f2a35;margin-bottom:14px\">" + esc(title) + "</div>";
    }

    private static String button(String label, String href, boolean primary) {
        return "<a href=\"" + esc(href) + "\" style=\"display:block;text-decoration:none;text-align:center;border-radius:10px;padding:12px 16px;"
                + "margin:0 0 8px;font-weight:bold;font-size:15px;" + (primary ? "background:#0b6e73;color:#ffffff;border:1px solid #0b6e73"
                : "background:#ffffff;color:#0b6e73;border:1px solid #0b6e73") + "\">" + esc(label) + "</a>";
    }

    static String textToHtml(String text) {
        StringBuilder out = new StringBuilder();
        Matcher m = URL.matcher(text);
        int at = 0;
        while (m.find()) {
            out.append(esc(text.substring(at, m.start())));
            out.append("<a href=\"").append(esc(m.group())).append("\">").append(esc(m.group())).append("</a>");
            at = m.end();
        }
        out.append(esc(text.substring(at)));
        return out.toString().replace("\r\n", "\n").replace("\n", "<br>\n");
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
