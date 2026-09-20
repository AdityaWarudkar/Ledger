package dev.ledger.idempotency;

import java.util.UUID;
import java.sql.SQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {
    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean claim(String key, String requestHash) {
        String previousTimeout = jdbc.queryForObject("SELECT current_setting('lock_timeout')", String.class);
        jdbc.queryForObject("SELECT set_config('lock_timeout', '5s', true)", String.class);
        int inserted;
        try {
            // A competing insert waits for the first transaction to commit or roll back.
            inserted = jdbc.update("""
                    INSERT INTO idempotency_keys (key, request_hash) VALUES (?, ?)
                    ON CONFLICT (key) DO NOTHING
                    """, key, requestHash);
        } catch (DataAccessException exception) {
            if (!(exception.getMostSpecificCause() instanceof SQLException sql) || !"55P03".equals(sql.getSQLState())) {
                throw exception;
            }
            // PostgreSQL has aborted this transaction; do not attempt another statement in it.
            throw new IdempotencyException(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS",
                    "A request with this key is still processing. Retry with the same key and payload");
        }
        jdbc.queryForObject("SELECT set_config('lock_timeout', ?, true)", String.class, previousTimeout);
        return inserted == 1;
    }

    public StoredRequest find(String key) {
        return jdbc.queryForObject("""
                SELECT request_hash, response_status, response_body, response_content_type, response_location, transfer_id
                  FROM idempotency_keys WHERE key = ?
                """, (row, index) -> new StoredRequest(row.getString("request_hash"),
                new IdempotentResponse(row.getInt("response_status"), row.getString("response_body"),
                        row.getString("response_content_type"), row.getString("response_location"),
                        row.getObject("transfer_id", UUID.class), true)), key);
    }

    public void complete(String key, IdempotentResponse response) {
        int updated = jdbc.update("""
                UPDATE idempotency_keys
                   SET response_status = ?, response_body = ?, response_content_type = ?, response_location = ?, transfer_id = ?
                 WHERE key = ? AND response_status IS NULL
                """, response.status(), response.body(), response.contentType(), response.location(), response.transferId(), key);
        if (updated != 1) {
            throw new IllegalStateException("Idempotency claim was not available for completion");
        }
    }

    public record StoredRequest(String requestHash, IdempotentResponse response) {}
}
