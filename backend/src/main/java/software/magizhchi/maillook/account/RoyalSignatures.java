package software.magizhchi.maillook.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import software.magizhchi.maillook.common.ApiException;
import software.magizhchi.maillook.forms.FormMail;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Designed signatures: one of the royal templates in one of three layouts, filled in with their details
 * and, if they like, their photo and their company's logo. The browser draws the banner (crest or photo,
 * logo, name, designation and company, with its emboss, reflection and colour grade) at 3x size and sends
 * it here; mail shows it from {@code /api/sig/{token}}, because mail programs drop 3D, animation and fonts
 * but keep a picture. The contact lines, social icons and the booking button under it stay real links. A
 * plain-text copy of the same signature goes in the plain part of every mail.
 */
@Component
public class RoyalSignatures {

    /** The templates the designer offers, with the colours of their text lines. */
    record Look(String ink, String accent, String muted, String link) {}

    static final Map<String, Look> LOOKS = Map.of(
            "gold-crest", new Look("#2a2112", "#b8892b", "#6e6047", "#8a6414"),
            "peacock-royal", new Look("#0d2b33", "#1f8a8a", "#4d6b70", "#13707a"),
            "maroon-gold", new Look("#3a0d14", "#a8742a", "#6e4a4f", "#8c1d2c"),
            "midnight-sapphire", new Look("#111a33", "#3b5bb8", "#56607a", "#2a4aa3"),
            "temple-kolam", new Look("#3b1d0b", "#c0541b", "#73513c", "#a8420f"),
            "ivory-classic", new Look("#262420", "#9c8a63", "#6b665c", "#5a4a26"));

    static final List<String> LAYOUTS = List.of("person", "company", "card");

    /** Social networks: where a bare handle points, and the label for the icon. */
    record Social(String key, String label, String handleUrl) {}

    static final List<Social> SOCIALS = List.of(
            new Social("linkedin", "LinkedIn", "https://www.linkedin.com/in/%s"),
            new Social("instagram", "Instagram", "https://www.instagram.com/%s"),
            new Social("youtube", "YouTube", "https://www.youtube.com/@%s"),
            new Social("facebook", "Facebook", "https://www.facebook.com/%s"),
            new Social("x", "X", "https://x.com/%s"));

    static final int MAX_BYTES = 4 * 1024 * 1024;
    static final int MAX_ART_BYTES = 2 * 1024 * 1024;
    static final int KEEP_PICTURES = 20;
    private static final List<String> FIELDS = List.of("name", "title", "company", "phone", "whatsapp", "email", "website", "address",
            "tagline", "booking", "linkedin", "instagram", "youtube", "facebook", "x");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final AccountRepo accounts;
    private final ObjectMapper json;
    private final String publicUrl;

    public RoyalSignatures(JdbcTemplate jdbc, AccountRepo accounts, ObjectMapper json,
                           @Value("${maillook.public-url:http://localhost:8097}") String publicUrl) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.json = json;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    public record Picture(String token, int width, int height, String contentType, byte[] data) {}

    /**
     * Saves a design and its banner for one mailbox, and the matching plain-text signature. {@code photo}
     * and {@code logo} are new uploads (null keeps the ones the design names, if they are this mailbox's).
     */
    @Transactional
    public void save(long userId, long accountId, String designJson, byte[] banner, byte[] photo, byte[] logo) {
        owned(userId, accountId);
        ObjectNode design = clean(designJson);
        if (banner == null || banner.length == 0) throw ApiException.bad("The signature picture is missing.");
        if (banner.length > MAX_BYTES) throw ApiException.bad("The signature picture is too large.");
        design.put("image", store(accountId, "banner", banner, 300, 2400, 60, 1200, "signature picture"));
        art(accountId, design, "photo", photo);
        art(accountId, design, "logo", logo);
        accounts.setSignature(accountId, plain(design));
        jdbc.update("UPDATE accounts SET signature_design = ? WHERE id = ?", design.toString(), accountId);
        // Mail already sent still shows its banner; only very old banners are let go (never a photo or logo).
        List<String> old = jdbc.queryForList("SELECT token FROM signature_images WHERE account_id = ? AND kind = 'banner'"
                + " ORDER BY created_at DESC, token", String.class, accountId);
        for (int i = KEEP_PICTURES; i < old.size(); i++) jdbc.update("DELETE FROM signature_images WHERE token = ?", old.get(i));
    }

