package software.magizhchi.maillook.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import software.magizhchi.maillook.auth.SessionTokens;

/**
 * CORS + the auth gate: every /api route except login and health needs a valid
 * Bearer token; the user id lands in the "userId" request attribute.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final SessionTokens tokens;
    private final String[] origins;

    public WebConfig(SessionTokens tokens, @Value("${cors.allowed-origins}") String origins) {
        this.tokens = tokens;
        this.origins = origins.split("\\s*,\\s*");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*").exposedHeaders("Content-Disposition");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
                if ("OPTIONS".equals(req.getMethod())) return true;
                String h = req.getHeader("Authorization");
                Long userId = tokens.verify(h != null && h.startsWith("Bearer ") ? h.substring(7) : null);
                if (userId == null) {
                    res.setStatus(401);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\":\"Please sign in again.\"}");
                    return false;
                }
                req.setAttribute("userId", userId);
                return true;
            }
        }).addPathPatterns("/api/**").excludePathPatterns("/api/auth/sso", "/api/health", "/api/hooks/meet", "/api/oauth/*/callback");
    }
}
