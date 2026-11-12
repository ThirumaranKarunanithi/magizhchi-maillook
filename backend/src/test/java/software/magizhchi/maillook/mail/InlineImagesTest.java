package software.magizhchi.maillook.mail;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InlineImagesTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

    @Test
    void pastedPictureGoesInItsSpotAndLeavesTheAttachments() {
        var shot = new MailActions.OutFile("screenshot-1.png", "image/png", PNG);
        var sheet = new MailActions.OutFile("status.xlsx", "application/vnd.ms-excel", new byte[]{1});
        var unused = new MailActions.OutFile("photo.jpg", "image/jpeg", new byte[]{9});
        String text = "Hi,\n\nSee below:\n[image: screenshot-1.png]\n\nPlease fix it & resend.";

        var split = InlineImages.split(text, List.of(shot, sheet, unused));
        assertThat(split.images()).extracting(i -> i.file().name()).containsExactly("screenshot-1.png");
        assertThat(split.attachments()).extracting(MailActions.OutFile::name).containsExactly("status.xlsx", "photo.jpg");

        String cid = split.images().get(0).cid();
        String out = InlineImages.html(null, text, split.images(), false);
        int img = out.indexOf("<img src=\"cid:" + cid + "\"");
        assertThat(img).isGreaterThan(out.indexOf("See below:"));
        assertThat(img).isLessThan(out.indexOf("Please fix it &amp; resend."));
        assertThat(out).doesNotContain("[image:");

        assertThat(InlineImages.html(null, text, split.images(), true)).contains("src=\"data:image/png;base64,");
    }

    @Test
    void markerWithoutItsFileStaysText() {
        var split = InlineImages.split("[image: gone.png]", List.of(new MailActions.OutFile("a.pdf", "application/pdf", new byte[]{1})));
        assertThat(split.images()).isEmpty();
        assertThat(split.attachments()).hasSize(1);
    }
}
