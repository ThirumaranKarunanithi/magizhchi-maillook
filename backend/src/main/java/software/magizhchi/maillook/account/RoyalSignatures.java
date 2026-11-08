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
 * Designed signatures: one of the royal templates, filled in with their details. The browser draws the
 * banner (crest, name and title, with its emboss, reflection and colour grade) at 3x size and sends it
 * here as a PNG; mail shows it from {@code /api/sig/{token}}, because mail programs drop 3D, animation
 * and fonts but keep a picture. The contact lines under it stay real text, so they can be tapped and
 * copied. A plain-text copy of the same signature goes in the plain part of every mail.
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

    static final int MAX_BYTES = 4 * 1024 * 1024;
    static final int KEEP_PICTURES = 20;
    private static final List<String> FIELDS = List.of("name", "title", "company", "phone", "email", "website", "address", "tagline");
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

    /** Saves a design and its banner for one mailbox, and the matching plain-text signature. */
    @Transactional
    public void save(long userId, long accountId, String designJson, byte[] png) {
        // png: the banner, as PNG or JPEG (JPEG keeps a 3x banner with film grain small enough for mail).
        owned(userId, accountId);
        ObjectNode design = clean(designJson);
        if (png == null || png.length == 0) throw ApiException.bad("The signature picture is missing.");
        if (png.length > MAX_BYTES) throw ApiException.bad("The signature picture is too large.");
        boolean isPng = png.length > 8 && (png[0] & 0xff) == 0x89 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G';
        boolean isJpeg = png.length > 3 && (png[0] & 0xff) == 0xFF && (png[1] & 0xff) == 0xD8 && (png[2] & 0xff) == 0xFF;
        if (!isPng && !isJpeg) throw ApiException.bad("The signature picture has to be a PNG or JPEG.");
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(png));
        } catch (Exception e) {
            img = null;
        }
        if (img == null) throw ApiException.bad("The signature picture couldn't be read.");
        if (img.getWidth() < 300 || img.getWidth() > 2400 || img.getHeight() < 60 || img.getHeight() > 1200) {
            throw ApiException.bad("The signature picture is an odd size.");
        }
        String token = HexFormat.of().formatHex(bytes(16));
        jdbc.update("INSERT INTO signature_images (token, account_id, width, height, content_type, data) VALUES (?, ?, ?, ?, ?, ?)",
                token, accountId, img.getWidth(), img.getHeight(), isPng ? "image/png" : "image/jpeg", png);
        design.put("image", token);
        accounts.setSignature(accountId, plain(design));
        jdbc.update("UPDATE accounts SET signature_design = ? WHERE id = ?", design.toString(), accountId);
        // Mail already sent still shows its picture; only very old ones are let go.
        List<String> old = jdbc.queryForList("SELECT token FROM signature_images WHERE account_id = ? ORDER BY created_at DESC, token",
                String.class, accountId);
        for (int i = KEEP_PICTURES; i < old.size(); i++) jdbc.update("DELETE FROM signature_images WHERE token = ?", old.get(i));
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

    /** The signature as HTML for mail: the banner, then the contact lines as real text. Null without a design. */
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
            String alt = String.join(" · ", nonBlank(d, "name", "title"));
            h.append("<tr><td style=\"padding:0 0 6px 0\"><img src=\"").append(esc(imageUrl(token))).append("\" width=\"").append(w)
                    .append("\" height=\"").append(ht).append("\" alt=\"").append(esc(alt))
                    .append("\" style=\"display:block;border:0;outline:none;width:").append(w).append("px;max-width:100%;height:auto\"></td></tr>");
        }
        StringBuilder lines = new StringBuilder();
        String company = text(d, "company");
        String tagline = text(d, "tagline");
        if (!company.isEmpty() || !tagline.isEmpty()) {
            lines.append("<div>");
            if (!company.isEmpty()) lines.append("<b style=\"color:").append(look.ink()).append(";letter-spacing:.04em\">").append(esc(company)).append("</b>");
            if (!company.isEmpty() && !tagline.isEmpty()) lines.append(" <span style=\"color:").append(look.accent()).append("\">&#9670;</span> ");
            if (!tagline.isEmpty()) lines.append("<i style=\"color:").append(look.muted()).append("\">").append(esc(tagline)).append("</i>");
            lines.append("</div>");
        }
        StringBuilder reach = new StringBuilder();
        String phone = text(d, "phone");
        String email = text(d, "email");
        String web = text(d, "website");
        String sep = " <span style=\"color:" + look.accent() + "\">|</span> ";
        if (!phone.isEmpty()) reach.append(link("tel:" + phone.replaceAll("[^0-9+]", ""), phone, look));
        if (!email.isEmpty()) reach.append(reach.length() > 0 ? sep : "").append(link("mailto:" + email, email, look));
        if (!web.isEmpty()) {
            String href = web.matches("(?i)https?://.*") ? web : "https://" + web;
            reach.append(reach.length() > 0 ? sep : "").append(link(href, web.replaceFirst("(?i)^https?://", ""), look));
        }
        if (reach.length() > 0) lines.append("<div>").append(reach).append("</div>");
        String address = text(d, "address");
        if (!address.isEmpty()) lines.append("<div style=\"color:").append(look.muted()).append("\">").append(esc(address)).append("</div>");
        if (lines.length() > 0) {
            h.append("<tr><td style=\"border-top:2px solid ").append(look.accent()).append(";padding:8px 0 0 0;font-size:13px;line-height:1.65;color:")
                    .append(look.ink()).append("\">").append(lines).append("</td></tr>");
        }
        h.append("</table>");
        return h.toString();
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

    /** The plain-text copy: name, title and company, how to reach them, address, tagline. */
    static String plain(JsonNode d) {
        StringBuilder s = new StringBuilder();
        String name = text(d, "name");
        String role = String.join(", ", nonBlank(d, "title", "company"));
        if (!name.isEmpty()) s.append(name).append('\n');
        if (!role.isEmpty()) s.append(role).append('\n');
        String reach = String.join(" · ", nonBlank(d, "phone", "email", "website"));
        if (!reach.isEmpty()) s.append(reach).append('\n');
        if (!text(d, "address").isEmpty()) s.append(text(d, "address")).append('\n');
        if (!text(d, "tagline").isEmpty()) s.append(text(d, "tagline")).append('\n');
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
        ObjectNode out = json.createObjectNode();
        out.put("template", template);
        for (String f : FIELDS) {
            String v = in.path(f).asText("").replaceAll("[\\r\\n\\t]+", " ").strip();
            if (v.length() > 120) throw ApiException.bad("Keep each line of the signature under 120 characters.");
            if (!v.isEmpty()) out.put(f, v);
        }
        if (text(out, "name").isEmpty()) throw ApiException.bad("Add your name to the signature.");
        String email = text(out, "email");
        if (!email.isEmpty() && !email.matches("[^\\s@<>\"]+@[^\\s@<>\"]+\\.[^\\s@<>\"]+")) throw ApiException.bad("That email address doesn't look right.");
        String web = text(out, "website");
        if (!web.isEmpty() && !web.matches("(?i)(https?://)?[a-z0-9.-]+\\.[a-z]{2,}(/[^\\s\"<>]*)?")) throw ApiException.bad("That website doesn't look right.");
        String phone = text(out, "phone");
        if (!phone.isEmpty() && !phone.matches("[0-9+()\\-. ]{5,30}")) throw ApiException.bad("That phone number doesn't look right.");
        return out;
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
