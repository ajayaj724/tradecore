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
    public RiskDecision check(String account, Side side, String symbol, long price, long quantity) {
        return side == Side.BUY ? reserveCash(account, price * quantity) : reserveHoldings(account, symbol, quantity);
    }

    private RiskDecision reserveCash(String account, long cost) {
        Long available = jdbc.sql("select amount from risk.available_cash where account = :a for update")
                .param("a", account)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (available == null || available < cost) {
            return new RiskDecision.Rejected("insufficient cash");
        }
        jdbc.sql("update risk.available_cash set amount = amount - :cost where account = :a")
                .param("cost", cost)
                .param("a", account)
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
