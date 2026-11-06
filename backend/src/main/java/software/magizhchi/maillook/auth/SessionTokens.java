package software.magizhchi.maillook.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/** Small signed session token: base64url("userId.expiresEpoch") + "." + HMAC-SHA256. */
@Component
public class SessionTokens {

    private final byte[] secret;
    private final long hours;

    public SessionTokens(@Value("${maillook.session-secret}") String secret,
                         @Value("${maillook.session-hours}") long hours) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.hours = hours;
    }

    public String issue(long userId) {
        long exp = Instant.now().plusSeconds(hours * 3600).getEpochSecond();
        String payload = b64(userId + "." + exp);
        return payload + "." + sign(payload);
    }

    /** @return the user id, or null when the token is invalid or expired. */
    public Long verify(String token) {
        if (token == null) return null;
        int dot = token.indexOf('.');
        if (dot <= 0) return null;
        String payload = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        if (!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8).split("\\.");
            long userId = Long.parseLong(parts[0]);
            long exp = Long.parseLong(parts[1]);
            return Instant.now().getEpochSecond() < exp ? userId : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
