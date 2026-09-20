package dev.ledger.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledger.account.AccountService;
import dev.ledger.account.CreateAccountRequest;
import dev.ledger.ledger.ReconciliationService;
import dev.ledger.transfer.CreateTransferRequest;
import dev.ledger.transfer.TransferService;
import jakarta.servlet.ServletException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Testcontainers
class IdempotencyIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountService accounts;
    @Autowired TransferService transfers;
    @Autowired IdempotentTransferService idempotentTransfers;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ReconciliationService reconciliation;

    UUID from;
    UUID to;
    String key;

    @BeforeEach
    void createAccounts() {
        from = accounts.create(new CreateAccountRequest("Harbour Coffee", "INR")).id();
        to = accounts.create(new CreateAccountRequest("Monsoon Supply Co.", "INR")).id();
        transfers.create(new CreateTransferRequest(UUID.fromString("018f0000-0000-7000-8000-000000000001"), from, 10000L));
        key = UUID.randomUUID().toString();
    }

    @Test
    void replaysTheOriginalStatusHeadersAndExactBodyWithoutRecheckingTheBalance() throws Exception {
        var first = send(key, payload("10000"));
        jdbc.update("UPDATE accounts SET status = 'FROZEN' WHERE id = ?", from);
        var replay = send(key, payload("10000"));
        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(first.getHeader("Idempotency-Replayed")).isEqualTo("false");
        assertReplay(first, replay);
        assertOneTransfer(10000);
    }

    @Test
    void canonicalizesFieldOrderWhitespaceAndIntegerRepresentation() throws Exception {
        var first = send(key, payload("1250"));
        String reordered = """
                { "amountMinor": 1250, "toAccountId": "%s", "fromAccountId": "%s" }
                """.formatted(to, from);
        assertReplay(first, send(key, reordered));
        assertOneTransfer(1250);
    }

    @Test
    void rejectsAChangedAmountSourceOrDestinationWithoutReplacingTheOriginalResponse() throws Exception {
        var first = send(key, payload("100"));
        List<String> changed = List.of(payload("101"),
                mapper.writeValueAsString(Map.of("fromAccountId", to, "toAccountId", from, "amountMinor", "100")),
                mapper.writeValueAsString(Map.of("fromAccountId", from, "toAccountId", UUID.randomUUID(), "amountMinor", "100")));
        for (String body : changed) {
            var rejected = send(key, body);
            assertThat(rejected.getStatus()).isEqualTo(422);
            assertThat(mapper.readTree(rejected.getContentAsString()).get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        }
        assertReplay(first, send(key, payload("100")));
        assertOneTransfer(100);
    }

    @Test
    void requiresOneValidKeyAndDoesNotReserveKeysForMalformedRequests() throws Exception {
        long before = jdbc.queryForObject("SELECT count(*) FROM idempotency_keys", Long.class);
        mvc.perform(post("/api/transfers").contentType(APPLICATION_JSON).content(payload("100")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
        for (String invalid : new String[]{"", " ", "with space", "a,b", "x".repeat(129), "café"}) {
            assertThat(send(invalid, payload("100")).getStatus()).isEqualTo(400);
        }
        mvc.perform(post("/api/transfers").header("Idempotency-Key", "first", "second")
                        .contentType(APPLICATION_JSON).content(payload("100")))
                .andExpect(status().isBadRequest());
        assertThat(send(key, "{}").getStatus()).isEqualTo(400);
        assertThat(send(key, "{not-json").getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys", Long.class)).isEqualTo(before);
        assertThat(send(key, payload("100")).getStatus()).isEqualTo(201);
    }

    @Test
    void acceptsTheMaximumKeyLengthAndKeepsKeysCaseSensitive() throws Exception {
        String longKey = "a".repeat(90) + key + ".:";
        assertThat(longKey).hasSize(128);
        assertThat(send(longKey, payload("100")).getStatus()).isEqualTo(201);
        assertThat(send(longKey.toUpperCase(), payload("100")).getStatus()).isEqualTo(201);
        assertThat(balance(to)).isEqualTo(200);
    }

    @Test
    void replaysInsufficientFundsEvenAfterFundingAndAllowsANewKey() throws Exception {
        var rejected = send(key, payload("10100"));
        assertThat(rejected.getStatus()).isEqualTo(422);
        assertThat(mapper.readTree(rejected.getContentAsString()).get("code").asText()).isEqualTo("INSUFFICIENT_BALANCE");
        transfers.create(new CreateTransferRequest(UUID.fromString("018f0000-0000-7000-8000-000000000001"), from, 100L));
        assertReplay(rejected, send(key, payload("10100")));
        assertThat(balance(to)).isZero();
        assertThat(send(UUID.randomUUID().toString(), payload("10100")).getStatus()).isEqualTo(201);
        assertThat(balance(to)).isEqualTo(10100);
    }

    @Test
    void replaysNotFoundEvenIfTheAccountIsCreatedLater() throws Exception {
        UUID missing = UUID.randomUUID();
        String body = mapper.writeValueAsString(Map.of("fromAccountId", from, "toAccountId", missing, "amountMinor", "100"));
        var rejected = send(key, body);
        assertThat(rejected.getStatus()).isEqualTo(404);
        jdbc.update("INSERT INTO accounts (id, name, currency) VALUES (?, 'Fieldwork Studio', 'INR')", missing);
        assertReplay(rejected, send(key, body));
        assertThat(balance(missing)).isZero();
    }

    @Test
    void eightConcurrentRequestsProduceOneTransferAndOnePairOfEntries() throws Exception {
        int workers = 8;
        var barrier = new CyclicBarrier(workers);
        String body = payload("500");
        try (var executor = Executors.newFixedThreadPool(workers)) {
            var futures = new ArrayList<Future<MockHttpServletResponse>>();
            for (int i = 0; i < workers; i++) {
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return send(key, body);
                }));
            }
            var responses = new ArrayList<MockHttpServletResponse>();
            for (var future : futures) {
                responses.add(future.get(20, TimeUnit.SECONDS));
            }
            var original = responses.stream().filter(response -> "false".equals(response.getHeader("Idempotency-Replayed")))
                    .toList();
            assertThat(original).hasSize(1);
            for (var response : responses) {
                assertThat(response.getStatus()).isEqualTo(201);
                assertThat(response.getContentAsString()).isEqualTo(original.getFirst().getContentAsString());
            }
            assertThat(responses.stream().filter(response -> "true".equals(response.getHeader("Idempotency-Replayed"))).count())
                    .isEqualTo(7);
        }
        assertOneTransfer(500);
    }

    @Test
    void concurrentDifferentPayloadsHaveOneWinnerAndOneConflict() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return send(key, payload("100")); });
            var second = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return send(key, payload("200")); });
            var responses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(201, 422);
            var winner = responses.stream().filter(response -> response.getStatus() == 201).findFirst().orElseThrow();
            assertOneTransfer(mapper.readTree(winner.getContentAsString()).get("amountMinor").asLong());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aWaitingRequestReplaysACommitOrTakesOverAfterRollback(boolean rollback) throws Exception {
        var claimed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                var response = idempotentTransfers.create(key, new CreateTransferRequest(from, to, 100L));
                claimed.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Test did not release the transaction");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                if (rollback) {
                    status.setRollbackOnly();
                }
                return response;
            }));
            try {
                assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();
                var waiting = executor.submit(() -> send(key, payload("100")));
                await().atMost(3, TimeUnit.SECONDS).until(() -> jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE wait_event_type = 'Lock' AND query LIKE 'INSERT INTO idempotency_keys%'
                        """, Long.class) > 0);
                assertThat(waiting.isDone()).isFalse();
                release.countDown();
                var firstResponse = first.get(10, TimeUnit.SECONDS);
                var secondResponse = waiting.get(10, TimeUnit.SECONDS);
                assertThat(secondResponse.getStatus()).isEqualTo(201);
                assertThat(secondResponse.getHeader("Idempotency-Replayed")).isEqualTo(Boolean.toString(!rollback));
                if (!rollback) {
                    assertThat(secondResponse.getContentAsString()).isEqualTo(firstResponse.body());
                }
                assertOneTransfer(100);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void aLongRunningClaimReturnsARetryableConflictWithoutReservingAnotherKey() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var insert = connection.prepareStatement("INSERT INTO idempotency_keys (key, request_hash) VALUES (?, ?)")) {
                insert.setString(1, key);
                insert.setString(2, "a".repeat(64));
                insert.executeUpdate();
            }
            try {
                var waiting = send(key, payload("100"));
                assertThat(waiting.getStatus()).isEqualTo(409);
                assertThat(waiting.getHeader("Retry-After")).isEqualTo("1");
                assertThat(mapper.readTree(waiting.getContentAsString()).get("code").asText()).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
                assertThat(balance(to)).isZero();
            } finally {
                connection.rollback();
            }
        }
        assertThat(send(key, payload("100")).getStatus()).isEqualTo(201);
        assertOneTransfer(100);
    }

    @Test
    void failureSavingTheResponseRollsBackTheMoneyAndAllowsRetry() throws Exception {
        jdbc.execute("""
                CREATE FUNCTION test_reject_response() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.key = '%s' THEN RAISE EXCEPTION 'forced response failure'; END IF;
                    RETURN NEW;
                END;
                $$
                """.formatted(key));
        jdbc.execute("CREATE TRIGGER test_reject_response BEFORE UPDATE ON idempotency_keys FOR EACH ROW EXECUTE FUNCTION test_reject_response()");
        try {
            assertThatThrownBy(() -> send(key, payload("100"))).isInstanceOf(ServletException.class);
        } finally {
            jdbc.execute("DROP TRIGGER test_reject_response ON idempotency_keys");
            jdbc.execute("DROP FUNCTION test_reject_response()");
        }
        assertThat(balance(from)).isEqualTo(10000);
        assertThat(balance(to)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE key = ?", Long.class, key)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers WHERE from_account_id = ?", Long.class, from)).isZero();
        var retry = send(key, payload("100"));
        assertThat(retry.getStatus()).isEqualTo(201);
        assertReplay(retry, send(key, payload("100")));
        assertOneTransfer(100);
    }

    @Test
    void incompleteClaimsCannotCommitAndStoredResponsesCannotBeChanged() throws Exception {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO idempotency_keys (key, request_hash) VALUES (?, ?)", key, "a".repeat(64)))
                .hasStackTraceContaining("response before commit");
        var first = send(key, payload("100"));
        assertThatThrownBy(() -> jdbc.update("UPDATE idempotency_keys SET response_body = '{}' WHERE key = ?", key))
                .hasStackTraceContaining("cannot be changed");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM idempotency_keys WHERE key = ?", key))
                .hasStackTraceContaining("append-only");
        assertReplay(first, send(key, payload("100")));
    }

    private MockHttpServletResponse send(String requestKey, String body) throws Exception {
        return mvc.perform(post("/api/transfers").header("Idempotency-Key", requestKey)
                .contentType(APPLICATION_JSON).content(body)).andReturn().getResponse();
    }

    private String payload(String amount) throws Exception {
        return mapper.writeValueAsString(Map.of("fromAccountId", from, "toAccountId", to, "amountMinor", amount));
    }

    private void assertReplay(MockHttpServletResponse original, MockHttpServletResponse replay) throws Exception {
        assertThat(replay.getStatus()).isEqualTo(original.getStatus());
        assertThat(replay.getContentAsString()).isEqualTo(original.getContentAsString());
        assertThat(replay.getContentType()).isEqualTo(original.getContentType());
        assertThat(replay.getHeader("Location")).isEqualTo(original.getHeader("Location"));
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
    }

    private void assertOneTransfer(long amount) {
        assertThat(balance(from)).isEqualTo(10000 - amount);
        assertThat(balance(to)).isEqualTo(amount);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers WHERE from_account_id = ?", Long.class, from)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ledger_entries e JOIN transfers t ON t.id = e.transfer_id
                WHERE t.from_account_id = ?
                """, Long.class, from)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE key = ?", Long.class, key)).isEqualTo(1);
        assertThat(reconciliation.check().balanced()).isTrue();
    }

    private long balance(UUID id) {
        return jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?", Long.class, id);
    }
}
