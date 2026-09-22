package dev.ledger.transfer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

public record CreateTransferRequest(
        @NotNull(message = "Source account is required") UUID fromAccountId,
        @NotNull(message = "Destination account is required") UUID toAccountId,
        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be greater than zero")
        @Schema(type = "string", implementation = String.class, pattern = "^[1-9][0-9]*$", example = "12500",
                description = "Positive integer minor units, at most 9223372036854775807. Send as a string to preserve precision.")
        Long amountMinor
) {}
