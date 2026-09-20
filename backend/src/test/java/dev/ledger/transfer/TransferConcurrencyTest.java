package dev.ledger.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledger.account.AccountService;
import dev.ledger.account.CreateAccountRequest;
import dev.ledger.idempotency.IdempotentResponse;
import dev.ledger.idempotency.IdempotentTransferService;
import dev.ledger.ledger.ReconciliationService;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles("local")
@Testcontainers
class TransferConcurrencyTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired AccountService accounts;
    @Autowired TransferService transfers;
    @Autowired IdempotentTransferService idempotency;
    @Autowired ReconciliationService reconciliation;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void competingDebitsSpendOnlyTheAvailableBalance() throws Exception {
        UUID source = account("Northstar settlements", 1000);
        UUID destination = account("Monsoon receivables", 0);
        var totals = merchantTotals();
        var results = runConcurrently(12, 5, index -> send(source, destination, 100));
        assertThat(results.stream().filter(result -> result.status() == 201).count()).isEqualTo(10);
        assertThat(results.stream().filter(result -> result.status() == 422).count()).isEqualTo(50);
        for (var result : results) {
            if (result.status() == 422) {
                assertThat(mapper.readTree(result.body()).get("code").asText()).isEqualTo("INSUFFICIENT_BALANCE");
            }
        }
        assertThat(balance(source)).isZero();
        assertThat(balance(destination)).isEqualTo(1000);
        assertInvariants(totals);
    }

    @Test
    void oppositeDirectionTransfersFinishWithoutDeadlocksOrLostUpdates() throws Exception {
        UUID first = account("Harbour Coffee", 10000);
        UUID second = account("Papertrail Books", 10000);
        var totals = merchantTotals();
        var results = runConcurrently(12, 20, index -> index % 2 == 0
                ? send(first, second, 25) : send(second, first, 25));
        assertThat(results).allSatisfy(result -> assertThat(result.status()).isEqualTo(201));
        assertThat(balance(first)).isEqualTo(10000);
        assertThat(balance(second)).isEqualTo(10000);
        assertInvariants(totals);
    }

    @ParameterizedTest
    @ValueSource(ints = {7, 41, 203})
    void sharedAccountNetworksMatchTheExpectedFinalBalances(int seed) throws Exception {
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 6; i++) {
            ids.add(account("Settlement account " + (i + 1), 10000));
        }
        long[] expected = {10000, 10000, 10000, 10000, 10000, 10000};
        var random = new Random(seed);
        var requests = new ArrayList<CreateTransferRequest>();
        for (int i = 0; i < 240; i++) {
            int from = random.nextInt(ids.size());
            int to = (from + 1 + random.nextInt(ids.size() - 1)) % ids.size();
            long amount = 1 + random.nextInt(20);
            requests.add(new CreateTransferRequest(ids.get(from), ids.get(to), amount));
            expected[from] -= amount;
            expected[to] += amount;
        }
        var totals = merchantTotals();
        var results = runConcurrently(12, 20, index -> idempotency.create(UUID.randomUUID().toString(), requests.get(index)));
        assertThat(results).allSatisfy(result -> assertThat(result.status()).isEqualTo(201));
        for (int i = 0; i < ids.size(); i++) {
            assertThat(balance(ids.get(i))).as("account %s, seed %s", i, seed).isEqualTo(expected[i]);
        }
        assertInvariants(totals);
    }

    @Test
    void duplicateKeysMixedWithIndependentTransfersStillPostOncePerKey() throws Exception {
        UUID source = account("Fieldwork settlements", 10000);
        UUID destination = account("Fieldwork receivables", 0);
        var keys = new ArrayList<String>();
        for (int i = 0; i < 20; i++) {
            keys.add(UUID.randomUUID().toString());
        }
        var totals = merchantTotals();
        var results = runConcurrently(12, 10, index -> idempotency.create(keys.get(index % keys.size()),
                new CreateTransferRequest(source, destination, 100L)));
        assertThat(results).allSatisfy(result -> assertThat(result.status()).isEqualTo(201));
        assertThat(results.stream().filter(result -> !result.replayed()).count()).isEqualTo(20);
        assertThat(results.stream().map(IdempotentResponse::transferId).distinct().count()).isEqualTo(20);
        assertThat(balance(source)).isEqualTo(8000);
        assertThat(balance(destination)).isEqualTo(2000);
        assertInvariants(totals);
    }

    @Test
    void reconciliationSeesOnlyCommittedStateWhileAWritingTransactionIsOpen() throws Exception {
        UUID source = account("Harbour settlements", 1000);
        UUID destination = account("Harbour receivables", 0);
        var totals = merchantTotals();
        var written = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var writer = executor.submit(() -> new TransactionTemplate(transactionManager).execute(ignored -> {
                var result = send(source, destination, 100);
                written.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Test did not release the writer");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return result;
            }));
            assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 5; i++) {
                assertThat(reconciliation.check().balanced()).isTrue();
                assertThat(balance(source)).isEqualTo(1000);
                assertThat(balance(destination)).isZero();
            }
            release.countDown();
            assertThat(writer.get(10, TimeUnit.SECONDS).status()).isEqualTo(201);
            assertThat(balance(source)).isEqualTo(900);
            assertThat(balance(destination)).isEqualTo(100);
            assertInvariants(totals);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void aBlockedDebitChecksTheBalanceAfterThePreviousWriterCommits() throws Exception {
        UUID source = account("Monsoon settlements", 100);
        UUID destination = account("Northstar receivables", 0);
        var totals = merchantTotals();
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<IdempotentResponse> waiting = new TransactionTemplate(transactionManager).execute(ignored -> {
                assertThat(send(source, destination, 100).status()).isEqualTo(201);
                var future = executor.submit(() -> send(source, destination, 100));
                await().atMost(5, TimeUnit.SECONDS).until(() -> jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE wait_event_type = 'Lock' AND query LIKE '%for no key update%'
                        """, Long.class) > 0);
                assertThat(future.isDone()).isFalse();
                return future;
            });
            var result = waiting.get(10, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(422);
            assertThat(mapper.readTree(result.body()).get("code").asText()).isEqualTo("INSUFFICIENT_BALANCE");
            assertThat(balance(source)).isZero();
            assertThat(balance(destination)).isEqualTo(100);
            assertInvariants(totals);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private List<IdempotentResponse> runConcurrently(int workers, int requestsPerWorker,
            IntFunction<IdempotentResponse> request) throws Exception {
        var executor = Executors.newFixedThreadPool(workers);
        var start = new CyclicBarrier(workers);
        var futures = new ArrayList<Future<List<IdempotentResponse>>>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        try {
            for (int worker = 0; worker < workers; worker++) {
                int offset = worker * requestsPerWorker;
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    var responses = new ArrayList<IdempotentResponse>();
                    for (int i = 0; i < requestsPerWorker; i++) {
                        responses.add(request.apply(offset + i));
                    }
                    return responses;
                }));
            }
            var results = new ArrayList<IdempotentResponse>();
            for (var future : futures) {
                results.addAll(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            }
            return results;
        } finally {
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private UUID account(String name, long balance) {
        UUID id = accounts.create(new CreateAccountRequest(name, "INR")).id();
        if (balance > 0) {
            transfers.create(new CreateTransferRequest(UUID.fromString("018f0000-0000-7000-8000-000000000001"), id, balance));
        }
        return id;
    }

    private IdempotentResponse send(UUID source, UUID destination, long amount) {
        return idempotency.create(UUID.randomUUID().toString(), new CreateTransferRequest(source, destination, amount));
    }

    private long balance(UUID id) {
        return jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?", Long.class, id);
    }

    private Map<String, BigDecimal> merchantTotals() {
        var totals = new TreeMap<String, BigDecimal>();
        jdbc.query("SELECT currency, sum(balance_minor) AS total FROM accounts WHERE kind = 'MERCHANT' GROUP BY currency",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> totals.put(row.getString("currency"), row.getBigDecimal("total")));
        return totals;
    }

    private void assertInvariants(Map<String, BigDecimal> expectedTotals) {
        assertThat(merchantTotals()).isEqualTo(expectedTotals);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE kind = 'MERCHANT' AND balance_minor < 0", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(amount_minor), 0) FROM ledger_entries", BigDecimal.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT t.id FROM transfers t LEFT JOIN ledger_entries e ON e.transfer_id = t.id
                    GROUP BY t.id HAVING count(e.id) <> 2 OR sum(e.amount_minor) <> 0
                ) invalid
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT sum(e.amount_minor) OVER (PARTITION BY e.account_id ORDER BY e.id) AS running_balance
                    FROM ledger_entries e JOIN accounts a ON a.id = e.account_id WHERE a.kind = 'MERCHANT'
                ) history WHERE running_balance < 0
                """, Long.class)).isZero();
        assertThat(reconciliation.check().balanced()).isTrue();
    }
}
