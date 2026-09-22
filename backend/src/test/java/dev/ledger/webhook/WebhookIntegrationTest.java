package dev.ledger.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ledger.account.AccountService;
import dev.ledger.account.CreateAccountRequest;
import dev.ledger.idempotency.IdempotentTransferService;
import dev.ledger.transfer.CreateTransferRequest;
import dev.ledger.transfer.TransferService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ledger.webhooks.request-timeout=500ms", "ledger.webhooks.lease-duration=3s",
        "ledger.webhooks.base-backoff=100ms", "ledger.webhooks.max-backoff=500ms",
        "ledger.webhooks.max-attempts=3", "ledger.webhooks.receiver-slow-ms=1500"
})
@ActiveProfiles("local")
@Testcontainers
class WebhookIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired AccountService accounts;
    @Autowired TransferService transfers;
    @Autowired IdempotentTransferService idempotency;
    @Autowired WebhookService endpoints;
    @Autowired DeliveryStore deliveries;
    @Autowired WebhookWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired TestRestTemplate rest;
    @LocalServerPort int port;

    UUID source;
    UUID destination;
    String key;
    WebhookService.RegisteredEndpoint endpoint;

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE webhook_deliveries SET status = 'DEAD_LETTER', lease_token = NULL, lease_until = NULL WHERE status <> 'DELIVERED'");
        behavior("HEALTHY");
        source = accounts.create(new CreateAccountRequest("Harbour Coffee", "INR")).id();
        destination = accounts.create(new CreateAccountRequest("Monsoon Supply Co.", "INR")).id();
        transfers.create(new CreateTransferRequest(UUID.fromString("018f0000-0000-7000-8000-000000000001"), source, 10000L));
        endpoint = endpoints.register(source, "http://localhost:" + port + "/api/webhooks/test-receiver");
        key = UUID.randomUUID().toString();
    }

    @Test
    void completedTransferCreatesOneEventForBothAccountsAndReplayCreatesNothing() {
        var other = endpoints.register(destination, endpoint.url());
        var request = new CreateTransferRequest(source, destination, 100L);
        var original = idempotency.create(key, request);
        var replay = idempotency.create(key, request);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body()).isEqualTo(original.body());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE transfer_id = ?", Long.class, original.transferId())).isEqualTo(1);
        var first = delivery();
        var second = deliveries.list(other.id(), null, 0, 25).getFirst();
        assertThat(second.eventId()).isEqualTo(first.eventId());
        assertThat(first.type()).isEqualTo("transfer.completed");
        assertThat(worker.processOne()).isTrue();
        assertThat(worker.processOne()).isTrue();
        assertThat(deliveries.get(first.id()).delivery().status()).isEqualTo("DELIVERED");
        assertThat(deliveries.get(second.id()).delivery().status()).isEqualTo("DELIVERED");
    }

    @Test
    void businessRejectionIsCommittedAsOneFailedEventWithoutLedgerEntries() {
        var request = new CreateTransferRequest(source, destination, 10001L);
        assertThat(idempotency.create(key, request).status()).isEqualTo(422);
        assertThat(idempotency.create(key, request).replayed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE request_key = ?", Long.class, key)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers WHERE from_account_id = ?", Long.class, source)).isZero();
        assertThat(delivery().type()).isEqualTo("transfer.failed");
        assertThat(worker.processOne()).isTrue();
        assertThat(deliveries.get(delivery().id()).delivery().status()).isEqualTo("DELIVERED");
    }

    @Test
    void transactionRollbackRemovesEventDeliveriesAndKeyTogether() {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            send();
            assertThat(deliveries.list(endpoint.id(), null, 0, 25)).hasSize(1);
            status.setRollbackOnly();
        });
        assertThat(deliveries.list(endpoint.id(), null, 0, 25)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE key = ?", Long.class, key)).isZero();
        assertThat(jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?", Long.class, source)).isEqualTo(10000);
        send();
        assertThat(deliveries.list(endpoint.id(), null, 0, 25)).hasSize(1);
    }

    @Test
    void anOutboxWriteFailureRollsBackTheMoneyAndCanBeRetried() {
        jdbc.execute("""
                CREATE FUNCTION test_fail_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.payload::jsonb->'data'->>'fromAccountId' = '%s' THEN
                        RAISE EXCEPTION 'forced outbox failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """.formatted(source));
        jdbc.execute("CREATE TRIGGER test_fail_outbox BEFORE INSERT ON outbox_events FOR EACH ROW EXECUTE FUNCTION test_fail_outbox()");
        try {
            assertThatThrownBy(this::send).hasStackTraceContaining("forced outbox failure");
        } finally {
            jdbc.execute("DROP TRIGGER test_fail_outbox ON outbox_events");
            jdbc.execute("DROP FUNCTION test_fail_outbox()");
        }
        assertThat(jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?", Long.class, source)).isEqualTo(10000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers WHERE from_account_id = ?", Long.class, source)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE key = ?", Long.class, key)).isZero();
        assertThat(deliveries.list(endpoint.id(), null, 0, 25)).isEmpty();
        send();
        assertThat(deliveries.list(endpoint.id(), null, 0, 25)).hasSize(1);
    }

    @Test
    void receiverFailureThenRecoveryDeliversAndManualReplayIsDeduplicated() {
        double successes = metrics.counter("ledger.webhook.attempts", "outcome", "success").count();
        double failures = metrics.counter("ledger.webhook.attempts", "outcome", "failure").count();
        double retries = metrics.counter("ledger.webhook.retries").count();
        send();
        UUID id = delivery().id();
        behavior("FAIL");
        assertThat(worker.processOne()).isTrue();
        var failed = deliveries.get(id);
        assertThat(failed.delivery().status()).isEqualTo("RETRY");
        assertThat(failed.attempts().getFirst().statusCode()).isEqualTo(500);
        assertThat(failed.attempts().getFirst().latencyMs()).isNotNegative();
        assertThat(failed.attempts().getFirst().nextAttemptAt()).isAfter(failed.attempts().getFirst().startedAt());
        behavior("HEALTHY");
        makeDue(id);
        assertThat(worker.processOne()).isTrue();
        assertThat(deliveries.get(id).delivery().status()).isEqualTo("DELIVERED");
        assertThat(receipts()).isEqualTo(1);
        deliveries.replay(id);
        assertThat(worker.processOne()).isTrue();
        assertThat(receipts()).isEqualTo(1);
        assertThat(deliveries.get(id).attempts()).hasSize(3);
        assertThat(deliveries.get(id).delivery().eventId()).isEqualTo(failed.delivery().eventId());
        assertThat(metrics.counter("ledger.webhook.attempts", "outcome", "success").count()).isEqualTo(successes + 2);
        assertThat(metrics.counter("ledger.webhook.attempts", "outcome", "failure").count()).isEqualTo(failures + 1);
        assertThat(metrics.counter("ledger.webhook.retries").count()).isEqualTo(retries + 1);
    }

    @Test
    void maxAttemptsDeadLettersAndReplayPreservesHistory() {
        send();
        UUID id = delivery().id();
        behavior("FAIL");
        for (int i = 0; i < 3; i++) {
            makeDue(id);
            assertThat(worker.processOne()).isTrue();
        }
        assertThat(deliveries.get(id).delivery().status()).isEqualTo("DEAD_LETTER");
        assertThat(worker.processOne()).isFalse();
        assertThat(deliveries.get(id).attempts()).hasSize(3);
        behavior("HEALTHY");
        var replay = rest.postForEntity("/api/webhooks/deliveries/" + id + "/replay", null, JsonNode.class);
        assertThat(replay.getStatusCode().value()).isEqualTo(202);
        assertThat(worker.processOne()).isTrue();
        var result = deliveries.get(id);
        assertThat(result.delivery().status()).isEqualTo("DELIVERED");
        assertThat(result.delivery().attemptCount()).isEqualTo(4);
        assertThat(result.delivery().cycleAttempts()).isEqualTo(1);
        assertThat(result.attempts()).hasSize(4);
    }

    @Test
    void aSlowReceiverCanAcceptAfterTimeoutAndDeduplicatesTheRetry() {
        send();
        UUID id = delivery().id();
        behavior("SLOW");
        assertThat(worker.processOne()).isTrue();
        var timedOut = deliveries.get(id);
        assertThat(timedOut.delivery().status()).isEqualTo("RETRY");
        assertThat(timedOut.attempts().getFirst().statusCode()).isNull();
        assertThat(timedOut.attempts().getFirst().error()).contains("Timeout");
        await().atMost(5, TimeUnit.SECONDS).until(() -> receipts() == 1);
        behavior("HEALTHY");
        makeDue(id);
        worker.processOne();
        assertThat(deliveries.get(id).delivery().status()).isEqualTo("DELIVERED");
        assertThat(receipts()).isEqualTo(1);
    }

    @Test
    void receiverRejectsExpiredTamperedAndMismatchedSignatures() {
        send();
        var data = deliveries.get(delivery().id());
        long now = Instant.now().getEpochSecond();
        assertThat(receive(data, now - 600, data.payload(), data.delivery().eventId()).getStatusCode().value()).isEqualTo(401);
        assertThat(receive(data, now + 600, data.payload(), data.delivery().eventId()).getStatusCode().value()).isEqualTo(401);
        assertThat(receive(data, now, data.payload() + " ", data.delivery().eventId()).getStatusCode().value()).isEqualTo(401);
        assertThat(receive(data, now, data.payload(), UUID.randomUUID()).getStatusCode().value()).isEqualTo(400);
        assertThat(receipts()).isZero();
        var accepted = receive(data, now, data.payload(), data.delivery().eventId());
        assertThat(accepted.getStatusCode().value()).isEqualTo(200);
        var duplicate = receive(data, now, data.payload(), data.delivery().eventId());
        assertThat(duplicate.getBody().get("duplicate").asBoolean()).isTrue();
        assertThat(receipts()).isEqualTo(1);
    }

    @Test
    void expiredLeaseIsRecoveredAndStaleWorkerCannotOverwriteTheNewAttempt() {
        send();
        var first = deliveries.claim().orElseThrow();
        assertThat(deliveries.claim()).isEmpty();
        expire(first.id());
        var second = deliveries.claim().orElseThrow();
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(second.attemptNumber()).isEqualTo(2);
        assertThat(deliveries.finish(first, 200, 10, null, 100)).isFalse();
        assertThat(deliveries.finish(second, 200, 10, null, 100)).isTrue();
        var history = deliveries.get(first.id());
        assertThat(history.attempts().getFirst().error()).contains("outcome unknown");
        assertThat(history.delivery().status()).isEqualTo("DELIVERED");
    }

    @Test
    void repeatedWorkerCrashesAlsoRespectTheAttemptLimit() {
        send();
        UUID id = delivery().id();
        for (int i = 0; i < 3; i++) {
            assertThat(deliveries.claim()).isPresent();
            expire(id);
        }
        assertThat(deliveries.claim()).isEmpty();
        assertThat(deliveries.get(id).delivery().status()).isEqualTo("DEAD_LETTER");
        assertThat(deliveries.get(id).attempts()).hasSize(3).allSatisfy(attempt -> assertThat(attempt.error()).contains("Lease expired"));
    }

    @Test
    void twoWorkersCannotClaimTheSameDelivery() throws Exception {
        send();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return deliveries.claim(); });
            var second = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return deliveries.claim(); });
            var results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertThat(results.stream().filter(java.util.Optional::isPresent).count()).isEqualTo(1);
        }
        assertThat(deliveries.get(delivery().id()).attempts()).hasSize(1);
    }

    @Test
    void endpointsAndDeliveryApisValidateInputAndDoNotExposeSecrets() {
        var list = rest.getForEntity("/api/webhooks/endpoints", JsonNode.class);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        list.getBody().forEach(item -> assertThat(item.has("signingSecret")).isFalse());
        assertThat(rest.postForEntity("/api/webhooks/endpoints", Map.of("accountId", source, "url", endpoint.url()), JsonNode.class)
                .getStatusCode().value()).isEqualTo(409);
        for (String url : List.of("https://unlisted.invalid/webhook", "ftp://localhost/file", "http://user:pass@localhost/", "http://localhost/#fragment")) {
            assertThat(rest.postForEntity("/api/webhooks/endpoints", Map.of("accountId", destination, "url", url), JsonNode.class)
                    .getStatusCode().value()).isEqualTo(400);
        }
        var created = rest.postForEntity("/api/webhooks/endpoints", Map.of("accountId", destination, "url", endpoint.url()), JsonNode.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody().get("signingSecret").asText()).hasSize(64);
        send();
        UUID id = delivery().id();
        assertThat(rest.getForEntity("/api/webhooks/deliveries?endpointId=" + endpoint.id(), JsonNode.class).getBody()).hasSize(1);
        assertThat(rest.getForEntity("/api/webhooks/deliveries/" + id, JsonNode.class).getStatusCode().value()).isEqualTo(200);
        assertThat(rest.postForEntity("/api/webhooks/deliveries/" + id + "/replay", null, JsonNode.class).getStatusCode().value()).isEqualTo(409);
        assertThat(rest.getForEntity("/api/webhooks/deliveries?size=101", JsonNode.class).getStatusCode().value()).isEqualTo(400);
        assertThat(rest.getForEntity("/api/webhooks/deliveries/" + UUID.randomUUID(), JsonNode.class).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void signatureMatchesAnIndependentHmacVectorAndBackoffStaysBounded() {
        assertThat(WebhookSignature.sign("secret", 1700000000L, "{}"))
                .isEqualTo("v1=b8569b78799ff9e3cbff0fc2d63a33a2b57f3282abd07c37ae5e8e7d79a5f163");
        assertThat(WebhookSignature.verify("secret", Long.toString(Long.MIN_VALUE), "v1=" + "a".repeat(64), "{}")).isFalse();
        for (int i = 0; i < 100; i++) {
            assertThat(worker.backoffMillis(1)).isBetween(50L, 100L);
            assertThat(worker.backoffMillis(2)).isBetween(100L, 200L);
            assertThat(worker.backoffMillis(10)).isBetween(250L, 500L);
        }
    }

    private ResponseEntity<JsonNode> receive(DeliveryStore.DeliveryDetail data, long timestamp, String body, UUID eventId) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Ledger-Endpoint-Id", endpoint.id().toString());
        headers.set("X-Ledger-Event-Id", eventId.toString());
        headers.set("X-Ledger-Timestamp", Long.toString(timestamp));
        headers.set("X-Ledger-Signature", WebhookSignature.sign(endpoint.signingSecret(), timestamp, data.payload()));
        return rest.postForEntity("/api/webhooks/test-receiver", new HttpEntity<>(body, headers), JsonNode.class);
    }

    private void behavior(String behavior) {
        rest.put("/api/webhooks/test-receiver/behavior", Map.of("behavior", behavior));
    }

    private void send() {
        assertThat(idempotency.create(key, new CreateTransferRequest(source, destination, 100L)).status()).isEqualTo(201);
    }

    private DeliveryStore.Delivery delivery() {
        return deliveries.list(endpoint.id(), null, 0, 25).getFirst();
    }

    private long receipts() {
        return jdbc.queryForObject("SELECT count(*) FROM test_receiver_receipts WHERE endpoint_id = ?", Long.class, endpoint.id());
    }

    private void makeDue(UUID id) {
        jdbc.update("UPDATE webhook_deliveries SET next_attempt_at = clock_timestamp() - interval '1 second' WHERE id = ?", id);
    }

    private void expire(UUID id) {
        jdbc.update("UPDATE webhook_deliveries SET lease_until = clock_timestamp() - interval '1 second' WHERE id = ?", id);
    }
}
