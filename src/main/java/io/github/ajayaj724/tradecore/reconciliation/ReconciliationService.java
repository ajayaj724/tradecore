package io.github.ajayaj724.tradecore.reconciliation;

import io.github.ajayaj724.tradecore.ledger.LedgerService;
import io.github.ajayaj724.tradecore.marketdata.MarketDataService;
import io.github.ajayaj724.tradecore.portfolio.PortfolioService;
import io.github.ajayaj724.tradecore.risk.RiskService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Read-only reconciliation: proves the event-fed read-models (risk's settled projections) have not
 * drifted from their source of truth (ledger cash, portfolio positions). Pure fan-in — nothing
 * depends on this module. See ADR-0009 for the cross-module read-access rationale.
 */
@Service
public class ReconciliationService {

    private final RiskService risk;
    private final LedgerService ledger;
    private final PortfolioService portfolio;
    private final MarketDataService marketData;
    private final ReconciliationProperties props;

    private final AtomicInteger driftPairs = new AtomicInteger(0);
    private final Map<String, AtomicLong> equityByAccount = new HashMap<>();

    ReconciliationService(
            RiskService risk,
            LedgerService ledger,
            PortfolioService portfolio,
            MarketDataService marketData,
            ReconciliationProperties props,
            MeterRegistry registry) {
        this.risk = risk;
        this.ledger = ledger;
        this.portfolio = portfolio;
        this.marketData = marketData;
        this.props = props;

        Gauge.builder("tradecore.reconciliation.drift.pairs", driftPairs, AtomicInteger::doubleValue)
                .description("count of (account, symbol) pairs whose cash or holdings drift is non-zero; 0 = healthy")
                .register(registry);

        for (String account : props.accounts()) {
            AtomicLong holder = new AtomicLong(0);
            equityByAccount.put(account, holder);
            Gauge.builder("tradecore.account.equity", holder, AtomicLong::doubleValue)
                    .description("net asset value per account in paise: cash + sum(position * last price)")
                    .tag("account", account)
                    .register(registry);
        }
    }

    /** Recompute drift and equity for the configured universe and publish the gauges. */
    @Scheduled(
            initialDelayString = "${tradecore.reconciliation.initial-delay-ms:60000}",
            fixedDelayString = "${tradecore.reconciliation.fixed-delay-ms:60000}")
    public void reconcile() {
        ReconciliationReport current = report();
        for (ReconciliationReport.AccountHealth health : current.accounts()) {
            AtomicLong holder = equityByAccount.get(health.account());
            if (holder != null) {
                holder.set(health.equity());
            }
        }
        driftPairs.set(current.driftPairs());
    }

    /** On-demand snapshot — the same computation the scheduled gauges publish (ADR-0023). */
    ReconciliationReport report() {
        List<ReconciliationReport.AccountHealth> accounts =
                props.accounts().stream().map(this::healthOf).toList();
        int drifted = accounts.stream()
                .mapToInt(ReconciliationReport.AccountHealth::driftedPairs)
                .sum();
        return new ReconciliationReport(drifted, accounts);
    }

    /** Reconcile one account across every tradable symbol (marketdata is the universe). */
    private ReconciliationReport.AccountHealth healthOf(String account) {
        long cashDrift = risk.settledCash(account) - ledger.balanceOf(account);
        int drifted = 0;
        long positionsValue = 0;
        for (String symbol : marketData.knownSymbols()) {
            long qty = portfolio.positionQty(account, symbol);
            long holdingsDrift = risk.settledHoldings(account, symbol) - qty;
            // cash drift is per-account, so it marks every configured symbol-pair for that account;
            // the 0-vs-non-zero alarm is the signal, not the magnitude
            if (cashDrift != 0 || holdingsDrift != 0) {
                drifted++;
            }
            if (qty != 0) {
                try {
                    positionsValue += qty * marketData.lastPrice(symbol);
                } catch (EmptyResultDataAccessException noPrice) {
                    // no last price for this symbol yet — omit from equity, keep reconciling
                }
            }
        }
        return new ReconciliationReport.AccountHealth(
                account, ledger.balanceOf(account) + positionsValue, cashDrift, drifted);
    }
}
