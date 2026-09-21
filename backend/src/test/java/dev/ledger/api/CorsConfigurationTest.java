package dev.ledger.api;

import dev.ledger.account.AccountController;
import dev.ledger.account.AccountPage;
import dev.ledger.account.AccountService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AccountController.class)
@Import(CorsConfiguration.class)
@TestPropertySource(properties = "ledger.cors.allowed-origins=http://localhost:3000,http://127.0.0.1:3000")
class CorsConfigurationTest {
    @Autowired MockMvc mvc;
    @MockitoBean AccountService accounts;

    @Test
    void acceptsConfiguredOriginsAndIdempotencyPreflightHeaders() throws Exception {
        for (String origin : List.of("http://localhost:3000", "http://127.0.0.1:3000")) {
            mvc.perform(options("/api/accounts").header("Origin", origin)
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "content-type,idempotency-key"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", origin))
                    .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        }
    }

    @Test
    void rejectsUntrustedOriginsAndUnconfiguredMethods() throws Exception {
        mvc.perform(options("/api/accounts").header("Origin", "https://untrusted.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(options("/api/accounts").header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isForbidden());
    }

    @Test
    void exposesResponseHeadersNeededByTheDashboard() throws Exception {
        when(accounts.list(0, 25)).thenReturn(new AccountPage(List.of(), 0, 25, 0, 0));
        mvc.perform(get("/api/accounts").header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("Idempotency-Replayed")))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("Location")))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("Retry-After")));
    }
}
