package io.github.ajayaj724.tradecore.portfolio;

import io.github.ajayaj724.tradecore.shared.HoldingsPosted;
import io.github.ajayaj724.tradecore.shared.PriceUpdated;
import io.github.ajayaj724.tradecore.shared.TradeExecuted;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Positions as total_qty + total_cost; average cost derived, P&L integer. */
@Service
public class PortfolioService {

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    PortfolioService(JdbcClient jdbc, ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void onTrade(TradeExecuted trade) {
        if (alreadyProcessed(trade.eventId())) {
            return;
        }
        applyBuy(trade.buyerAccount(), trade.symbol(), trade.price(), trade.quantity());
        applySell(trade.sellerAccount(), trade.symbol(), trade.price(), trade.quantity());
        markProcessed(trade.eventId());
        publishHoldings(trade.buyerAccount(), trade.symbol(), trade.quantity());
        publishHoldings(trade.sellerAccount(), trade.symbol(), -trade.quantity());
    }

    @Transactional
    public void onPrice(PriceUpdated price) {
        if (alreadyProcessed(price.eventId())) {
            return;
        }
        jdbc.sql("insert into portfolio.mark_price (symbol, price) values (:s, :p)"
                        + " on conflict (symbol) do update set price = :p")
                .param("s", price.symbol())
                .param("p", price.price())
                .update();
        markProcessed(price.eventId());
    }

    /** Open or realized positions for the account, alphabetical; mark absent → unrealized 0. */
    @Transactional(readOnly = true)
    List<PositionResponse> positionsFor(String account) {
        return jdbc.sql("select p.symbol, p.total_qty, p.total_cost, p.realized_pnl, m.price as mark_price"
                        + " from portfolio.position p"
                        + " left join portfolio.mark_price m on m.symbol = p.symbol"
                        + " where p.account = :a and (p.total_qty <> 0 or p.realized_pnl <> 0)"
                        + " order by p.symbol")
                .param("a", account)
                .query((rs, rowNum) -> {
                    long qty = rs.getLong("total_qty");
                    long cost = rs.getLong("total_cost");
                    long mark = rs.getLong("mark_price");
                    boolean marked = !rs.wasNull();
                    return new PositionResponse(
                            rs.getString("symbol"),
                            qty,
                            cost,
                            marked ? mark : null,
                            rs.getLong("realized_pnl"),
                            marked ? mark * qty - cost : 0);
                })
                .list();
    }

    public long positionQty(String account, String symbol) {
        return scalar(
                "select coalesce(total_qty, 0) from portfolio.position where account = :a and symbol = :s",
                account,
                symbol);
    }

    public long realizedPnl(String account, String symbol) {
        return scalar(
                "select coalesce(realized_pnl, 0) from portfolio.position where account = :a and symbol = :s",
                account,
                symbol);
    }

    /** Exact: market value − cost basis = last_price×qty − total_cost. */
    public long unrealizedPnl(String account, String symbol) {
        long qty = positionQty(account, symbol);
        long cost = scalar(
                "select coalesce(total_cost, 0) from portfolio.position where account = :a and symbol = :s",
                account,
                symbol);
        Long mark = jdbc.sql("select price from portfolio.mark_price where symbol = :s")
                .param("s", symbol)
                .query(Long.class)
                .optional()
                .orElse(null);
        return mark == null ? 0 : mark * qty - cost;
    }

    private void applyBuy(String account, String symbol, long price, long qty) {
        ensurePosition(account, symbol);
        jdbc.sql("update portfolio.position set total_qty = total_qty + :q, total_cost = total_cost + :c"
                        + " where account = :a and symbol = :s")
                .param("q", qty)
                .param("c", price * qty)
                .param("a", account)
                .param("s", symbol)
                .update();
    }

    private void applySell(String account, String symbol, long price, long qty) {
        ensurePosition(account, symbol);
        long totalQty = positionQty(account, symbol);
        long totalCost = scalar(
                "select coalesce(total_cost, 0) from portfolio.position where account = :a and symbol = :s",
                account,
                symbol);
        long cost = totalQty == 0 ? 0 : totalCost * qty / totalQty;
        jdbc.sql("update portfolio.position set total_qty = total_qty - :q, total_cost = total_cost - :cost,"
                        + " realized_pnl = realized_pnl + :pnl where account = :a and symbol = :s")
                .param("q", qty)
                .param("cost", cost)
                .param("pnl", price * qty - cost)
                .param("a", account)
                .param("s", symbol)
                .update();
    }

    private void ensurePosition(String account, String symbol) {
        jdbc.sql("insert into portfolio.position (account, symbol) values (:a, :s)"
                        + " on conflict (account, symbol) do nothing")
                .param("a", account)
                .param("s", symbol)
                .update();
    }

    private void publishHoldings(String account, String symbol, long qty) {
        events.publishEvent(new HoldingsPosted(UUID.randomUUID(), account, symbol, qty, clock.instant()));
    }

    private long scalar(String sql, String account, String symbol) {
        return jdbc.sql(sql)
                .param("a", account)
                .param("s", symbol)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    private boolean alreadyProcessed(UUID eventId) {
        return jdbc.sql("select count(*) from portfolio.processed_event where event_id = :e")
                        .param("e", eventId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    private void markProcessed(UUID eventId) {
        jdbc.sql("insert into portfolio.processed_event (event_id, processed_at) values (:e, :t)")
                .param("e", eventId)
                .param("t", OffsetDateTime.now(clock))
                .update();
    }
}
