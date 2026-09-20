package dev.ledger.transfer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record CreateTransferRequest(
        @NotNull(message = "Source account is required") UUID fromAccountId,
        @NotNull(message = "Destination account is required") UUID toAccountId,
        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be greater than zero") Long amountMinor
) {}
