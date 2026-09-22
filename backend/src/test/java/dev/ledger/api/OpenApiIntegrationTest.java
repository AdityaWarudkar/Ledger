package dev.ledger.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@Testcontainers
class OpenApiIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    @Autowired MockMvc mvc;

    @Test
    void documentsExactMoneyAndReplayContract() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Ledger API"))
                .andExpect(jsonPath("$.paths['/api/accounts'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/webhooks/endpoints'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/webhooks/deliveries/{id}/replay'].post.responses['202']").exists())
                .andExpect(jsonPath("$.paths['/api/transfers'].post.responses['201'].content['application/json'].schema['$ref']")
                        .value("#/components/schemas/TransferResponse"))
                .andExpect(jsonPath("$.paths['/api/transfers'].post.responses['409'].headers['Retry-After']").exists())
                .andExpect(jsonPath("$.components.schemas.CreateTransferRequest.properties.amountMinor.type").value("string"))
                .andExpect(jsonPath("$.paths['/api/webhooks/test-receiver/behavior']").doesNotExist());
    }

    @Test
    void exposesPrometheusAndSwaggerUi() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("jvm_memory_used_bytes")));
        mvc.perform(get("/docs")).andExpect(status().is3xxRedirection());
    }
}
