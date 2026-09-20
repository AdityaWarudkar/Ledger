package dev.ledger.account;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {
    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountKind kind;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Account() {}

    Account(String name, String currency) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.currency = currency;
        this.status = AccountStatus.ACTIVE;
        this.kind = AccountKind.MERCHANT;
        this.balanceMinor = 0;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getCurrency() { return currency; }
    public AccountStatus getStatus() { return status; }
    public AccountKind getKind() { return kind; }
    public long getBalanceMinor() { return balanceMinor; }
    public Instant getCreatedAt() { return createdAt; }

    public void debit(long amountMinor) {
        if (amountMinor <= 0 || amountMinor > balanceMinor) {
            throw new IllegalArgumentException("Debit exceeds the available balance");
        }
        balanceMinor -= amountMinor;
    }

    public void credit(long amountMinor) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Credit must be positive");
        }
        balanceMinor = Math.addExact(balanceMinor, amountMinor);
    }
}
