package dev.ledger.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@Profile("local")
@RestController
@RequestMapping("/api/webhooks/test-receiver")
public class TestReceiverController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final long slowMs;

    public TestReceiverController(JdbcTemplate jdbc, ObjectMapper mapper,
            @Value("${ledger.webhooks.receiver-slow-ms:3000}") long slowMs) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.slowMs = slowMs;
    }

    @GetMapping("/behavior")
    public BehaviorRequest behavior() {
        return new BehaviorRequest(Behavior.valueOf(jdbc.queryForObject("SELECT behavior FROM test_receiver_settings WHERE id = 1", String.class)));
    }

    @PutMapping("/behavior")
    public BehaviorRequest behavior(@Valid @RequestBody BehaviorRequest request) {
        jdbc.update("UPDATE test_receiver_settings SET behavior = ? WHERE id = 1", request.behavior().name());
        return request;
    }

    @PostMapping
    public ResponseEntity<ReceiptResult> receive(@RequestHeader("X-Ledger-Endpoint-Id") UUID endpointId,
            @RequestHeader("X-Ledger-Event-Id") UUID eventId, @RequestHeader("X-Ledger-Timestamp") String timestamp,
            @RequestHeader("X-Ledger-Signature") String signature, @RequestBody String payload) throws InterruptedException {
        var secrets = jdbc.queryForList("SELECT signing_secret FROM webhook_endpoints WHERE id = ?", String.class, endpointId);
        if (secrets.isEmpty() || !WebhookSignature.verify(secrets.getFirst(), timestamp, signature, payload)) {
            throw new WebhookException(HttpStatus.UNAUTHORIZED, "Webhook signature is invalid or expired");
        }
        try {
            var body = mapper.readTree(payload);
            if (body == null || !eventId.toString().equals(body.path("id").asText())) {
                throw new WebhookException(HttpStatus.BAD_REQUEST, "Event ID does not match the signed payload");
            }
        } catch (JsonProcessingException exception) {
            throw new WebhookException(HttpStatus.BAD_REQUEST, "Invalid event JSON");
        }
        Behavior current = behavior().behavior();
        if (current == Behavior.FAIL) {
            return ResponseEntity.internalServerError().body(new ReceiptResult(eventId, false, false));
        }
        if (current == Behavior.SLOW) {
            Thread.sleep(slowMs);
        }
        // This insert represents the receiver's side effect. In a real consumer, commit its business work with it.
        int inserted = jdbc.update("""
                INSERT INTO test_receiver_receipts (endpoint_id, event_id, payload) VALUES (?, ?, ?)
                ON CONFLICT (endpoint_id, event_id) DO NOTHING
                """, endpointId, eventId, payload);
        if (inserted == 0) {
            String original = jdbc.queryForObject("SELECT payload FROM test_receiver_receipts WHERE endpoint_id = ? AND event_id = ?",
                    String.class, endpointId, eventId);
            if (!payload.equals(original)) {
                throw new WebhookException(HttpStatus.CONFLICT, "Event ID was already received with a different payload");
            }
        }
        return ResponseEntity.ok(new ReceiptResult(eventId, true, inserted == 0));
    }

    @GetMapping("/receipts")
    public List<Receipt> receipts(@RequestParam(required = false) UUID endpointId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return jdbc.query("""
                SELECT endpoint_id, event_id, received_at FROM test_receiver_receipts
                WHERE (?::uuid IS NULL OR endpoint_id = ?) ORDER BY received_at DESC, event_id DESC LIMIT ? OFFSET ?
                """, (row, index) -> new Receipt(row.getObject("endpoint_id", UUID.class), row.getObject("event_id", UUID.class),
                row.getTimestamp("received_at").toInstant()), endpointId, endpointId, size, (long) page * size);
    }

    public enum Behavior { HEALTHY, FAIL, SLOW }
    public record BehaviorRequest(@NotNull Behavior behavior) {}
    public record ReceiptResult(UUID eventId, boolean accepted, boolean duplicate) {}
    public record Receipt(UUID endpointId, UUID eventId, Instant receivedAt) {}
}
