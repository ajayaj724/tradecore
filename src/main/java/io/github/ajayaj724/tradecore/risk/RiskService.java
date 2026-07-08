package io.github.ajayaj724.tradecore.risk;

import io.github.ajayaj724.tradecore.shared.Side;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Pre-trade checks. The one synchronous API another module (orders) may call. */
@Service
public class RiskService {

    private final JdbcClient jdbc;

    RiskService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public RiskDecision check(long orderId, String account, Side side, String symbol, long price, long quantity) {
        return side == Side.BUY
                ? reserveCash(orderId, account, price, quantity)
                : reserveHoldings(account, symbol, quantity);
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

    private RiskDecision reserveHoldings(String account, String symbol, long quantity) {
        Long available = jdbc.sql(
                        "select quantity from risk.available_holdings where account = :a and symbol = :s for update")
                .param("a", account)
                .param("s", symbol)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (available == null || available < quantity) {
            return new RiskDecision.Rejected("insufficient holdings");
        }
        jdbc.sql("update risk.available_holdings set quantity = quantity - :q where account = :a and symbol = :s")
                .param("q", quantity)
                .param("a", account)
                .param("s", symbol)
                .update();
        return new RiskDecision.Approved();
    }
}
