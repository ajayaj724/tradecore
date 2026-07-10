package io.github.ajayaj724.tradecore.orders;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
class OrderController {

    private final OrderService service;
    private final CancelApprovalService approvals;

    OrderController(OrderService service, CancelApprovalService approvals) {
        this.service = service;
        this.approvals = approvals;
    }

    @PostMapping
    @PreAuthorize("hasRole('TRADER')")
    ResponseEntity<OrderResponse> submit(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SubmitOrderRequest request) {
        String account = Objects.requireNonNull(jwt.getClaimAsString("preferred_username"));
        Order order = service.submit(
                account,
                account,
                new SubmitOrderCommand(
                        idempotencyKey,
                        request.symbol(),
                        request.side(),
                        request.price(),
                        request.quantity(),
                        request.typeOrDefault()));
        return ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(order));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('TRADER','OPS')")
    ResponseEntity<OrderResponse> cancel(
            Authentication authentication, @AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        String principal = Objects.requireNonNull(jwt.getClaimAsString("preferred_username"));
        // An ops cancel is on someone else's behalf, so it parks a four-eyes request
        // (ADR-0024); a trader's self-cancel executes immediately. 202 either way — the
        // terminal CANCELLED status always arrives via events.
        Order order = isOps(authentication)
                ? approvals.request(id, principal)
                : service.cancel(id, principal, principal, false);
        return ResponseEntity.accepted().body(OrderResponse.from(order));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TRADER','OPS','ADMIN')")
    ResponseEntity<List<OrderResponse>> list(
            Authentication authentication,
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "scope", defaultValue = "own") String scope,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        String account = Objects.requireNonNull(jwt.getClaimAsString("preferred_username"));
        int capped = Math.clamp(limit, 1, 200);
        List<Order> rows;
        if ("all".equals(scope)) {
            if (!canViewAllAccounts(authentication)) {
                throw new AccessDeniedException("scope=all requires the OPS or ADMIN role");
            }
            rows = service.historyAllAccounts(capped);
        } else {
            rows = service.history(account, capped);
        }
        return ResponseEntity.ok(rows.stream().map(OrderResponse::from).toList());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('TRADER','OPS','ADMIN')")
    ResponseEntity<OrderResponse> get(
            Authentication authentication, @AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        String account = Objects.requireNonNull(jwt.getClaimAsString("preferred_username"));
        return ResponseEntity.ok(
                OrderResponse.from(service.findForViewer(id, account, canViewAllAccounts(authentication))));
    }

    /** OPS acts (cancel on behalf); ADMIN observes. Both read across accounts (ADR-0023). */
    private static boolean isOps(Authentication authentication) {
        return hasRole(authentication, "ROLE_OPS");
    }

    private static boolean canViewAllAccounts(Authentication authentication) {
        return hasRole(authentication, "ROLE_OPS") || hasRole(authentication, "ROLE_ADMIN");
    }

    private static boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream().anyMatch(a -> role.equals(a.getAuthority()));
    }
}
