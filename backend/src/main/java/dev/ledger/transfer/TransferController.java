package dev.ledger.transfer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import dev.ledger.idempotency.IdempotentTransferService;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {
    private final TransferService transfers;
    private final IdempotentTransferService idempotentTransfers;

    public TransferController(TransferService transfers, IdempotentTransferService idempotentTransfers) {
        this.transfers = transfers;
        this.idempotentTransfers = idempotentTransfers;
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CreateTransferRequest request) {
        var response = idempotentTransfers.create(key, request);
        var result = ResponseEntity.status(response.status())
                .contentType(MediaType.parseMediaType(response.contentType()))
                .header("Idempotency-Replayed", Boolean.toString(response.replayed()));
        if (response.location() != null) {
            result.location(URI.create(response.location()));
        }
        return result.body(response.body());
    }

    @GetMapping("/{id}")
    public TransferService.TransferDetail get(@PathVariable UUID id) {
        return transfers.get(id);
    }

    @GetMapping
    public TransferService.TransferPage list(@RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) TransferKind kind,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return transfers.list(accountId, kind, page, size);
    }
}