    /** A new photo or logo is stored; otherwise the design keeps the one it names, if it is still theirs. */
    private void art(long accountId, ObjectNode design, String kind, byte[] upload) {
        String label = "photo".equals(kind) ? "photo" : "logo";
        if (upload != null && upload.length > 0) {
            if (upload.length > MAX_ART_BYTES) throw ApiException.bad("The " + label + " is too large. Keep it under 2 MB.");
            design.put(kind, store(accountId, kind, upload, 16, 1600, 16, 1600, label));
            return;
        }
        String token = design.path(kind).asText("");
        Integer mine = token.matches("[0-9a-f]{32}") ? jdbc.queryForObject("SELECT COUNT(*) FROM signature_images WHERE token = ? AND account_id = ? AND kind = ?",
                Integer.class, token, accountId, kind) : Integer.valueOf(0);
        if (mine == null || mine == 0) design.remove(kind);
    }

    private String store(long accountId, String kind, byte[] data, int minW, int maxW, int minH, int maxH, String label) {
        boolean isPng = data.length > 8 && (data[0] & 0xff) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G';
        boolean isJpeg = data.length > 3 && (data[0] & 0xff) == 0xFF && (data[1] & 0xff) == 0xD8 && (data[2] & 0xff) == 0xFF;
        if (!isPng && !isJpeg) throw ApiException.bad("The " + label + " has to be a PNG or JPEG.");
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(data));
        } catch (Exception e) {
            img = null;
        }
        if (img == null) throw ApiException.bad("The " + label + " couldn't be read.");
        if (img.getWidth() < minW || img.getWidth() > maxW || img.getHeight() < minH || img.getHeight() > maxH) {
            throw ApiException.bad("The " + label + " is an odd size.");
        }
        String token = HexFormat.of().formatHex(bytes(16));
        jdbc.update("INSERT INTO signature_images (token, account_id, kind, width, height, content_type, data) VALUES (?, ?, ?, ?, ?, ?, ?)",
                token, accountId, kind, img.getWidth(), img.getHeight(), isPng ? "image/png" : "image/jpeg", data);
        return token;
    }

    /** Back to the plain-text signature (its text stays). */
    public void clear(long userId, long accountId) {
        owned(userId, accountId);
        clear(accountId);
    }

    void clear(long accountId) {
        jdbc.update("UPDATE accounts SET signature_design = NULL WHERE id = ?", accountId);
    }

    public Optional<Picture> picture(String token) {
        if (token == null || !token.matches("[0-9a-f]{32}")) return Optional.empty();
        return jdbc.query("SELECT token, width, height, content_type, data FROM signature_images WHERE token = ?",
                (rs, i) -> new Picture(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getString(4), rs.getBytes(5)), token).stream().findFirst();
    }

    /** What the Accounts page shows: the design and its picture's address, or nothing. */
    public Map<String, Object> view(long accountId) {
        JsonNode d = design(accountId);
        Map<String, Object> out = new LinkedHashMap<>();
        // Always all three keys (null when plain), so a page merging the reply drops an old design.
        out.put("signatureDesign", d);
        out.put("signatureImage", null);
        out.put("signatureHtml", null);
        if (d == null) return out;
        sizeOf(d.path("image").asText()).ifPresent(wh -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("url", imageUrl(d.path("image").asText()));
            p.put("width", wh[0] / 3);
            p.put("height", wh[1] / 3);
            out.put("signatureImage", p);
        });
        out.put("signatureHtml", signatureHtml(accountId));
        return out;
    }

    JsonNode design(long accountId) {
        List<String> s = jdbc.queryForList("SELECT signature_design FROM accounts WHERE id = ?", String.class, accountId);
        if (s.isEmpty() || s.get(0) == null) return null;
        try {
            return json.readTree(s.get(0));
        } catch (Exception e) {
            return null;
        }
    }

    private Optional<int[]> sizeOf(String token) {
        return jdbc.query("SELECT width, height FROM signature_images WHERE token = ?",
                (rs, i) -> new int[]{rs.getInt(1), rs.getInt(2)}, token).stream().findFirst();
    }

    String imageUrl(String token) {
        return publicUrl + "/api/sig/" + token;
    }

    String iconUrl(String icon, Look look) {
        return publicUrl + "/api/sig/icon/" + icon + "/" + look.accent().substring(1);
    }

    /** The signature as HTML for mail: the banner, then the contact lines, icons and button as real links. Null without a design. */
    public String signatureHtml(long accountId) {
        JsonNode d = design(accountId);
        if (d == null) return null;
        Look look = LOOKS.getOrDefault(d.path("template").asText(), LOOKS.get("ivory-classic"));
        String token = d.path("image").asText();
        int[] wh = sizeOf(token).orElse(null);
        StringBuilder h = new StringBuilder();
        h.append("<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" role=\"presentation\" style=\"border-collapse:collapse;font-family:Georgia,'Times New Roman',serif\">");
        if (wh != null) {
            int w = wh[0] / 3, ht = wh[1] / 3;
            String alt = String.join(" · ", nonBlank(d, "name", "title", "company"));
            h.append("<tr><td style=\"padding:0 0 6px 0\"><img src=\"").append(esc(imageUrl(token))).append("\" width=\"").append(w)
                    .append("\" height=\"").append(ht).append("\" alt=\"").append(esc(alt))
                    .append("\" style=\"display:block;border:0;outline:none;width:").append(w).append("px;max-width:100%;height:auto\"></td></tr>");
        }
        StringBuilder lines = new StringBuilder();
        String tagline = text(d, "tagline");
        if (!tagline.isEmpty()) lines.append("<div><i style=\"color:").append(look.muted()).append("\">").append(esc(tagline)).append("</i></div>");
        StringBuilder reach = new StringBuilder();
        String sep = " <span style=\"color:" + look.accent() + "\">|</span> ";
        String phone = text(d, "phone");
        String email = text(d, "email");
        String web = text(d, "website");
        if (!phone.isEmpty()) reach.append(link("tel:" + phone.replaceAll("[^0-9+]", ""), phone, look));
        if (!email.isEmpty()) reach.append(reach.length() > 0 ? sep : "").append(link("mailto:" + email, email, look));
        if (!web.isEmpty()) reach.append(reach.length() > 0 ? sep : "").append(link(webUrl(web), web.replaceFirst("(?i)^https?://", ""), look));
        if (reach.length() > 0) lines.append("<div>").append(reach).append("</div>");
        String address = text(d, "address");
        if (!address.isEmpty()) lines.append("<div style=\"color:").append(look.muted()).append("\">").append(esc(address)).append("</div>");

        // Icons: WhatsApp and the social networks, each a small picture that links out.
        StringBuilder icons = new StringBuilder();
        String wa = text(d, "whatsapp");
        if (!wa.isEmpty()) icons.append(icon("https://wa.me/" + wa.replaceAll("[^0-9]", ""), "whatsapp", "WhatsApp", look));
        for (Social s : SOCIALS) {
            String v = text(d, s.key());
            if (!v.isEmpty()) icons.append(icon(v, s.key(), s.label(), look));
        }
        if (icons.length() > 0) lines.append("<div style=\"padding-top:6px;line-height:0\">").append(icons).append("</div>");

        String booking = text(d, "booking");
        if (!booking.isEmpty()) {
            lines.append("<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" role=\"presentation\" style=\"margin-top:10px;border-collapse:separate\"><tr>")
                    .append("<td style=\"background:").append(look.accent()).append(";border-radius:6px\">")
                    .append("<a href=\"").append(esc(booking)).append("\" style=\"display:inline-block;padding:7px 14px;font-family:Arial,Helvetica,sans-serif;")
                    .append("font-size:13px;font-weight:bold;line-height:1.2;color:#ffffff;text-decoration:none;border-radius:6px\">")
                    .append("<img src=\"").append(esc(publicUrl + "/api/sig/icon/calendar/ffffff")).append("\" width=\"14\" height=\"14\" alt=\"\" ")
                    .append("style=\"display:inline-block;border:0;vertical-align:-2px;margin-right:6px\">Book a meeting</a></td></tr></table>");
        }
        if (lines.length() > 0) {
            h.append("<tr><td style=\"border-top:2px solid ").append(look.accent()).append(";padding:8px 0 0 0;font-size:13px;line-height:1.65;color:")
                    .append(look.ink()).append("\">").append(lines).append("</td></tr>");
        }
        h.append("</table>");
        return h.toString();
    }

    private String icon(String href, String icon, String label, Look look) {
        return "<a href=\"" + esc(href) + "\" style=\"display:inline-block;margin-right:6px;text-decoration:none\"><img src=\""
                + esc(iconUrl(icon, look)) + "\" width=\"22\" height=\"22\" alt=\"" + esc(label) + "\" title=\"" + esc(label)
                + "\" style=\"display:block;border:0\"></a>";
    }

    /**
     * The HTML part for a mail whose text carries this mailbox's signature unchanged: the text as HTML with
     * the designed signature in place of the plain one. Null (plain text only) without a design, or when
     * they edited or removed the signature in this mail.
     */
    public String mailHtml(long accountId, String text) {
        if (text == null) return null;
        String sig = accounts.signature(accountId);
        if (sig == null || sig.isBlank()) return null;
        String block = "-- \n" + sig;
        String t = text.replace("\r\n", "\n");
        int at = t.indexOf(block);
        if (at < 0) return null;
        String html = signatureHtml(accountId);
        if (html == null) return null;
        String before = t.substring(0, at).replaceAll("\n+$", "");
        String after = t.substring(at + block.length()).replaceAll("^\n+", "");
        StringBuilder out = new StringBuilder("<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:15px;line-height:1.5;color:#1f2d33\">");
        if (!before.isEmpty()) out.append(FormMail.textToHtml(before)).append("<br><br>\n");
        out.append(html);
        if (!after.isEmpty()) out.append("<br>\n").append(FormMail.textToHtml(after));
        return out.append("</div>").toString();
    }

    /** The plain-text copy: name, designation and company, how to reach them, address, tagline, links. */
    static String plain(JsonNode d) {
        StringBuilder s = new StringBuilder();
        String name = text(d, "name");
        String role = String.join(", ", nonBlank(d, "title", "company"));
        if (!name.isEmpty()) s.append(name).append('\n');
        if (!role.isEmpty()) s.append(role).append('\n');
        String reach = String.join(" · ", nonBlank(d, "phone", "email", "website"));
        if (!reach.isEmpty()) s.append(reach).append('\n');
        if (!text(d, "whatsapp").isEmpty()) s.append("WhatsApp ").append(text(d, "whatsapp")).append('\n');
        if (!text(d, "address").isEmpty()) s.append(text(d, "address")).append('\n');
        if (!text(d, "tagline").isEmpty()) s.append(text(d, "tagline")).append('\n');
        List<String> socials = SOCIALS.stream().map(x -> text(d, x.key())).filter(v -> !v.isEmpty()).toList();
        if (!socials.isEmpty()) s.append(String.join(" · ", socials)).append('\n');
        if (!text(d, "booking").isEmpty()) s.append("Book a meeting: ").append(text(d, "booking")).append('\n');
        return s.toString().strip();
    }

    private ObjectNode clean(String designJson) {
        JsonNode in;
        try {
            in = designJson == null ? null : json.readTree(designJson);
        } catch (Exception e) {
            in = null;
        }
        if (in == null || !in.isObject()) throw ApiException.bad("The signature design is missing.");
        String template = in.path("template").asText("");
        if (!LOOKS.containsKey(template)) throw ApiException.bad("Pick one of the signature designs.");
        String layout = in.path("layout").asText("person");
        if (!LAYOUTS.contains(layout)) throw ApiException.bad("Pick one of the signature layouts.");
        ObjectNode out = json.createObjectNode();
        out.put("template", template);
        out.put("layout", layout);
        for (String f : FIELDS) {
            String v = in.path(f).asText("").replaceAll("[\\r\\n\\t]+", " ").strip();
            if (v.length() > 200 || (v.length() > 120 && !isLink(f))) throw ApiException.bad("Keep each line of the signature under 120 characters.");
            if (!v.isEmpty()) out.put(f, v);
        }
        // The photo and logo the design already uses (checked against this mailbox when saving), and how the photo sits.
        for (String k : List.of("photo", "logo")) {
            String t = in.path(k).asText("");
            if (t.matches("[0-9a-f]{32}")) out.put(k, t);
        }
        JsonNode crop = in.path("photoCrop");
        if (crop.isObject()) {
            ObjectNode c = out.putObject("photoCrop");
            c.put("x", clamp(crop.path("x").asDouble(0), -1, 1));
            c.put("y", clamp(crop.path("y").asDouble(0), -1, 1));
            c.put("zoom", clamp(crop.path("zoom").asDouble(1), 1, 4));
        }
        if (text(out, "name").isEmpty()) throw ApiException.bad("Add your name to the signature.");
        String email = text(out, "email");
        if (!email.isEmpty() && !email.matches("[^\\s@<>\"]+@[^\\s@<>\"]+\\.[^\\s@<>\"]+")) throw ApiException.bad("That email address doesn't look right.");
        String web = text(out, "website");
        if (!web.isEmpty() && !web.matches("(?i)(https?://)?[a-z0-9.-]+\\.[a-z]{2,}(/[^\\s\"<>]*)?")) throw ApiException.bad("That website doesn't look right.");
        for (String p : List.of("phone", "whatsapp")) {
            String v = text(out, p);
            if (!v.isEmpty() && !v.matches("[0-9+()\\-. ]{5,30}")) {
                throw ApiException.bad("phone".equals(p) ? "That phone number doesn't look right." : "That WhatsApp number doesn't look right.");
            }
        }
        String booking = text(out, "booking");
        if (!booking.isEmpty()) {
            if (!booking.matches("(?i)https?://.*")) booking = "https://" + booking;
            if (!booking.matches("(?i)https?://[a-z0-9.-]+\\.[a-z]{2,}(:\\d+)?(/[^\\s\"<>]*)?")) throw ApiException.bad("That booking link doesn't look right.");
            out.put("booking", booking);
        }
        // A social link can be the page's address or just the handle.
        for (Social s : SOCIALS) {
            String v = text(out, s.key());
            if (v.isEmpty()) continue;
            String url;
            if (v.matches("@?[A-Za-z0-9._-]{1,60}")) url = String.format(s.handleUrl(), v.replaceFirst("^@", ""));
            else url = v.matches("(?i)https?://.*") ? v : "https://" + v;
            if (!url.matches("(?i)https?://[a-z0-9.-]+\\.[a-z]{2,}(/[^\\s\"<>]*)?")) throw ApiException.bad("That " + s.label() + " link doesn't look right.");
            out.put(s.key(), url);
        }
        return out;
    }

    private static boolean isLink(String field) {
        return "booking".equals(field) || "website".equals(field) || SOCIALS.stream().anyMatch(s -> s.key().equals(field));
    }

    private static double clamp(double v, double lo, double hi) {
        return Double.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
    }

    private static String webUrl(String web) {
        return web.matches("(?i)https?://.*") ? web : "https://" + web;
    }

    private void owned(long userId, long accountId) {
        accounts.find(accountId).filter(a -> a.userId() == userId).orElseThrow(() -> ApiException.notFound("Account"));
    }

    private static String link(String href, String label, Look look) {
        return "<a href=\"" + esc(href) + "\" style=\"color:" + look.link() + ";text-decoration:none\">" + esc(label) + "</a>";
    }

    private static List<String> nonBlank(JsonNode d, String... fields) {
        return java.util.Arrays.stream(fields).map(f -> text(d, f)).filter(s -> !s.isEmpty()).toList();
    }

    private static String text(JsonNode d, String field) {
        return d.path(field).asText("").strip();
    }

    private static String esc(String s) {
        return FormMail.esc(s);
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }
}
