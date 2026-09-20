package dev.ledger.transfer;

import java.time.Instant;
import java.util.UUID;

public record TransferResponse(UUID id, UUID fromAccountId, UUID toAccountId, String currency,
                               String amountMinor, TransferKind kind, String status, Instant createdAt) {
    static TransferResponse from(Transfer transfer) {
        return new TransferResponse(transfer.getId(), transfer.getFromAccountId(), transfer.getToAccountId(),
                transfer.getCurrency(), Long.toString(transfer.getAmountMinor()), transfer.getKind(),
                transfer.getStatus(), transfer.getCreatedAt());
    }
}
