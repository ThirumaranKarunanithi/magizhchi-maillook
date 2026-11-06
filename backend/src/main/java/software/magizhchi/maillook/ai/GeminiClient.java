package software.magizhchi.maillook.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import software.magizhchi.maillook.common.ApiException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Text-in, JSON-out client for the Gemini REST API (same conventions as Magizhchi Forms). */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private final String apiKey;
    private final String model;
    private final RestClient rest;
    private final ObjectMapper mapper = new ObjectMapper();

    public GeminiClient(@Value("${maillook.ai.api-key}") String apiKey,
                        @Value("${maillook.ai.model}") String model,
                        @Value("${maillook.ai.base-url}") String baseUrl,
                        @Value("${maillook.ai.timeout-seconds}") int timeoutSeconds) {
        this.apiKey = apiKey;
        this.model = model;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(10));
        f.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public JsonNode json(String system, String user, double temperature) {
        if (!isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI is off. Set GEMINI_API_KEY on the server to turn it on.");
        }
        Map<String, Object> body = Map.of(
                "system_instruction", Map.of("parts", List.of(Map.of("text", system))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", user)))),
                "generationConfig", Map.of("temperature", temperature, "response_mime_type", "application/json"));
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                JsonNode resp = rest.post()
                        .uri("/v1beta/models/{model}:generateContent?key={key}", model, apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
                String text = resp == null ? null
                        : resp.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText(null);
                if (text == null || text.isBlank()) throw new ApiException(HttpStatus.BAD_GATEWAY, "AI returned an empty answer.");
                String t = text.trim();
                if (t.startsWith("```")) {
                    t = t.substring(t.indexOf('\n') + 1);
                    if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
                }
                return mapper.readTree(t);
            } catch (HttpStatusCodeException e) {
                int code = e.getStatusCode().value();
                if (code == 429 || code >= 500) {
                    last = e;
                    sleep(attempt);
                    continue;
                }
                log.warn("Gemini call failed {}: {}", code, e.getResponseBodyAsString());
                throw new ApiException(HttpStatus.BAD_GATEWAY, "AI request failed (" + code + ").");
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                last = new RuntimeException(e);
                sleep(attempt);
            }
        }
        log.warn("Gemini unavailable", last);
        throw new ApiException(HttpStatus.BAD_GATEWAY, "AI is busy right now. Please try again.");
    }

    private static void sleep(int attempt) {
        try {
            Thread.sleep(1000L * (attempt + 1));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
