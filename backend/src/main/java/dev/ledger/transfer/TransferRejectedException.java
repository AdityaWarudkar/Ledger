package dev.ledger.transfer;

public class TransferRejectedException extends RuntimeException {
    private final String code;

    public TransferRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() { return code; }
}
