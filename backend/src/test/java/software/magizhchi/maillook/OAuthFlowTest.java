package software.magizhchi.maillook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import software.magizhchi.maillook.account.OAuthTokens;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Sign in with Google for a mailbox, end to end against a stand-in Google token endpoint and a real
 * (in-memory) IMAP server that takes the access token: the sign-in page link (PKCE, offline access,
 * the address as a hint), the callback adding the mailbox, syncing with the token, refreshing it
 * (and keeping a rotated refresh token), and the ways sign-in can go wrong.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${MAILLOOK_TEST_DB_URL:jdbc:h2:mem:oauth;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${MAILLOOK_TEST_DB_USER:sa}",
        "spring.datasource.password=${MAILLOOK_TEST_DB_PASSWORD:}",
        "maillook.sync.interval-ms=3600000",
        "maillook.ai.api-key=",
        "magizhchi.apps.pa.api=",
        "maillook.public-url=https://api.maillook.test",
        "maillook.web-url=https://maillook.test",
        "maillook.oauth.google.client-id=test-client",
        "maillook.oauth.google.client-secret=test-secret",
        "maillook.oauth.google.auth-url=https://accounts.google.test/o/oauth2/v2/auth",
        "maillook.oauth.google.imap-host=localhost",
        "maillook.oauth.google.imap-port=3143",
        "maillook.oauth.google.smtp-host=localhost",
        "maillook.oauth.google.smtp-port=3025"})
@AutoConfigureMockMvc
class OAuthFlowTest {

    static final String MAILBOX = "pat@gmail.test";

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
            // The mailbox accepts the current access token as its credential.
            .withConfiguration(GreenMailConfiguration.aConfig().withUser(MAILBOX, MAILBOX, "access-1"));

