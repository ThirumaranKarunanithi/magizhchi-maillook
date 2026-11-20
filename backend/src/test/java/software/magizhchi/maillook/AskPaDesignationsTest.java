package software.magizhchi.maillook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import software.magizhchi.maillook.ai.AiService;
import software.magizhchi.suite.orgs.SuiteOrgs;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * "Send job applications to HR from now on", "write to the manager": Ask PA turns a role into the colleague who
 * holds that designation in Magizhchi ID, says who it is, and never guesses when nobody does.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${MAILLOOK_TEST_DB_URL:jdbc:h2:mem:askpadesignations;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${MAILLOOK_TEST_DB_USER:sa}",
        "spring.datasource.password=${MAILLOOK_TEST_DB_PASSWORD:}",
        "maillook.sync.interval-ms=3600000",
        "maillook.meet.recap-sweep-ms=3600000",
        "maillook.ai.api-key="})
@AutoConfigureMockMvc
class AskPaDesignationsTest {

    static final String ME = "owner@magizhchi.test";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean AiService ai;
    @MockBean SuiteOrgs orgs;

    @Test
    void rolesBecomeColleagues() throws Exception {
        String token = json.readTree(mvc.perform(post("/api/auth/sso").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"" + TestIds.idToken("2b7c1f0e-7777-4d3a-8b44-0000000000d1", ME, "Owner") + "\"}"))
                .andReturn().getResponse().getContentAsString()).path("token").asText();
        when(ai.enabled(anyLong())).thenReturn(true);
        when(orgs.teammates(ME, "HR")).thenReturn(List.of(
                new SuiteOrgs.Teammate("a-2", "priya@magizhchi.test", "Priya", "HR", "MEMBER", "o-1", "Demo Traders", true, 4)));
        when(orgs.teammates(ME, "cashier")).thenReturn(List.of());

        // An automation that forwards to HR: Priya's address, though it never appeared in this mail.
        plan("{\"actions\":[{\"type\":\"rule\",\"subject\":\"application for\",\"section\":\"Job applications\","
                + "\"forwardTo\":[\"HR\"]}],\"say\":\"New job applications will now be forwarded by themselves.\"}");
        JsonNode r = chat(token, "send job applications to HR from now on");
        assertThat(r.path("reply").asText()).contains("Priya (HR) <priya@magizhchi.test>");
        assertThat(r.path("actions").get(0).path("ruleId").asLong()).isPositive();
        String rules = mvc.perform(get("/api/rules").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        assertThat(rules).contains("priya@magizhchi.test");

        // A draft to HR has her address in To.
        plan("{\"actions\":[{\"type\":\"compose\",\"to\":[\"the HR\"],\"subject\":\"Leave\",\"body\":\"Leave on Friday.\"}],\"say\":\"Draft ready.\"}");
        r = chat(token, "write to HR about leave on Friday");
        assertThat(r.path("actions").get(0).path("to").toString()).contains("priya@magizhchi.test");

        // Nobody is the cashier: no rule, and it says so.
        plan("{\"actions\":[{\"type\":\"rule\",\"subject\":\"bill\",\"forwardTo\":[\"cashier\"]}],\"say\":\"Done.\"}");
        r = chat(token, "forward bills to the cashier");
        assertThat(r.path("actions").size()).isZero();
        assertThat(r.path("reply").asText()).contains("couldn't find who your cashier is");
    }

    private void plan(String plan) throws Exception {
        when(ai.appPlan(anyLong(), anyString(), anyString())).thenReturn(json.readTree(plan));
    }

    private JsonNode chat(String token, String message) throws Exception {
        var res = mvc.perform(post("/api/assistant/chat").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"" + message + "\"}")).andReturn().getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        return json.readTree(res.getContentAsString());
    }
}
