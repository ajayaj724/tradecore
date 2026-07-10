package io.github.ajayaj724.tradecore.reconciliation;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reconciliation")
class ReconciliationController {

    private final ReconciliationService reconciliation;

    ReconciliationController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<ReconciliationReport> report() {
        return ResponseEntity.ok(reconciliation.report());
    }
}
