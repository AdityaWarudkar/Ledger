package dev.ledger.ledger;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/accounts/{id}/entries")
public class LedgerController {
    private final LedgerService ledger;

    public LedgerController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @GetMapping
    public LedgerService.LedgerPage list(@PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return ledger.forAccount(id, page, size);
    }
}
