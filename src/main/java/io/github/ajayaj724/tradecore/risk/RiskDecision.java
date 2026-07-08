package io.github.ajayaj724.tradecore.risk;

public sealed interface RiskDecision permits RiskDecision.Approved, RiskDecision.Rejected {

    record Approved() implements RiskDecision {}

    record Rejected(String reason) implements RiskDecision {}
}
