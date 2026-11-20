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
import software.magizhchi.suite.chat.SuiteChat;

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
    @MockBean SuiteChat chat;

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

    /** The Magizhchi bar: the person's own chat pass (Box) and ring pass (Meet); chat survives Meet refusing. */
    @Test
    void barPassHandsBothOwnPasses() throws Exception {
        reset(calls, chat);
        String token = json.readTree(mvc.perform(post("/api/auth/sso").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"" + TestIds.idToken(ACCOUNT_ID, "caller@magizhchi.test", "Caller") + "\"}"))
                .andReturn().getResponse().getContentAsString()).path("token").asText();
        Map<String, Object> chatRaw = new LinkedHashMap<>();
        chatRaw.put("pass", "c-1");
        chatRaw.put("expiresAt", "2026-11-21T01:00:00Z");
        Map<String, Object> ringRaw = new LinkedHashMap<>();
        ringRaw.put("pass", "r-1");
        when(chat.configured()).thenReturn(true);
        when(chat.chatPass(ACCOUNT_ID, "caller@magizhchi.test", "Caller"))
                .thenReturn(new SuiteChat.ChatPass("c-1", "2026-11-21T01:00:00Z", chatRaw));
        when(calls.configured()).thenReturn(true);
        when(calls.ringPass(ACCOUNT_ID, "caller@magizhchi.test", "Caller", "d-1"))
                .thenReturn(new SuiteCalls.RingPass("r-1", null, ringRaw));

        var res = mvc.perform(get("/api/calls/bar-pass").param("device", "d-1").param("client", "web")
                .header("Authorization", "Bearer " + token)).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(res.getContentAsString());
        assertThat(body.path("chat").path("pass").asText()).isEqualTo("c-1");
        assertThat(body.path("ring").path("pass").asText()).isEqualTo("r-1");

        // Meet refuses: chat still comes, ring is null.
        when(calls.ringPass(any(), any(), any(), any())).thenThrow(new SuiteException(503, "meet_down", "Down."));
        body = json.readTree(mvc.perform(get("/api/calls/bar-pass").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString());
        assertThat(body.path("chat").path("pass").asText()).isEqualTo("c-1");
        assertThat(body.path("ring").isNull()).isTrue();

        // Box refuses: its status, except a 401 (the page would sign out) which becomes 502.
        when(chat.chatPass(any(), any(), any())).thenThrow(new SuiteException(403, "box_403", "Not active."));
        assertThat(mvc.perform(get("/api/calls/bar-pass").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        reset(chat);
        when(chat.configured()).thenReturn(true);
        when(chat.chatPass(any(), any(), any())).thenThrow(new SuiteException(401, "box_401", "No."));
        assertThat(mvc.perform(get("/api/calls/bar-pass").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus()).isEqualTo(502);

        // Chat not set up: 503. Signed out: 401.
        when(chat.configured()).thenReturn(false);
        assertThat(mvc.perform(get("/api/calls/bar-pass").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus()).isEqualTo(503);
        assertThat(mvc.perform(get("/api/calls/bar-pass")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void barPassNeedsMagizhchiIdAccount() throws Exception {
        reset(chat);
        when(chat.configured()).thenReturn(true);
        jdbc.update("INSERT INTO users (email, name) VALUES (?, ?)", "old-bar@magizhchi.test", "Old");
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, "old-bar@magizhchi.test");
        var res = mvc.perform(get("/api/calls/bar-pass").header("Authorization", "Bearer " + sessions.issue(userId)))
                .andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(409);
        verify(chat, never()).chatPass(any(), any(), any());
    }
}