    static final ObjectMapper JSON = new ObjectMapper();
    static final List<Map<String, String>> tokenCalls = new CopyOnWriteArrayList<>();
    /** Set by the test: the S256 challenge the sign-in link carried, checked against the verifier. */
    static volatile String expectedChallenge;
    static volatile boolean refreshRevoked;
    static final HttpServer google = start();

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry r) {
        r.add("maillook.oauth.google.token-url", () -> "http://localhost:" + google.getAddress().getPort() + "/token");
    }

    @AfterAll
    static void stop() {
        google.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired OAuthTokens tokens;

    @Test
    void signInWithGoogleAddsTheMailboxAndKeepsItsTokenFresh() throws Exception {
        String token = call(post("/api/auth/sso").contentType(MediaType.APPLICATION_JSON).content(
                "{\"idToken\":\"" + TestIds.idToken("5c1d2e3f-0000-4aaa-8bbb-000000000099", "pat@magizhchi.test", "Pat") + "\"}"), null, 200)
                .path("token").asText();

        // Only Google is set up on this server, and the form offers it for Gmail addresses.
        JsonNode offered = call(get("/api/accounts/oauth"), token, 200);
        assertThat(offered.path("providers")).hasSize(1);
        assertThat(offered.path("providers").get(0).path("id").asText()).isEqualTo("google");
        JsonNode gmail = call(post("/api/accounts/detect").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@gmail.com\"}"), token, 200);
        assertThat(gmail.path("oauth").asText()).isEqualTo("google");
        assertThat(gmail.path("help").path("kind").asText()).isEqualTo("app_password");
        assertThat(gmail.path("help").path("url").asText()).isEqualTo("https://myaccount.google.com/apppasswords");
        JsonNode outlook = call(post("/api/accounts/detect").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@hotmail.co.uk\"}"), token, 200);
        assertThat(outlook.path("provider").asText()).isEqualTo("microsoft");
        assertThat(outlook.path("oauth").isNull()).isTrue();
        assertThat(outlook.path("help").path("kind").asText()).isEqualTo("oauth_only");
        call(post("/api/accounts/oauth/microsoft").contentType(MediaType.APPLICATION_JSON).content("{}"), token, 400);

        // The sign-in link: Google's page, offline access, PKCE, back to this API, the address as a hint.
        String url = call(post("/api/accounts/oauth/google").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + MAILBOX + "\"}"), token, 200).path("url").asText();
        assertThat(url).startsWith("https://accounts.google.test/o/oauth2/v2/auth?");
        Map<String, String> q = query(URI.create(url).getRawQuery());
        assertThat(q).containsEntry("client_id", "test-client").containsEntry("response_type", "code")
                .containsEntry("access_type", "offline").containsEntry("prompt", "consent")
                .containsEntry("code_challenge_method", "S256").containsEntry("login_hint", MAILBOX)
                .containsEntry("redirect_uri", "https://api.maillook.test/api/oauth/google/callback");
        assertThat(q.get("scope")).contains("https://mail.google.com/").contains("email");
        expectedChallenge = q.get("code_challenge");

        // Google sends the browser back: the mailbox is added and the person lands on Manage accounts.
        String to = redirect(get("/api/oauth/google/callback").param("code", "code-1").param("state", q.get("state")));
        assertThat(to).isEqualTo("https://maillook.test/accounts?connected=pat%40gmail.test");
        assertThat(tokenCalls.get(0)).containsEntry("grant_type", "authorization_code").containsEntry("code", "code-1")
                .containsEntry("client_secret", "test-secret")
                .containsEntry("redirect_uri", "https://api.maillook.test/api/oauth/google/callback");

        JsonNode acc = waitForSync(token);
        assertThat(acc.path("email").asText()).isEqualTo(MAILBOX);
        assertThat(acc.path("auth").asText()).isEqualTo("oauth");
        assertThat(acc.path("provider").asText()).isEqualTo("google");
        long id = acc.path("id").asLong();

        // The same state can't be used twice.
        assertThat(redirect(get("/api/oauth/google/callback").param("code", "code-1").param("state", q.get("state"))))
                .startsWith("https://maillook.test/accounts?connectError=");

        // There's no password to change on a Google sign-in.
        call(patch("/api/accounts/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"x\"}"), token, 400);

        // When the access token runs out, the refresh token gets a new one; a rotated refresh token is kept.
        greenMail.getUserManager().getUserByEmail(MAILBOX).setPassword("access-2");
        tokens.forget(id);
        call(post("/api/accounts/" + id + "/sync"), token, 202);
        waitForSync(token);
        Map<String, String> refresh = tokenCalls.get(tokenCalls.size() - 1);
        assertThat(refresh).containsEntry("grant_type", "refresh_token").containsEntry("refresh_token", "refresh-1");

        greenMail.getUserManager().getUserByEmail(MAILBOX).setPassword("access-3");
        tokens.forget(id);
        call(post("/api/accounts/" + id + "/sync"), token, 202);
        waitForSync(token);
        assertThat(tokenCalls.get(tokenCalls.size() - 1)).containsEntry("refresh_token", "refresh-2");

        // If the person takes MailLook's access back in their Google account, the mailbox asks them to sign in again.
        refreshRevoked = true;
        tokens.forget(id);
        call(post("/api/accounts/" + id + "/sync"), token, 202);
        JsonNode failed = waitForStatus(token, "error");
        assertThat(failed.path("lastError").asText()).contains("Google needs you to sign in again for " + MAILBOX);

        // Signing in again switches the same mailbox back on (no second copy).
        refreshRevoked = false;
        greenMail.getUserManager().getUserByEmail(MAILBOX).setPassword("access-1");
        String again = call(post("/api/accounts/oauth/google").contentType(MediaType.APPLICATION_JSON).content("{}"), token, 200).path("url").asText();
        Map<String, String> q2 = query(URI.create(again).getRawQuery());
        expectedChallenge = q2.get("code_challenge");
        assertThat(redirect(get("/api/oauth/google/callback").param("code", "code-2").param("state", q2.get("state"))))
                .isEqualTo("https://maillook.test/accounts?connected=pat%40gmail.test");
        assertThat(call(get("/api/accounts"), token, 200)).hasSize(1);
        waitForSync(token);

        // Cancelling on Google's page adds nothing and says so.
        String third = call(post("/api/accounts/oauth/google").contentType(MediaType.APPLICATION_JSON).content("{}"), token, 200).path("url").asText();
        String cancelled = redirect(get("/api/oauth/google/callback").param("error", "access_denied")
                .param("state", query(URI.create(third).getRawQuery()).get("state")));
        assertThat(URLDecoder.decode(cancelled, StandardCharsets.UTF_8)).endsWith("connectError=Sign-in was cancelled, so nothing was added.");

        // A stranger's callback (unknown state) does nothing.
        assertThat(redirect(get("/api/oauth/google/callback").param("code", "x").param("state", "made-up")))
                .startsWith("https://maillook.test/accounts?connectError=");
    }

    // ── stand-in Google token endpoint ───────────────────────────

    static HttpServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            s.createContext("/token", OAuthFlowTest::token);
            s.start();
            return s;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static void token(HttpExchange ex) throws java.io.IOException {
        Map<String, String> f = query(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        tokenCalls.add(f);
        String body;
        int status = 200;
        if ("authorization_code".equals(f.get("grant_type"))) {
            boolean pkceOk;
            try {
                byte[] d = MessageDigest.getInstance("SHA-256").digest(f.getOrDefault("code_verifier", "").getBytes(StandardCharsets.US_ASCII));
                pkceOk = Base64.getUrlEncoder().withoutPadding().encodeToString(d).equals(expectedChallenge);
            } catch (Exception e) {
                pkceOk = false;
            }
            if (!pkceOk) {
                status = 400;
                body = "{\"error\":\"invalid_grant\",\"error_description\":\"Bad code_verifier\"}";
            } else {
                String claims = Base64.getUrlEncoder().withoutPadding().encodeToString(
                        ("{\"email\":\"" + MAILBOX + "\",\"email_verified\":true}").getBytes(StandardCharsets.UTF_8));
                body = "{\"access_token\":\"access-1\",\"refresh_token\":\"refresh-1\",\"expires_in\":3599,"
                        + "\"id_token\":\"eyJhbGciOiJub25lIn0." + claims + ".sig\",\"token_type\":\"Bearer\"}";
            }
        } else if (refreshRevoked) {
            status = 400;
            body = "{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked.\"}";
        } else if ("refresh-1".equals(f.get("refresh_token"))) {
            body = "{\"access_token\":\"access-2\",\"refresh_token\":\"refresh-2\",\"expires_in\":3599}";
        } else {
            body = "{\"access_token\":\"access-3\",\"expires_in\":3599}";
        }
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    static Map<String, String> query(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    // ── helpers ──────────────────────────────────────────────────

    private String redirect(MockHttpServletRequestBuilder req) throws Exception {
        var res = mvc.perform(req).andReturn().getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(302);
        return res.getHeader("Location");
    }

    private JsonNode call(MockHttpServletRequestBuilder req, String token, int status) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        var res = mvc.perform(req).andReturn().getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(status);
        String body = res.getContentAsString(StandardCharsets.UTF_8);
        return body.isEmpty() ? JSON.createObjectNode() : JSON.readTree(body);
    }

    private JsonNode waitForSync(String token) throws Exception {
        JsonNode a = waitForStatus(token, "ok");
        return a;
    }

    private JsonNode waitForStatus(String token, String status) throws Exception {
        JsonNode last = null;
        for (int i = 0; i < 100; i++) {
            JsonNode list = call(get("/api/accounts"), token, 200);
            if (list.size() > 0) {
                last = list.get(0);
                String s = last.path("status").asText();
                if (status.equals(s)) return last;
                if ("ok".equals(status) && "error".equals(s)) throw new AssertionError("Sync failed: " + last.path("lastError").asText());
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Mailbox never reached " + status + ": " + last);
    }
}
