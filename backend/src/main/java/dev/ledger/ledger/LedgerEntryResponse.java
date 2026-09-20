package dev.ledger.ledger;

import java.time.Instant;
import java.util.UUID;

public record LedgerEntryResponse(String id, UUID transferId, UUID accountId, UUID counterpartyId,
                                  String counterpartyName, String currency, String direction,
                                  String amountMinor, String runningBalanceMinor, Instant createdAt) {}
