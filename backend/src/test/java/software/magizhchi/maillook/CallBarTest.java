package software.magizhchi.maillook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import software.magizhchi.maillook.auth.SessionTokens;
import software.magizhchi.suite.SuiteException;
import software.magizhchi.suite.calls.SuiteCalls;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** The call bar's ring pass: only the signed-in person's own, only for a Magizhchi ID account. */
@SpringBootTest(properties = {
        "spring.datasource.url=${MAILLOOK_TEST_DB_URL:jdbc:h2:mem:callbar;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${MAILLOOK_TEST_DB_USER:sa}",
        "spring.datasource.password=${MAILLOOK_TEST_DB_PASSWORD:}",
        "maillook.sync.interval-ms=3600000",
        "maillook.ai.api-key="})
@AutoConfigureMockMvc
class CallBarTest {

    static final String ACCOUNT_ID = "2b7c1f0e-3333-4d3a-8b44-0000000000c1";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionTokens sessions;
    @MockBean SuiteCalls calls;

    @Test
    void handsTheSignedInPersonTheirOwnPass() throws Exception {
        reset(calls);
        String token = json.readTree(mvc.perform(post("/api/auth/sso").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"" + TestIds.idToken(ACCOUNT_ID, "caller@magizhchi.test", "Caller") + "\"}"))
                .andReturn().getResponse().getContentAsString()).path("token").asText();
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("pass", "p-123");
        raw.put("expiresAt", "2026-11-21T00:00:00Z");
        raw.put("apiUrl", "https://meetback.magizhchi.software");
        raw.put("name", "Caller");
        when(calls.configured()).thenReturn(true);
        when(calls.ringPass(ACCOUNT_ID, "caller@magizhchi.test", "Caller", "d-1"))
                .thenReturn(new SuiteCalls.RingPass("p-123", "2026-11-21T00:00:00Z", raw));

        var res = mvc.perform(get("/api/calls/ring-pass").param("device", "d-1").param("client", "web")
                .header("Authorization", "Bearer " + token)).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(res.getContentAsString());
        assertThat(body.path("pass").asText()).isEqualTo("p-123");
        assertThat(body.path("apiUrl").asText()).isEqualTo("https://meetback.magizhchi.software");
        assertThat(body.path("name").asText()).isEqualTo("Caller");

        // Meet's refusal comes back with Meet's status.
        when(calls.ringPass(any(), any(), any(), any())).thenThrow(new SuiteException(403, "meet_403", "Not allowed."));
        res = mvc.perform(get("/api/calls/ring-pass").header("Authorization", "Bearer " + token)).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(403);

        // Not set up on this server: 503.
        when(calls.configured()).thenReturn(false);
        res = mvc.perform(get("/api/calls/ring-pass").header("Authorization", "Bearer " + token)).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(503);

        // Signed out: 401.
        assertThat(mvc.perform(get("/api/calls/ring-pass")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void noMagizhchiIdAccountMeansNoPass() throws Exception {
        reset(calls);
        when(calls.configured()).thenReturn(true);
        jdbc.update("INSERT INTO users (email, name) VALUES (?, ?)", "old-user@magizhchi.test", "Old");
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, "old-user@magizhchi.test");

        var res = mvc.perform(get("/api/calls/ring-pass").param("device", "d-2")
                .header("Authorization", "Bearer " + sessions.issue(userId))).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(409);
        verify(calls, never()).ringPass(any(), any(), any(), any());
    }
}
