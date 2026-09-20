package dev.ledger.idempotency;

import java.util.UUID;

public record IdempotentResponse(int status, String body, String contentType, String location,
                                 UUID transferId, boolean replayed) {}
