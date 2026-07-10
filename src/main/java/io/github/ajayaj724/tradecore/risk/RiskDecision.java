package io.github.ajayaj724.tradecore.risk;

public sealed interface RiskDecision permits RiskDecision.Approved, RiskDecision.Rejected {

    /**
     * Approved, reserving at {@code effectiveUnitPrice} — the client's price, or for an
     * unpriced MARKET order the collared reference price that also caps the engine match.
     */
    record Approved(long effectiveUnitPrice) implements RiskDecision {}

    record Rejected(String reason) implements RiskDecision {}
}
