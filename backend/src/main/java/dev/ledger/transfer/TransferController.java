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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

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
    @Operation(summary = "Create or replay a transfer", description = "The same key and payload returns the original status and body. "
            + "An unknown outcome must be retried with the same key. A new key represents a new movement.")
    @ApiResponse(responseCode = "201", description = "Created, or replay of the original creation",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = TransferResponse.class)),
            headers = {@Header(name = "Idempotency-Replayed", schema = @Schema(type = "boolean")),
                    @Header(name = "Location", schema = @Schema(type = "string"))})
    @ApiResponse(responseCode = "400", description = "Invalid request or missing key", content = @Content(mediaType = "application/problem+json"))
    @ApiResponse(responseCode = "404", description = "Account not found; response is cached", content = @Content(mediaType = "application/problem+json"))
    @ApiResponse(responseCode = "409", description = "Key is in progress; retry the same request after one second",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer", example = "1")),
            content = @Content(mediaType = "application/problem+json"))
    @ApiResponse(responseCode = "422", description = "Business rejection or key reused with a different payload", content = @Content(mediaType = "application/problem+json"))
    public ResponseEntity<String> create(@Parameter(required = true, description = "1–128 letters, digits, dots, underscores, colons or hyphens", example = "demo-transfer-001")
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
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
