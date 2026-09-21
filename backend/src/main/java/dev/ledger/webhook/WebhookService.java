package dev.ledger.webhook;

import dev.ledger.account.AccountNotFoundException;
import dev.ledger.account.AccountRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookService {
    private final JdbcTemplate jdbc;
    private final AccountRepository accounts;
    private final WebhookUrlPolicy urls;

    public WebhookService(JdbcTemplate jdbc, AccountRepository accounts, WebhookUrlPolicy urls) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.urls = urls;
    }

    @Transactional
    public RegisteredEndpoint register(UUID accountId, String url) {
        urls.validate(url);
        if (!accounts.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }
        UUID id = UUID.randomUUID();
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String secret = HexFormat.of().formatHex(bytes);
        int count = jdbc.update("""
                INSERT INTO webhook_endpoints (id, account_id, url, signing_secret) VALUES (?, ?, ?, ?)
                ON CONFLICT (account_id) DO NOTHING
                """, id, accountId, url, secret);
        if (count == 0) {
            throw new WebhookException(HttpStatus.CONFLICT, "This account already has a webhook endpoint");
        }
        return new RegisteredEndpoint(id, accountId, url, secret);
    }

    public List<Endpoint> list(int page, int size) {
        return jdbc.query("SELECT id, account_id, url, created_at FROM webhook_endpoints ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                (row, index) -> new Endpoint(row.getObject("id", UUID.class), row.getObject("account_id", UUID.class),
                        row.getString("url"), row.getTimestamp("created_at").toInstant()), size, (long) page * size);
    }

    public record RegisteredEndpoint(UUID id, UUID accountId, String url, String signingSecret) {}
    public record Endpoint(UUID id, UUID accountId, String url, Instant createdAt) {}
}
