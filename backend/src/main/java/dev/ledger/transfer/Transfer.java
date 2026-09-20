package dev.ledger.transfer;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "transfers")
public class Transfer {
    @Id
    private UUID id;
    @Column(name = "from_account_id", nullable = false)
    private UUID fromAccountId;
    @Column(name = "to_account_id", nullable = false)
    private UUID toAccountId;
    @Column(nullable = false, length = 3)
    private String currency;
    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransferKind kind;
    @Column(nullable = false, length = 16)
    private String status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Transfer() {}

    Transfer(UUID fromAccountId, UUID toAccountId, String currency, long amountMinor) {
        this.id = UUID.randomUUID();
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.currency = currency;
        this.amountMinor = amountMinor;
        this.kind = TransferKind.TRANSFER;
        this.status = "COMPLETED";
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getFromAccountId() { return fromAccountId; }
    public UUID getToAccountId() { return toAccountId; }
    public String getCurrency() { return currency; }
    public long getAmountMinor() { return amountMinor; }
    public TransferKind getKind() { return kind; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
