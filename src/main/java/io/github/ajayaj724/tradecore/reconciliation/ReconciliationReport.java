package io.github.ajayaj724.tradecore.reconciliation;

import java.util.List;

/** Snapshot of read-model health. All money in paise; driftPairs 0 = healthy. */
record ReconciliationReport(int driftPairs, List<AccountHealth> accounts) {

    record AccountHealth(String account, long equity, long cashDrift, int driftedPairs) {}
}
