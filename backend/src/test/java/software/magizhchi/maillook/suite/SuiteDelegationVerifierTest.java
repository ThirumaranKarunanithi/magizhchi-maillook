package software.magizhchi.maillook.suite;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import software.magizhchi.maillook.common.ApiException;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuiteDelegationVerifierTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-1234";
    private final SuiteDelegationVerifier verifier = new SuiteDelegationVerifier(SECRET);

    private static SecretKey keyFor(String audience) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Keys.hmacShaKeyFor(mac.doFinal(("magizhchi-suite-delegation|" + audience).getBytes(StandardCharsets.UTF_8)));
    }

    private static String token(String audience, String actor, String scope, SecretKey key, long ttlMs) {
        Date now = new Date();
        return Jwts.builder().issuer("magizhchi-id").subject(UUID.randomUUID().toString())
                .audience().add(audience).and()
                .claim("email", "Reader@Example.com").claim("act", actor).claim("scope", scope)
                .issuedAt(now).expiration(new Date(now.getTime() + ttlMs)).signWith(key).compact();
    }

    @Test
    void acceptsATokenMintedForThisProductByPa() throws Exception {
        var d = verifier.verify(token("Magizhchi MailLook", "Magizhchi PA", "read", keyFor("Magizhchi MailLook"), 60_000));
        assertEquals("reader@example.com", d.email());
        assertFalse(d.canWrite());
    }

    @Test
    void rejectsOtherProductsPlainSignInTokensOtherActorsAndExpiredOnes() throws Exception {
        assertThrows(ApiException.class, () -> verifier.verify(
                token("Magizhchi CRM", "Magizhchi PA", "read", keyFor("Magizhchi CRM"), 60_000)));
        assertThrows(ApiException.class, () -> verifier.verify(
                token("Magizhchi MailLook", "Magizhchi PA", "read",
                        Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), 60_000)));
        assertThrows(ApiException.class, () -> verifier.verify(
                token("Magizhchi MailLook", "Evil App", "read", keyFor("Magizhchi MailLook"), 60_000)));
        assertThrows(ApiException.class, () -> verifier.verify(
                token("Magizhchi MailLook", "Magizhchi PA", "read", keyFor("Magizhchi MailLook"), -60_000)));
    }

    @Test
    void disabledWithoutASecret() {
        assertFalse(new SuiteDelegationVerifier("").enabled());
        assertTrue(verifier.enabled());
        assertThrows(ApiException.class, () -> new SuiteDelegationVerifier("").verify("x"));
    }
}
