package dev.ledger.transfer;

import java.util.UUID;

public class TransferNotFoundException extends RuntimeException {
    public TransferNotFoundException(UUID id) {
        super("Transfer " + id + " was not found");
    }
}
