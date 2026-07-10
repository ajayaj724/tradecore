package io.github.ajayaj724.tradecore.portfolio;

import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/positions")
class PositionController {

    private final PortfolioService portfolio;

    PositionController(PortfolioService portfolio) {
        this.portfolio = portfolio;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TRADER','OPS')")
    ResponseEntity<List<PositionResponse>> positions(@AuthenticationPrincipal Jwt jwt) {
        String account = Objects.requireNonNull(jwt.getClaimAsString("preferred_username"));
        return ResponseEntity.ok(portfolio.positionsFor(account));
    }
}
