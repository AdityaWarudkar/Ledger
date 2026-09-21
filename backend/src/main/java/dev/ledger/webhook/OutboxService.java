package dev.ledger.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledger.transfer.CreateTransferRequest;
import dev.ledger.transfer.TransferResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OutboxService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void completed(TransferResponse transfer) {
        publish("transfer.completed", transfer.id(), null, transfer,
                List.of(transfer.fromAccountId(), transfer.toAccountId()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void failed(String key, CreateTransferRequest request, String code, String message) {
        publish("transfer.failed", null, key,
                new FailedTransfer(request.fromAccountId(), request.toAccountId(), request.amountMinor().toString(), code, message),
                List.of(request.fromAccountId()));
    }

    private void publish(String type, UUID transferId, String key, Object data, List<UUID> accounts) {
        UUID eventId = UUID.randomUUID();
        String payload;
        try {
            payload = mapper.writeValueAsString(new Event(eventId, type, Instant.now(), data));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize webhook event", exception);
        }
        jdbc.update("INSERT INTO outbox_events (id, type, transfer_id, request_key, payload) VALUES (?, ?, ?, ?, ?)",
                eventId, type, transferId, key, payload);
        for (UUID account : accounts) {
            jdbc.update("""
                    INSERT INTO webhook_deliveries (id, event_id, endpoint_id)
                    SELECT gen_random_uuid(), ?, id FROM webhook_endpoints WHERE account_id = ?
                    ON CONFLICT (event_id, endpoint_id) DO NOTHING
                    """, eventId, account);
        }
    }

    public record Event(UUID id, String type, Instant createdAt, Object data) {}
    public record FailedTransfer(UUID fromAccountId, UUID toAccountId, String amountMinor, String code, String message) {}
}
