package software.magizhchi.maillook.suite;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import software.magizhchi.maillook.common.ApiException;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Set;

/**
 * Verifies a Magizhchi ID <em>delegation</em> token: a short-lived token that lets Magizhchi PA
 * (the suite assistant) act for one person inside this product only. The signing key is derived
 * from the suite secret and this product's name, exactly as Magizhchi ID derives it, so the token
 * is not valid as a normal sign-in token and a token minted for another product is rejected.
 * See Magizhchi PA's docs/Suite-Brain-Protocol.md.
 */
@Component
public class SuiteDelegationVerifier {

    public static final String AUDIENCE = "Magizhchi MailLook";
    private static final String ALLOWED_ACTOR = "Magizhchi PA";

    public record Delegation(String email, Set<String> scopes) {
        public boolean canWrite() {
            return scopes.contains("write");
        }
    }

    private final SecretKey key;

    public SuiteDelegationVerifier(@Value("${magizhchi.jwt.secret:}") String secret) {
        this.key = secret == null || secret.isBlank() ? null : derive(secret.getBytes(StandardCharsets.UTF_8));
    }

    static SecretKey derive(byte[] secretBytes) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
            return Keys.hmacShaKeyFor(mac.doFinal(
                    ("magizhchi-suite-delegation|" + AUDIENCE).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean enabled() {
        return key != null;
    }

    public Delegation verify(String token) {
        if (key == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Suite access is not configured.");
        }
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (!ALLOWED_ACTOR.equals(c.get("act", String.class))
                    || c.getAudience() == null || !c.getAudience().contains(AUDIENCE)) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "Token is not for this product.");
            }
            String email = c.get("email", String.class);
            if (email == null || email.isBlank()) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "Token has no email.");
            }
            String scope = c.get("scope", String.class);
            Set<String> scopes = scope == null || scope.isBlank() ? Set.of() : Set.of(scope.trim().split("\\s+"));
            return new Delegation(email.trim().toLowerCase(), scopes);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid delegation token.");
        }
    }
}
