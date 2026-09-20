package dev.ledger.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 120, message = "Name must be 120 characters or fewer") String name,
        @NotBlank(message = "Currency is required")
        @Pattern(regexp = "INR|USD|EUR|GBP", message = "Currency must be INR, USD, EUR, or GBP") String currency
) {}
