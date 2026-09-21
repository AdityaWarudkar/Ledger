package dev.ledger.webhook;

import org.springframework.http.HttpStatus;

public class WebhookException extends RuntimeException {
    private final HttpStatus status;

    public WebhookException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() { return status; }
}
