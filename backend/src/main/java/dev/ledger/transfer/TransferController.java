package dev.ledger.transfer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {
    private final TransferService transfers;

    public TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> create(@Valid @RequestBody CreateTransferRequest request) {
        var transfer = transfers.create(request);
        return ResponseEntity.created(URI.create("/api/transfers/" + transfer.id())).body(transfer);
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
