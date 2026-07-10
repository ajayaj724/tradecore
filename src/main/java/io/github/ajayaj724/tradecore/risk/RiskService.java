package io.github.ajayaj724.tradecore.risk;

import io.github.ajayaj724.tradecore.shared.CashPosted;
import io.github.ajayaj724.tradecore.shared.HoldingsPosted;
import io.github.ajayaj724.tradecore.shared.OrderCancelled;
import io.github.ajayaj724.tradecore.shared.PriceUpdated;
import io.github.ajayaj724.tradecore.shared.Side;
import io.github.ajayaj724.tradecore.shared.TradeExecuted;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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

    /** Basis points of protective collar applied to the reference price of unpriced MARKET orders. */
    private static final long COLLAR_BASIS_POINTS = 500;

    /**
     * Pre-trade check. {@code price} is the client's limit/cap in paise, or {@code null} for an
     * unpriced MARKET order — then the reference price ± the collar becomes the effective price
     * that is both reserved against and returned as the engine's protective cap.
     */
    @Transactional
    public RiskDecision check(
            long orderId, String account, Side side, String symbol, @Nullable Long price, long quantity) {
        Long effective = price != null ? price : collaredReference(symbol, side);
        if (effective == null) {
            return new RiskDecision.Rejected("no reference price");
        }
        return side == Side.BUY
                ? reserveCash(orderId, account, effective, quantity)
                : reserveHoldings(orderId, account, symbol, effective, quantity);
    }

    private @Nullable Long collaredReference(String symbol, Side side) {
        Long reference = jdbc.sql("select price from risk.reference_price where symbol = :s")
                .param("s", symbol)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (reference == null) {
            return null;
        }
        long collar = reference * COLLAR_BASIS_POINTS / 10_000;
        return side == Side.BUY ? reference + collar : Math.max(1, reference - collar);
    }

    /** Read-model update: last-write-wins by event time, so replays and stale redeliveries are no-ops. */
    @Transactional
    public void applyPriceUpdated(PriceUpdated event) {
        jdbc.sql("insert into risk.reference_price (symbol, price, updated_at) values (:s, :p, :t)"
                        + " on conflict (symbol) do update set price = excluded.price, updated_at = excluded.updated_at"
                        + " where reference_price.updated_at <= excluded.updated_at")
                .param("s", event.symbol())
                .param("p", event.price())
                .param("t", event.occurredAt().atOffset(ZoneOffset.UTC))
                .update();
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
        return new RiskDecision.Approved(unitPrice);
    }

    private RiskDecision reserveHoldings(long orderId, String account, String symbol, long unitPrice, long quantity) {
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
        return new RiskDecision.Approved(unitPrice);
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

    /**
     * Release the full remaining hold for a cancelled order. The hold's {@code remaining_qty} already
     * tracks only the unfilled reservation (fills decrement it in {@link #releaseHold}), so deleting
     * by {@code order_id} credits back exactly the unused amount. Only one of the two holds exists for
     * a given order — the other delete is a harmless no-op.
     */
    @Transactional
    public void releaseOnCancel(OrderCancelled event) {
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        jdbc.sql("delete from risk.cash_hold where order_id = :o")
                .param("o", event.orderId())
                .update();
        jdbc.sql("delete from risk.holdings_hold where order_id = :o")
                .param("o", event.orderId())
                .update();
        markProcessed(event.eventId());
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

    /** Snapshot of the account's cash: settled, held by working orders, and the difference. */
    @Transactional(readOnly = true)
    CashBalance balanceOf(String account) {
        long settled = settledCash(account);
        long held = jdbc.sql(
                        "select coalesce(sum(unit_price * remaining_qty), 0) from risk.cash_hold where account = :a")
                .param("a", account)
                .query(Long.class)
                .single();
        return new CashBalance(account, settled, held, settled - held);
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
