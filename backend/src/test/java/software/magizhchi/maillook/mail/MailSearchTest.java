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
}
