package software.magizhchi.maillook.suite;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Locale;
import java.util.Optional;

/**
 * Magizhchi ID tokens: HS256 with the suite's shared secret, issuer "magizhchi-id".
 * Verifies the token a person brings back from the ID sign-in page, and signs short
 * ones for that same person to sign in to other suite apps (Magizhchi PA).
 */
@Component
public class SuiteTokens {

    public static final String ISSUER = "magizhchi-id";
    private static final Logger log = LoggerFactory.getLogger(SuiteTokens.class);

    public record Identity(String accountId, String email, String name) {}

    private final SecretKey key;

    public SuiteTokens(@Value("${magizhchi.jwt.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalStateException("MAGIZHCHI_JWT_SECRET must be at least 32 bytes.");
        if (secret.contains("change-in-production")) {
            log.warn("MAGIZHCHI_JWT_SECRET is the suite's development default. Use the real shared secret in production.");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    /** The person in a Magizhchi ID token, or empty if it isn't valid (signature, expiry, issuer, claims). */
    public Optional<Identity> verify(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        try {
            Claims c = Jwts.parser().verifyWith(key).requireIssuer(ISSUER).build()
                    .parseSignedClaims(token.trim()).getPayload();
            String email = c.get("email", String.class);
            String sub = c.getSubject();
            if (email == null || email.isBlank() || sub == null || sub.isBlank()) return Optional.empty();
            return Optional.of(new Identity(sub, email.trim().toLowerCase(Locale.ROOT), c.get("name", String.class)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** A five-minute Magizhchi ID token for this person, as Magizhchi ID itself would sign it. */
    public String mint(Identity who) {
        Date now = new Date();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(who.accountId())
                .claim("email", who.email())
                .claim("name", who.name() == null ? "" : who.name())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 5 * 60_000))
                .signWith(key)
                .compact();
    }
}
