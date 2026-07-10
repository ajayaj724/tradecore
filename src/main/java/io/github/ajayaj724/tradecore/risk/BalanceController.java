package io.github.ajayaj724.tradecore.risk;

import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/balances")
class BalanceController {

    private final RiskService risk;

    BalanceController(RiskService risk) {
        this.risk = risk;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TRADER','OPS')")
    ResponseEntity<CashBalance> balance(@AuthenticationPrincipal Jwt jwt) {
        String account = Objects.requireNonNull(jwt.getClaimAsString("preferred_username"));
        return ResponseEntity.ok(risk.balanceOf(account));
    }
}
