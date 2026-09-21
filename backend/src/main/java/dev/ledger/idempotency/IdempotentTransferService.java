package dev.ledger.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledger.account.AccountNotFoundException;
import dev.ledger.transfer.CreateTransferRequest;
import dev.ledger.transfer.TransferRejectedException;
import dev.ledger.transfer.TransferService;
import dev.ledger.webhook.OutboxService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotentTransferService {
    private final IdempotencyRepository keys;
    private final TransferService transfers;
    private final ObjectMapper mapper;
    private final OutboxService outbox;

    public IdempotentTransferService(IdempotencyRepository keys, TransferService transfers, ObjectMapper mapper, OutboxService outbox) {
        this.keys = keys;
        this.transfers = transfers;
        this.mapper = mapper;
        this.outbox = outbox;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public IdempotentResponse create(String key, CreateTransferRequest request) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IdempotencyException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key is required and must contain 1–128 letters, digits, dots, underscores, colons, or hyphens");
        }
        String hash = fingerprint(request);
        if (!keys.claim(key, hash)) {
            // A separate READ COMMITTED statement sees the winner's newly committed response.
            var stored = keys.find(key);
            if (!stored.requestHash().equals(hash)) {
                throw new IdempotencyException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used with a different transfer payload");
            }
            return stored.response();
        }

        IdempotentResponse response;
        try {
            var transfer = transfers.create(request);
            response = new IdempotentResponse(201, serialize(transfer), "application/json",
                    "/api/transfers/" + transfer.id(), transfer.id(), false);
        } catch (TransferRejectedException exception) {
            var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
            problem.setTitle("Transfer rejected");
            problem.setProperty("code", exception.getCode());
            response = rejection(problem);
            outbox.failed(key, request, exception.getCode(), exception.getMessage());
        } catch (AccountNotFoundException exception) {
            var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
            problem.setTitle("Account not found");
            response = rejection(problem);
            outbox.failed(key, request, "ACCOUNT_NOT_FOUND", exception.getMessage());
        }
        keys.complete(key, response);
        return response;
    }

    private IdempotentResponse rejection(ProblemDetail problem) {
        problem.setInstance(URI.create("/api/transfers"));
        return new IdempotentResponse(problem.getStatus(), serialize(problem), "application/problem+json", null, null, false);
    }

    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize the transfer response", exception);
        }
    }

    private String fingerprint(CreateTransferRequest request) {
        String canonical = request.fromAccountId() + "\n" + request.toAccountId() + "\n" + request.amountMinor();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
