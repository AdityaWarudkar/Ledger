package dev.ledger.api;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfiguration implements WebMvcConfigurer {
    private final String[] origins;

    public CorsConfiguration(@Value("${ledger.cors.allowed-origins:}") String origins) {
        this.origins = Arrays.stream(origins.split(",")).map(String::strip)
                .filter(origin -> !origin.isEmpty()).toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (origins.length == 0) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "OPTIONS")
                .allowedHeaders("Content-Type", "Accept", "Idempotency-Key")
                .exposedHeaders("Location", "Idempotency-Replayed", "Retry-After")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
