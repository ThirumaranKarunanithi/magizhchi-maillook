package software.magizhchi.maillook.bills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Today's rupee value of a dollar (or any other currency), so a USD bill can say "≈ ₹11,083".
 * Rates are the European Central Bank's reference rates through Frankfurter (free, no key),
 * kept for six hours. When they can't be had, bills simply show their own currency.
 */
@Component
public class FxRates {
    private static final Logger log = LoggerFactory.getLogger(FxRates.class);
    private static final Duration KEEP = Duration.ofHours(6);
    private static final Duration RETRY = Duration.ofMinutes(10);

    /** A rate: rupees for one unit, and the day the bank set it. */
    public record Rate(BigDecimal inr, String date) {}

    private record Held(Rate rate, Instant at) {}

    private final String url;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Map<String, Held> held = new ConcurrentHashMap<>();

    public FxRates(@Value("${maillook.fx.url:https://api.frankfurter.dev/v1/latest}") String url) {
        this.url = url == null ? "" : url.trim();
    }

    /** Rupees for one unit of {@code currency}, or null (INR itself, unknown, or not reachable). */
    public Rate toInr(String currency) {
        if (url.isEmpty() || currency == null || !currency.matches("[A-Z]{3}") || "INR".equals(currency)) return null;
        Held h = held.get(currency);
        Instant now = Instant.now();
        if (h != null && now.isBefore(h.at().plus(h.rate() == null ? RETRY : KEEP))) return h.rate();
        Rate r = fetch(currency);
        // Keep an older rate rather than nothing when the service is briefly down.
        if (r == null && h != null && h.rate() != null) r = h.rate();
        held.put(currency, new Held(r, now));
        return r;
    }

    /** {@code amount} in rupees, rounded to the paisa, or null. */
    public BigDecimal inr(BigDecimal amount, String currency) {
        Rate r = amount == null ? null : toInr(currency);
        return r == null ? null : amount.multiply(r.inr()).setScale(2, RoundingMode.HALF_UP);
    }

    private Rate fetch(String currency) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url + "?base=" + URLEncoder.encode(currency, StandardCharsets.UTF_8) + "&symbols=INR"))
                    .timeout(Duration.ofSeconds(6)).header("Accept", "application/json").GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return null;
            JsonNode j = json.readTree(res.body());
            JsonNode inr = j.path("rates").path("INR");
            if (!inr.isNumber() || inr.asDouble() <= 0) return null;
            return new Rate(inr.decimalValue(), j.path("date").asText(null));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.debug("No {} rate: {}", currency, e.toString());
            return null;
        }
    }
}
