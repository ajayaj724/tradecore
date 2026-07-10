package io.github.ajayaj724.tradecore.orders;

import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cancel-requests")
class CancelRequestController {

    private final CancelApprovalService approvals;

    CancelRequestController(CancelApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    ResponseEntity<List<CancelRequestResponse>> pending() {
        return ResponseEntity.ok(approvals.pending());
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('OPS')")
    ResponseEntity<Void> approve(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        approvals.approve(id, Objects.requireNonNull(jwt.getClaimAsString("preferred_username")));
        // 202: the approval is recorded; the cancellation itself completes via events.
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/decline")
    @PreAuthorize("hasRole('OPS')")
    ResponseEntity<Void> decline(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        approvals.decline(id, Objects.requireNonNull(jwt.getClaimAsString("preferred_username")));
        return ResponseEntity.ok().build();
    }
}
