package dev.ledger.ledger;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReconciliationController {
    private final ReconciliationService reconciliation;

    public ReconciliationController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @GetMapping("/api/system/reconciliation")
    public ReconciliationService.ReconciliationResult check() {
        return reconciliation.check();
    }
}
