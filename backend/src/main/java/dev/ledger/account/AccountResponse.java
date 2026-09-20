package dev.ledger.account;

import java.time.Instant;
import java.util.UUID;

public record AccountResponse(UUID id, String name, String currency, AccountStatus status, AccountKind kind,
                              String balanceMinor, Instant createdAt) {
    static AccountResponse from(Account account) {
        // JSON numbers cannot preserve every bigint value in a JavaScript client.
        return new AccountResponse(account.getId(), account.getName(), account.getCurrency(),
                account.getStatus(), account.getKind(), Long.toString(account.getBalanceMinor()), account.getCreatedAt());
    }
}
