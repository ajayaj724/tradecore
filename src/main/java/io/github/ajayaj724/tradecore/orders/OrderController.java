package io.github.ajayaj724.tradecore.orders;

import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
class OrderController {

    private final OrderService service;

    OrderController(OrderService service) {
        this.service = service;
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
                        idempotencyKey, request.symbol(), request.side(), request.price(), request.quantity()));
        return ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(order));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('TRADER')")
    ResponseEntity<OrderResponse> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        String account = Objects.requireNonNull(jwt.getClaimAsString("preferred_username"));
        // 202: cancellation is accepted for async processing; the order reaches CANCELLED via event.
        return ResponseEntity.accepted().body(OrderResponse.from(service.cancel(id, account, account)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('TRADER','OPS')")
    ResponseEntity<OrderResponse> get(
            Authentication authentication, @AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        String account = Objects.requireNonNull(jwt.getClaimAsString("preferred_username"));
        boolean isOps = authentication.getAuthorities().stream().anyMatch(a -> "ROLE_OPS".equals(a.getAuthority()));
        return ResponseEntity.ok(OrderResponse.from(service.findForViewer(id, account, isOps)));
    }
}
