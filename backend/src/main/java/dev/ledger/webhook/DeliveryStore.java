package dev.ledger.webhook;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DeliveryStore {
    private final JdbcTemplate jdbc;
    private final WebhookProperties properties;

    public DeliveryStore(JdbcTemplate jdbc, WebhookProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Transactional
    public Optional<Claim> claim() {
        var candidates = jdbc.query("""
                SELECT id, status, attempt_count, cycle_attempts FROM webhook_deliveries
                WHERE (status IN ('PENDING', 'RETRY') AND next_attempt_at <= clock_timestamp())
                   OR (status = 'PROCESSING' AND lease_until <= clock_timestamp())
                ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (row, index) -> new Candidate(row.getObject("id", UUID.class), row.getString("status"),
                row.getInt("attempt_count"), row.getInt("cycle_attempts")));
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        var candidate = candidates.getFirst();
        if (candidate.status().equals("PROCESSING")) {
            jdbc.update("""
                    UPDATE webhook_attempts SET finished_at = clock_timestamp(), error = 'Lease expired; receiver outcome unknown'
                    WHERE delivery_id = ? AND attempt_number = ? AND finished_at IS NULL
                    """, candidate.id(), candidate.attempts());
        }
        if (candidate.cycleAttempts() >= properties.maxAttempts()) {
            jdbc.update("UPDATE webhook_deliveries SET status = 'DEAD_LETTER', lease_token = NULL, lease_until = NULL WHERE id = ?",
                    candidate.id());
            return Optional.empty();
        }
        UUID token = UUID.randomUUID();
        jdbc.update("""
                UPDATE webhook_deliveries SET status = 'PROCESSING', lease_token = ?,
                       lease_until = clock_timestamp() + (? * interval '1 millisecond'),
                       attempt_count = attempt_count + 1, cycle_attempts = cycle_attempts + 1
                WHERE id = ?
                """, token, properties.leaseDuration().toMillis(), candidate.id());
        jdbc.update("INSERT INTO webhook_attempts (delivery_id, attempt_number) VALUES (?, ?)", candidate.id(), candidate.attempts() + 1);
        return Optional.of(jdbc.queryForObject("""
                SELECT d.id, d.event_id, d.endpoint_id, d.attempt_count, d.cycle_attempts, e.payload, w.url, w.signing_secret
                  FROM webhook_deliveries d JOIN outbox_events e ON e.id = d.event_id
                  JOIN webhook_endpoints w ON w.id = d.endpoint_id WHERE d.id = ?
                """, (row, index) -> new Claim(row.getObject("id", UUID.class), token, row.getObject("event_id", UUID.class),
                row.getObject("endpoint_id", UUID.class), row.getInt("attempt_count"), row.getInt("cycle_attempts"),
                row.getString("payload"), row.getString("url"), row.getString("signing_secret")), candidate.id()));
    }

    @Transactional
    public boolean finish(Claim claim, Integer statusCode, long latencyMs, String error, long backoffMs) {
        var owned = jdbc.queryForList("""
                SELECT id FROM webhook_deliveries WHERE id = ? AND lease_token = ? AND status = 'PROCESSING' FOR UPDATE
                """, UUID.class, claim.id(), claim.token());
        if (owned.isEmpty()) {
            return false;
        }
        boolean delivered = statusCode != null && statusCode >= 200 && statusCode < 300;
        String state = delivered ? "DELIVERED" : claim.cycleAttempts() >= properties.maxAttempts() ? "DEAD_LETTER" : "RETRY";
        Timestamp next = state.equals("RETRY") ? jdbc.queryForObject(
                "SELECT clock_timestamp() + (? * interval '1 millisecond')", Timestamp.class, backoffMs) : null;
        jdbc.update("""
                UPDATE webhook_attempts SET finished_at = clock_timestamp(), status_code = ?, latency_ms = ?, error = ?, next_attempt_at = ?
                WHERE delivery_id = ? AND attempt_number = ?
                """, statusCode, latencyMs, error, next, claim.id(), claim.attemptNumber());
        jdbc.update("""
                UPDATE webhook_deliveries SET status = ?, lease_token = NULL, lease_until = NULL,
                       next_attempt_at = coalesce(?, next_attempt_at), delivered_at = CASE WHEN ? THEN clock_timestamp() ELSE NULL END
                WHERE id = ?
                """, state, next, delivered, claim.id());
        return true;
    }

    @Transactional
    public Delivery replay(UUID id) {
        var states = jdbc.queryForList("SELECT status FROM webhook_deliveries WHERE id = ? FOR UPDATE", String.class, id);
        if (states.isEmpty()) {
            throw new WebhookException(HttpStatus.NOT_FOUND, "Webhook delivery was not found");
        }
        if (!List.of("DEAD_LETTER", "DELIVERED").contains(states.getFirst())) {
            throw new WebhookException(HttpStatus.CONFLICT, "Only delivered or dead-letter deliveries can be replayed");
        }
        jdbc.update("""
                UPDATE webhook_deliveries SET status = 'PENDING', cycle_attempts = 0, next_attempt_at = clock_timestamp(), delivered_at = NULL
                WHERE id = ?
                """, id);
        return get(id).delivery();
    }

    public List<Delivery> list(UUID endpointId, String status, int page, int size) {
        return jdbc.query("""
                SELECT d.*, e.type FROM webhook_deliveries d JOIN outbox_events e ON e.id = d.event_id
                WHERE (?::uuid IS NULL OR d.endpoint_id = ?) AND (?::text IS NULL OR d.status = ?)
                ORDER BY d.created_at DESC, d.id DESC LIMIT ? OFFSET ?
                """, this::delivery, endpointId, endpointId, status, status, size, (long) page * size);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public DeliveryDetail get(UUID id) {
        var deliveries = jdbc.query("""
                SELECT d.*, e.type FROM webhook_deliveries d JOIN outbox_events e ON e.id = d.event_id WHERE d.id = ?
                """, this::delivery, id);
        if (deliveries.isEmpty()) {
            throw new WebhookException(HttpStatus.NOT_FOUND, "Webhook delivery was not found");
        }
        var attempts = jdbc.query("SELECT * FROM webhook_attempts WHERE delivery_id = ? ORDER BY attempt_number",
                (row, index) -> new Attempt(row.getInt("attempt_number"), instant(row, "started_at"), instant(row, "finished_at"),
                        row.getObject("status_code", Integer.class), row.getObject("latency_ms", Long.class),
                        row.getString("error"), instant(row, "next_attempt_at")), id);
        String payload = jdbc.queryForObject("SELECT payload FROM outbox_events WHERE id = ?", String.class, deliveries.getFirst().eventId());
        return new DeliveryDetail(deliveries.getFirst(), attempts, payload);
    }

    private Delivery delivery(ResultSet row, int index) throws SQLException {
        return new Delivery(row.getObject("id", UUID.class), row.getObject("event_id", UUID.class), row.getObject("endpoint_id", UUID.class),
                row.getString("type"), row.getString("status"), row.getInt("attempt_count"), row.getInt("cycle_attempts"),
                List.of("PENDING", "RETRY").contains(row.getString("status")) ? instant(row, "next_attempt_at") : null,
                instant(row, "lease_until"), instant(row, "delivered_at"), instant(row, "created_at"));
    }

    private static Instant instant(ResultSet row, String field) throws SQLException {
        Timestamp value = row.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }

    private record Candidate(UUID id, String status, int attempts, int cycleAttempts) {}
    public record Claim(UUID id, UUID token, UUID eventId, UUID endpointId, int attemptNumber, int cycleAttempts,
                        String payload, String url, String secret) {}
    public record Delivery(UUID id, UUID eventId, UUID endpointId, String type, String status, int attemptCount,
                           int cycleAttempts, Instant nextAttemptAt, Instant leaseUntil, Instant deliveredAt, Instant createdAt) {}
    public record Attempt(int number, Instant startedAt, Instant finishedAt, Integer statusCode, Long latencyMs,
                          String error, Instant nextAttemptAt) {}
    public record DeliveryDetail(Delivery delivery, List<Attempt> attempts, String payload) {}
}
