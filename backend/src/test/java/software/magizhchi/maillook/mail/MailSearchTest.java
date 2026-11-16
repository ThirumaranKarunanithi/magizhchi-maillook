package software.magizhchi.maillook.mail;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** The search box's operators, read the way people type them. */
class MailSearchTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final Instant NOW = Instant.parse("2026-11-07T06:00:00Z");

    @Test
    void operatorsComeOutAndTheRestIsText() {
        MailSearch s = MailSearch.parse("from:vendor invoice has:attachment is:unread", IST, NOW);
        assertThat(s.from()).isEqualTo("vendor");
        assertThat(s.text()).isEqualTo("invoice");
        assertThat(s.hasAttachment()).isTrue();
        assertThat(s.unread()).isTrue();
        assertThat(s.flagged()).isFalse();

        MailSearch quoted = MailSearch.parse("from:\"Priya Raman\" is:starred", IST, NOW);
        assertThat(quoted.from()).isEqualTo("Priya Raman");
        assertThat(quoted.flagged()).isTrue();
        assertThat(quoted.text()).isNull();

        assertThat(MailSearch.parse("has:attachment", IST, NOW).hasAttachment()).isTrue();
        assertThat(MailSearch.parse("has:attachment", IST, NOW).isEmpty()).isFalse();
    }

    @Test
    void datesAreDaysWhereThePersonIs() {
        MailSearch s = MailSearch.parse("after:2026-11-01 before:2026/11/05", IST, NOW);
        assertThat(s.after()).isEqualTo(Instant.parse("2026-10-31T18:30:00Z"));
        assertThat(s.before()).isEqualTo(Instant.parse("2026-11-04T18:30:00Z"));
        assertThat(MailSearch.parse("newer_than:2w", IST, NOW).after()).isEqualTo(NOW.minus(Duration.ofDays(14)));
        assertThat(MailSearch.parse("older_than:1y", IST, NOW).before()).isEqualTo(NOW.minus(Duration.ofDays(365)));
    }

    @Test
    void thingsThatOnlyLookLikeOperatorsStayText() {
        // An unknown value, a time of day, an address: all plain words.
        assertThat(MailSearch.parse("is:important", IST, NOW).text()).isEqualTo("is:important");
        assertThat(MailSearch.parse("after:lunch", IST, NOW).text()).isEqualTo("after:lunch");
        assertThat(MailSearch.parse("meet at 10:30", IST, NOW).text()).isEqualTo("meet at 10:30");
        assertThat(MailSearch.parse("ravi@from:x", IST, NOW).text()).isEqualTo("ravi@from:x");
        assertThat(MailSearch.parse("  ", IST, NOW).isEmpty()).isTrue();
    }

    @Test
    void chipsAndOperatorsTogetherNarrowIt() {
        MailSearch s = MailSearch.parse("after:2026-11-01", IST, NOW)
                .with(null, true, null, null, Instant.parse("2026-11-03T00:00:00Z"), null);
        assertThat(s.hasAttachment()).isTrue();
        assertThat(s.after()).isEqualTo(Instant.parse("2026-11-03T00:00:00Z"));    // the later of the two
        assertThat(MailSearch.parse(null, IST, NOW).with("ravi", null, false, null, null, null).from()).isEqualTo("ravi");
    }
    @Test
    void wordsMatchWhereAWordStarts() {
        java.util.regex.Pattern moa = java.util.regex.Pattern.compile(MailSearch.wordStart(" MOA "));
        assertThat(moa.matcher("fwd: memorandum (moa, aoa) printing").find()).isTrue();
        assertThat(moa.matcher("spicemoa_inc form").find()).isFalse();             // inside a longer word
        assertThat(moa.matcher("https://x.example/?id=ab3moaq").find()).isFalse();  // inside a link's code
        assertThat(moa.matcher("moa").find()).isTrue();
        // What people type is taken literally.
        java.util.regex.Pattern dotted = java.util.regex.Pattern.compile(MailSearch.wordStart("a.b (c)"));
        assertThat(dotted.matcher("see a.b (c) here").find()).isTrue();
        assertThat(dotted.matcher("see axb (c) here").find()).isFalse();
    }

    @Test
    void attachmentNamesMatchAnywhereInTheName() {
        java.util.regex.Pattern moa = java.util.regex.Pattern.compile(MailSearch.inName("moa"));
        assertThat(moa.matcher("[{\"index\":0,\"name\":\"spicemoa_inc-33.pdf\",\"size\":10}]").find()).isTrue();
        assertThat(moa.matcher("[{\"index\":0,\"name\":\"pan.pdf\",\"contenttype\":\"application/moa\"}]").find()).isFalse();
    }

    @Test
    void gmailIsAskedInItsOwnWords() {
        MailSearch s = MailSearch.parse("moa from:\"Vignesh S\" after:2023-01-01", IST, NOW);
        assertThat(ServerSearch.gmail(s)).isEqualTo("moa from:\"Vignesh S\" after:" + s.after().getEpochSecond());
        assertThat(ServerSearch.gmail(MailSearch.parse("is:unread", IST, NOW))).isNull();
    }

    @Test
    void gmailSearchGoesAsOneQuotedString() {
        var forms = SyncService.gmailForms("moa from:\"Vignesh S\"");
        assertThat(forms.get(0)[0]).isEqualTo("SEARCH X-GM-RAW \"moa from:\\\"Vignesh S\\\"\"");
        assertThat(forms.get(0)[1]).isNull();
        assertThat(SyncService.gmailForms("பள்ளி").get(0)[0]).isEqualTo("SEARCH CHARSET UTF-8");   // Tamil goes as UTF-8
    }
}
