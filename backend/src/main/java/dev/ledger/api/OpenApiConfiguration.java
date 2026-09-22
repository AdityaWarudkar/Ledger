package dev.ledger.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
    @Bean
    OpenAPI ledgerApi() {
        return new OpenAPI().info(new Info().title("Ledger API").version("1.0")
                .description("Local payments ledger demo. Money responses are exact integer strings in minor units. "
                        + "Transfers require an Idempotency-Key; reuse the same key and payload after an unknown outcome. "
                        + "No authentication is implemented. Webhook test-receiver routes exist only in the local profile."));
    }
}
