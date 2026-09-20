package dev.ledger.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledger.ledger.ReconciliationService;
import jakarta.servlet.ServletException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TransferIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ReconciliationService reconciliation;

    UUID from;
    UUID to;
    TransactionTemplate transaction;

    @BeforeEach
    void createAccounts() {
        transaction = new TransactionTemplate(transactionManager);
        from = fundedAccount("Northstar Commerce", "INR", 10000);
        to = fundedAccount("Monsoon Supply Co.", "INR", 0);
    }

    @Test
    void postsExactlyTwoEntriesAndUpdatesBothBalances() throws Exception {
        UUID id = transfer(from, to, "1250");
        assertThat(balance(from)).isEqualTo(8750);
        assertThat(balance(to)).isEqualTo(1250);
        assertThat(jdbc.queryForList("SELECT amount_minor FROM ledger_entries WHERE transfer_id = ? ORDER BY id",
                Long.class, id)).containsExactly(-1250L, 1250L);
        mvc.perform(get("/api/transfers/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transfer.status").value("COMPLETED"))
                .andExpect(jsonPath("$.transfer.amountMinor").value("1250"))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[0].counterpartyName").value("Monsoon Supply Co."))
                .andExpect(jsonPath("$.entries[0].runningBalanceMinor").value("8750"));
        assertThat(reconciliation.check().balanced()).isTrue();
    }

    @Test
    void canTransferTheEntireBalance() throws Exception {
        transfer(from, to, "10000");
        assertThat(balance(from)).isZero();
        assertThat(balance(to)).isEqualTo(10000);
    }

    @Test
    void rejectsOverdraftWithoutAnyPosting() throws Exception {
        rejected(from, to, "10001", "INSUFFICIENT_BALANCE");
        assertThat(balance(from)).isEqualTo(10000);
        assertThat(balance(to)).isZero();
        assertNoTransfersFrom(from);
    }

    @Test
    void rejectsSelfTransfersAndCurrencyMismatch() throws Exception {
        rejected(from, from, "100", "SAME_ACCOUNT");
        UUID usd = fundedAccount("Fieldwork Studio", "USD", 0);
        rejected(from, usd, "100", "CURRENCY_MISMATCH");
        assertNoTransfersFrom(from);
    }

    @Test
    void rejectsFrozenAndClosedAccountsOnEitherSide() throws Exception {
        for (String status : new String[]{"FROZEN", "CLOSED"}) {
            jdbc.update("UPDATE accounts SET status = ? WHERE id = ?", status, from);
            rejected(from, to, "100", "ACCOUNT_INACTIVE");
            jdbc.update("UPDATE accounts SET status = 'ACTIVE' WHERE id = ?", from);
            jdbc.update("UPDATE accounts SET status = ? WHERE id = ?", status, to);
            rejected(from, to, "100", "ACCOUNT_INACTIVE");
            jdbc.update("UPDATE accounts SET status = 'ACTIVE' WHERE id = ?", to);
        }
        assertNoTransfersFrom(from);
    }

    @Test
    void clearingAccountsCannotBeUsedThroughTheTransferApi() throws Exception {
        UUID clearing = jdbc.queryForObject("SELECT from_account_id FROM transfers WHERE to_account_id = ?",
                UUID.class, from);
        rejected(clearing, to, "100", "CLEARING_ACCOUNT");
        rejected(from, clearing, "100", "CLEARING_ACCOUNT");
        assertNoTransfersFrom(from);
    }

    @Test
    void rejectsAmountsThatAreMissingNonpositiveFractionalOrOutOfRange() throws Exception {
        for (String amount : new String[]{"null", "0", "-1", "1.5", "1e2", "\"1.5\"", "\"9223372036854775808\""}) {
            String payload = "{\"fromAccountId\":\"" + from + "\",\"toAccountId\":\"" + to
                    + "\",\"amountMinor\":" + amount + "}";
            mvc.perform(post("/api/transfers").contentType(APPLICATION_JSON).content(payload))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/transfers").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertNoTransfersFrom(from);
    }

    @Test
    void rejectsDestinationOverflowWithoutDebitingSource() throws Exception {
        UUID full = fundedAccount("Harbour Coffee", "INR", Long.MAX_VALUE);
        rejected(from, full, "1", "BALANCE_LIMIT");
        assertThat(balance(from)).isEqualTo(10000);
        assertThat(balance(full)).isEqualTo(Long.MAX_VALUE);
        assertNoTransfersFrom(from);
        assertThat(reconciliation.check().balanced()).isTrue();
    }

    @Test
    void transfersLargeAmountsWithoutLosingPrecision() throws Exception {
        long amount = 9007199254740993L;
        UUID large = fundedAccount("Papertrail Books", "INR", amount);
        UUID id = transfer(large, to, Long.toString(amount));
        mvc.perform(get("/api/transfers/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transfer.amountMinor").value("9007199254740993"));
        assertThat(balance(large)).isZero();
        assertThat(balance(to)).isEqualTo(amount);
    }

    @Test
    void returnsNotFoundForMissingAccountsAndTransfers() throws Exception {
        mvc.perform(post("/api/transfers").contentType(APPLICATION_JSON)
                        .content(payload(from, UUID.randomUUID(), "100")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/transfers/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/accounts/{id}/entries", UUID.randomUUID())).andExpect(status().isNotFound());
        assertNoTransfersFrom(from);
    }

    @Test
    void returnsRunningBalancesAcrossPagesAndFiltersTransfers() throws Exception {
        transfer(from, to, "1250");
        transfer(from, to, "750");
        mvc.perform(get("/api/accounts/{id}/entries?page=0&size=1", from))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].amountMinor").value("-750"))
                .andExpect(jsonPath("$.items[0].runningBalanceMinor").value("8000"))
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/accounts/{id}/entries?page=1&size=1", from))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].runningBalanceMinor").value("8750"));
        mvc.perform(get("/api/transfers").param("accountId", from.toString()).param("kind", "TRANSFER")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].amountMinor").value("750"));
        mvc.perform(get("/api/transfers")).andExpect(status().isOk());
        mvc.perform(get("/api/transfers?size=101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/accounts/{id}/entries?size=0", from)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/accounts/{id}/entries?page=-1", from)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/accounts/{id}/entries", to)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void repeatedRequestsExecuteAgainUntilIdempotencyIsImplemented() throws Exception {
        UUID first = transfer(from, to, "100");
        UUID second = transfer(from, to, "100");
        assertThat(second).isNotEqualTo(first);
        assertThat(balance(to)).isEqualTo(200);
    }

    @Test
    void databaseFailureRollsBackBalancesTransferAndEntries() {
        jdbc.execute("""
                CREATE FUNCTION test_reject_credit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.account_id = '%s'::uuid THEN
                        RAISE EXCEPTION 'forced credit failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """.formatted(to));
        jdbc.execute("CREATE TRIGGER test_reject_credit BEFORE INSERT ON ledger_entries FOR EACH ROW EXECUTE FUNCTION test_reject_credit()");
        try {
            assertThatThrownBy(() -> mvc.perform(post("/api/transfers").contentType(APPLICATION_JSON)
                    .content(payload(from, to, "100")))).isInstanceOf(ServletException.class);
        } finally {
            jdbc.execute("DROP TRIGGER test_reject_credit ON ledger_entries");
            jdbc.execute("DROP FUNCTION test_reject_credit()");
        }
        assertThat(balance(from)).isEqualTo(10000);
        assertThat(balance(to)).isZero();
        assertNoTransfersFrom(from);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE account_id = ?", Long.class, to)).isZero();
        assertThat(reconciliation.check().balanced()).isTrue();
    }

    @Test
    void databaseRejectsUnbalancedAndIncompleteTransfersAtCommit() {
        for (int entryCount : new int[]{0, 1, 2}) {
            UUID id = UUID.randomUUID();
            assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> {
                insertTransfer(id, from, to, 100, "TRANSFER");
                if (entryCount >= 1) {
                    insertEntry(id, from, "INR", -100);
                }
                if (entryCount == 2) {
                    insertEntry(id, to, "INR", 99);
                }
            })).isInstanceOf(RuntimeException.class).hasStackTraceContaining("matching debit and credit");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers WHERE id = ?", Long.class, id)).isZero();
        }
    }

    @Test
    void databaseRejectsBalancedEntriesForTheWrongAccounts() {
        UUID stranger = fundedAccount("Harbour Coffee", "INR", 0);
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> {
            insertTransfer(id, from, to, 100, "TRANSFER");
            insertEntry(id, from, "INR", -100);
            insertEntry(id, stranger, "INR", 100);
        })).isInstanceOf(RuntimeException.class).hasStackTraceContaining("matching debit and credit");
    }

    @Test
    void databaseRejectsChangesToPostedHistory() throws Exception {
        UUID id = transfer(from, to, "100");
        for (String sql : new String[]{
                "UPDATE ledger_entries SET amount_minor = amount_minor + 1 WHERE transfer_id = ?",
                "DELETE FROM ledger_entries WHERE transfer_id = ?",
                "UPDATE transfers SET amount_minor = 101 WHERE id = ?",
                "DELETE FROM transfers WHERE id = ?"}) {
            assertThatThrownBy(() -> jdbc.update(sql, id)).hasStackTraceContaining("append-only");
        }
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE ledger_entries")).hasStackTraceContaining("append-only");
        assertThat(reconciliation.check().balanced()).isTrue();
    }

    @Test
    void reconciliationDetectsBalanceDriftEvenWhenEntriesSumToZero() throws Exception {
        jdbc.update("UPDATE accounts SET balance_minor = balance_minor + 1 WHERE id = ?", from);
        try {
            var result = reconciliation.check();
            assertThat(result.balanced()).isFalse();
            assertThat(result.mismatches()).anySatisfy(mismatch -> {
                assertThat(mismatch.accountId()).isEqualTo(from);
                assertThat(mismatch.balanceMinor()).isEqualTo("10001");
                assertThat(mismatch.ledgerBalanceMinor()).isEqualTo("10000");
            });
            assertThat(result.currencies()).allSatisfy(total ->
                    assertThat(new BigDecimal(total.netMinor())).isZero());
            mvc.perform(get("/api/system/reconciliation")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.balanced").value(false));
        } finally {
            jdbc.update("UPDATE accounts SET balance_minor = balance_minor - 1 WHERE id = ?", from);
        }
    }

    private UUID transfer(UUID source, UUID destination, String amount) throws Exception {
        var response = mvc.perform(post("/api/transfers").contentType(APPLICATION_JSON)
                        .content(payload(source, destination, amount)))
                .andExpect(status().isCreated()).andReturn().getResponse();
        UUID id = UUID.fromString(mapper.readTree(response.getContentAsString()).get("id").asText());
        assertThat(response.getHeader("Location")).isEqualTo("/api/transfers/" + id);
        return id;
    }

    private void rejected(UUID source, UUID destination, String amount, String code) throws Exception {
        mvc.perform(post("/api/transfers").contentType(APPLICATION_JSON).content(payload(source, destination, amount)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value(code));
    }

    private String payload(UUID source, UUID destination, String amount) throws Exception {
        return mapper.writeValueAsString(Map.of("fromAccountId", source, "toAccountId", destination, "amountMinor", amount));
    }

    private long balance(UUID accountId) {
        return jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?", Long.class, accountId);
    }

    private void assertNoTransfersFrom(UUID accountId) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers WHERE from_account_id = ? AND kind = 'TRANSFER'",
                Long.class, accountId)).isZero();
    }

    private UUID fundedAccount(String name, String currency, long amount) {
        UUID id = UUID.randomUUID();
        transaction.executeWithoutResult(ignored -> {
            jdbc.update("INSERT INTO accounts (id, name, currency, balance_minor) VALUES (?, ?, ?, ?)", id, name, currency, amount);
            if (amount > 0) {
                UUID clearing = UUID.randomUUID();
                UUID funding = UUID.randomUUID();
                jdbc.update("INSERT INTO accounts (id, name, currency, kind, balance_minor) VALUES (?, 'Funding clearing', ?, 'CLEARING', ?)",
                        clearing, currency, -amount);
                jdbc.update("""
                        INSERT INTO transfers (id, from_account_id, to_account_id, currency, amount_minor, kind)
                        VALUES (?, ?, ?, ?, ?, 'FUNDING')
                        """, funding, clearing, id, currency, amount);
                insertEntry(funding, clearing, currency, -amount);
                insertEntry(funding, id, currency, amount);
            }
        });
        return id;
    }

    private void insertTransfer(UUID id, UUID source, UUID destination, long amount, String kind) {
        jdbc.update("""
                INSERT INTO transfers (id, from_account_id, to_account_id, currency, amount_minor, kind)
                VALUES (?, ?, ?, 'INR', ?, ?)
                """, id, source, destination, amount, kind);
    }

    private void insertEntry(UUID transfer, UUID account, String currency, long amount) {
        jdbc.update("INSERT INTO ledger_entries (transfer_id, account_id, currency, amount_minor) VALUES (?, ?, ?, ?)",
                transfer, account, currency, amount);
    }
}
