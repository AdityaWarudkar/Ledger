package dev.ledger.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AccountIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clearAccounts() {
        jdbc.update("DELETE FROM accounts");
    }

    @Test
    void createsAnActiveAccountWithZeroBalanceAndFetchesIt() throws Exception {
        var response = mvc.perform(post("/api/accounts").contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"  Northstar Commerce  ","currency":"INR"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Northstar Commerce"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.balanceMinor").value("0"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn().getResponse();
        String id = mapper.readTree(response.getContentAsString()).get("id").asText();
        assertThat(response.getHeader("Location")).isEqualTo("/api/accounts/" + id);
        mvc.perform(get(response.getHeader("Location")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/api/accounts/{id}/balance", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.balanceMinor").value("0"));
    }

    @Test
    void rejectsInvalidFieldsWithoutInsertingAnAccount() throws Exception {
        mvc.perform(post("/api/accounts").contentType(APPLICATION_JSON)
                        .content("""
                                {"name":" ","currency":"XYZ"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.errors.name").value("Name is required"))
                .andExpect(jsonPath("$.errors.currency").exists());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts", Long.class)).isZero();
    }

    @Test
    void rejectsClientSuppliedBalances() throws Exception {
        mvc.perform(post("/api/accounts").contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"Northstar Commerce","currency":"INR","balanceMinor":10000}
                                """))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts", Long.class)).isZero();
    }

    @Test
    void returnsNotFoundAndRejectsMalformedIds() throws Exception {
        mvc.perform(get("/api/accounts/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value("Account not found"));
        mvc.perform(get("/api/accounts/not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void paginatesWithStableOrderingAndBounds() throws Exception {
        jdbc.update("""
                INSERT INTO accounts (id, name, currency, created_at) VALUES
                ('018f0000-0000-7000-8000-000000000001', 'Northstar Commerce', 'INR', '2026-09-14T09:15:00Z'),
                ('018f0000-0000-7000-8000-000000000002', 'Monsoon Supply Co.', 'INR', '2026-09-14T09:15:00Z')
                """);
        mvc.perform(get("/api/accounts?size=1&page=0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("Monsoon Supply Co."))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/accounts?size=1&page=1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].name").value("Northstar Commerce"));
        mvc.perform(get("/api/accounts?size=101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/accounts?page=-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/accounts?size=0")).andExpect(status().isBadRequest());
    }

    @Test
    void preservesLargeMinorUnitBalancesInJsonAndRejectsOverdraftsInTheDatabase() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, name, currency, balance_minor) VALUES (?, ?, ?, ?)",
                id, "Fieldwork Studio", "USD", 9007199254740993L);
        mvc.perform(get("/api/accounts/{id}/balance", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balanceMinor").value("9007199254740993"));
        assertThatThrownBy(() -> jdbc.update("UPDATE accounts SET balance_minor = -1 WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void returnsAnEmptyPage() throws Exception {
        mvc.perform(get("/api/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }
}
