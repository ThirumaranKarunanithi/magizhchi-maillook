package software.magizhchi.maillook.suite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import software.magizhchi.suite.SuiteException;
import software.magizhchi.suite.orgs.SuiteOrgs;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** "Forward this to HR": colleagues found by the designation the owner set in Magizhchi ID. */
class ColleaguesTest {

    SuiteOrgs orgs;
    Colleagues colleagues;

    static SuiteOrgs.Teammate mate(String name, String email, String designation, int match) {
        return new SuiteOrgs.Teammate("a-" + name, email, name, designation, "MEMBER", "o-1", "Sri Balaji", true, match);
    }

    @BeforeEach
    void setUp() {
        orgs = mock(SuiteOrgs.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class))).thenReturn(List.of("ravi@shop.in"));
        colleagues = new Colleagues(orgs, jdbc);
    }

    @Test
    void findsHr() {
        when(orgs.teammates("ravi@shop.in", "HR")).thenReturn(List.of(mate("Priya", "priya@shop.in", "HR", 4)));

        Colleagues.Found f = colleagues.find(7, "the HR");

        assertThat(f.exact()).isTrue();
        assertThat(f.emails()).containsExactly("priya@shop.in");
        assertThat(f.who()).isEqualTo("Priya (HR) <priya@shop.in>");
    }

    @Test
    void keepsOnlyTheBestMatches() {
        when(orgs.teammates("ravi@shop.in", "manager")).thenReturn(List.of(
                mate("Kumar", "kumar@shop.in", "Sales Manager", 2),
                mate("Anand", "anand@shop.in", "Branch Manager", 2),
                mate("Ravi Sr", "md@shop.in", "Managing Director", 1)));

        Colleagues.Found f = colleagues.find(7, "my manager");

        assertThat(f.emails()).containsExactly("kumar@shop.in", "anand@shop.in");
        assertThat(f.exact()).isFalse();   // a contact named "manager" would still win in a draft
    }

    @Test
    void nobodyOrIdDownIsSaidNotGuessed() {
        when(orgs.teammates("ravi@shop.in", "cashier")).thenReturn(List.of());
        assertThat(colleagues.find(7, "cashier").any()).isFalse();

        when(orgs.teammates("ravi@shop.in", "hr")).thenThrow(new SuiteException(503, "SUITE_NOT_CONFIGURED", "magizhchi.base-url is not set"));
        Colleagues.Found down = colleagues.find(7, "hr");
        assertThat(down.any()).isFalse();
        assertThat(down.problem()).contains("Magizhchi ID");
    }

    @Test
    void addressesAreNotRoles() {
        assertThat(colleagues.find(7, "hr@shop.in").any()).isFalse();
        assertThat(colleagues.find(7, "  ").any()).isFalse();
        verify(orgs, never()).teammates(anyString(), anyString());
        assertThat(Colleagues.ask("to our HR")).isEqualTo("HR");
    }
}
