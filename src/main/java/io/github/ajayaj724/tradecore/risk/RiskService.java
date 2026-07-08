package io.github.ajayaj724.tradecore.risk;

import io.github.ajayaj724.tradecore.shared.CashPosted;
import io.github.ajayaj724.tradecore.shared.HoldingsPosted;
import io.github.ajayaj724.tradecore.shared.Side;
import io.github.ajayaj724.tradecore.shared.TradeExecuted;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Pre-trade checks. The one synchronous API another module (orders) may call. */
@Service
public class RiskService {

    private final JdbcClient jdbc;
    private final Clock clock;

    RiskService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public RiskDecision check(long orderId, String account, Side side, String symbol, long price, long quantity) {
        return side == Side.BUY
                ? reserveCash(orderId, account, price, quantity)
                : reserveHoldings(orderId, account, symbol, quantity);
    }

    private RiskDecision reserveCash(long orderId, String account, long unitPrice, long quantity) {
        Long settled = jdbc.sql("select amount from risk.settled_cash where account = :a for update")
                .param("a", account)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (settled == null) {
            return new RiskDecision.Rejected("insufficient cash");
        }
        long held = jdbc.sql(
                        "select coalesce(sum(unit_price * remaining_qty), 0) from risk.cash_hold where account = :a")
                .param("a", account)
                .query(Long.class)
                .single();
        long cost = unitPrice * quantity;
        if (settled - held < cost) {
            return new RiskDecision.Rejected("insufficient cash");
        }
        jdbc.sql("insert into risk.cash_hold (order_id, account, unit_price, remaining_qty) values (:o, :a, :u, :q)")
                .param("o", orderId)
                .param("a", account)
                .param("u", unitPrice)
                .param("q", quantity)
                .update();
        return new RiskDecision.Approved();
    }

    private RiskDecision reserveHoldings(long orderId, String account, String symbol, long quantity) {
        Long settled = jdbc.sql("select qty from risk.settled_holdings where account = :a and symbol = :s for update")
                .param("a", account)
                .param("s", symbol)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (settled == null) {
            return new RiskDecision.Rejected("insufficient holdings");
        }
        long held = jdbc.sql("select coalesce(sum(remaining_qty), 0) from risk.holdings_hold"
                        + " where account = :a and symbol = :s")
                .param("a", account)
                .param("s", symbol)
                .query(Long.class)
                .single();
        if (settled - held < quantity) {
            return new RiskDecision.Rejected("insufficient holdings");
        }
        jdbc.sql("insert into risk.holdings_hold (order_id, account, symbol, remaining_qty) values (:o, :a, :s, :q)")
                .param("o", orderId)
                .param("a", account)
                .param("s", symbol)
                .param("q", quantity)
                .update();
        return new RiskDecision.Approved();
    }

    /** Read-model update: settled cash is fed by the ledger's signed-delta CashPosted events. */
    @Transactional
    public void applyCashPosted(CashPosted event) {
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        jdbc.sql("update risk.settled_cash set amount = amount + :amt where account = :a")
                .param("amt", event.amount())
                .param("a", event.account())
                .update();
        markProcessed(event.eventId());
    }

    /** Release the buyer's hold for the filled quantity; over-reservation refunds into available cash. */
    @Transactional
    public void releaseHold(TradeExecuted trade) {
        if (alreadyProcessed(trade.eventId())) {
            return;
        }
        jdbc.sql("update risk.cash_hold set remaining_qty = remaining_qty - :q where order_id = :o")
                .param("q", trade.quantity())
                .param("o", trade.buyOrderId())
                .update();
        jdbc.sql("delete from risk.cash_hold where order_id = :o and remaining_qty <= 0")
                .param("o", trade.buyOrderId())
                .update();
        jdbc.sql("update risk.holdings_hold set remaining_qty = remaining_qty - :q where order_id = :o")
                .param("q", trade.quantity())
                .param("o", trade.sellOrderId())
                .update();
        jdbc.sql("delete from risk.holdings_hold where order_id = :o and remaining_qty <= 0")
                .param("o", trade.sellOrderId())
                .update();
        markProcessed(trade.eventId());
    }

    /** Read-model update: settled holdings fed by portfolio's signed-delta HoldingsPosted events. */
    @Transactional
    public void applyHoldingsPosted(HoldingsPosted event) {
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        jdbc.sql("update risk.settled_holdings set qty = qty + :q where account = :a and symbol = :s")
                .param("q", event.qty())
                .param("a", event.account())
                .param("s", event.symbol())
                .update();
        markProcessed(event.eventId());
    }

    /** Settled cash for the account in paise (0 if the account has no settled row). */
    @Transactional(readOnly = true)
    public long settledCash(String account) {
        return jdbc.sql("select amount from risk.settled_cash where account = :a")
                .param("a", account)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    /** Settled holdings quantity for the account and symbol (0 if none). */
    @Transactional(readOnly = true)
    public long settledHoldings(String account, String symbol) {
        return jdbc.sql("select qty from risk.settled_holdings where account = :a and symbol = :s")
                .param("a", account)
                .param("s", symbol)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    private boolean alreadyProcessed(UUID eventId) {
        return jdbc.sql("select count(*) from risk.processed_event where event_id = :e")
                        .param("e", eventId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    private void markProcessed(UUID eventId) {
        jdbc.sql("insert into risk.processed_event (event_id, processed_at) values (:e, :t)")
                .param("e", eventId)
                .param("t", OffsetDateTime.now(clock))
                .update();
    }
}
