package software.magizhchi.maillook;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.util.Date;

/** Magizhchi ID tokens for tests, signed exactly as the ID service signs them. */
final class TestIds {

    /** The suite's development default (application.yml); tests run with it. */
    static final String SUITE_SECRET = "magizhchi-suite-shared-secret-change-in-production-2026";

    private TestIds() {}

    static String idToken(String accountId, String email, String name) {
        return sign(SUITE_SECRET, accountId, email, name);
    }

    static String forgedToken(String email) {
        return sign("some-other-secret-that-is-long-enough-0123456789", "00000000-0000-0000-0000-000000000009", email, "x");
    }

    private static String sign(String secret, String accountId, String email, String name) {
        Date now = new Date();
        return Jwts.builder().issuer("magizhchi-id").subject(accountId).claim("email", email).claim("name", name)
                .issuedAt(now).expiration(new Date(now.getTime() + 600_000))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
    }
}
