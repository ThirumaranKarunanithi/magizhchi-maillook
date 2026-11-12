package software.magizhchi.maillook.mail;

import software.magizhchi.maillook.forms.FormMail;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pictures placed in the middle of a mail. The writer pastes a screenshot and the text gets a marker
 * like "[image: screenshot-1.png]" where it goes; on sending, a picture file with that name goes into the
 * HTML version at that spot (as an inline part, cid:), not as an attachment at the end.
 */
final class InlineImages {
    static final Pattern MARK = Pattern.compile("\\[image: ([^\\]\\n]{1,200})\\]");

    record Img(String cid, String marker, MailActions.OutFile file) {}

    /** The files that stay attachments, and the pictures that go in the text. */
    record Split(List<MailActions.OutFile> attachments, List<Img> images) {}

    private InlineImages() {}

    static Split split(String text, List<MailActions.OutFile> files) {
        List<MailActions.OutFile> rest = new ArrayList<>(files == null ? List.of() : files);
        List<Img> imgs = new ArrayList<>();
        if (text == null || rest.isEmpty()) return new Split(rest, imgs);
        Map<String, MailActions.OutFile> byName = new LinkedHashMap<>();
        for (MailActions.OutFile f : rest) {
            if (isPicture(f)) byName.putIfAbsent(f.name(), f);
        }
        Matcher m = MARK.matcher(text);
        while (m.find()) {
            MailActions.OutFile f = byName.remove(m.group(1).strip());
            if (f == null) continue;
            rest.remove(f);
            imgs.add(new Img("img" + (imgs.size() + 1) + "." + Long.toHexString(System.nanoTime()) + "@maillook", m.group(), f));
        }
        return new Split(rest, imgs);
    }

    static boolean isPicture(MailActions.OutFile f) {
        String t = f.contentType() == null ? "" : f.contentType().toLowerCase(Locale.ROOT);
        return t.startsWith("image/") && !t.contains("svg") && f.bytes() != null && f.bytes().length > 0;
    }

    /**
     * The HTML version with each marker turned into its picture. {@code html} is an HTML version made already
     * (a designed signature), or null to make one from the text. {@code dataUris}: the picture itself in the
     * page (for the copy kept here), else a cid: link to the inline part (for the mail that goes out).
     */
    static String html(String html, String text, List<Img> imgs, boolean dataUris) {
        String out = html != null ? html
                : "<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:15px;line-height:1.5;color:#1f2d33\">"
                + FormMail.textToHtml(text == null ? "" : text) + "</div>";
        for (Img i : imgs) {
            String src = dataUris
                    ? "data:" + i.file().contentType() + ";base64," + Base64.getEncoder().encodeToString(i.file().bytes())
                    : "cid:" + i.cid();
            String tag = "<img src=\"" + src + "\" alt=\"" + FormMail.esc(i.file().name())
                    + "\" style=\"display:block;max-width:100%;height:auto;margin:8px 0;border:1px solid #e3e8ea;border-radius:6px\">";
            out = out.replace(FormMail.esc(i.marker()), tag);
        }
        return out;
    }
}
