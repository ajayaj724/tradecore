package io.github.ajayaj724.tradecore.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ajayaj724.tradecore.TestcontainersConfig;
import io.github.ajayaj724.tradecore.shared.TradeExecuted;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfig.class)
class LedgerServiceIT {

    private final LedgerService ledger;
    private final JdbcClient jdbc;

    @Autowired
    LedgerServiceIT(LedgerService ledger, JdbcClient jdbc) {
        this.ledger = ledger;
        this.jdbc = jdbc;
    }

    private static TradeExecuted trade(long qty, long price) {
        return new TradeExecuted(UUID.randomUUID(), 1L, 2L, "trader1", "trader2", "ACME", price, qty, Instant.EPOCH);
    }

    @Test
    void postingATradeTransfersCashBuyerToSeller() {
        long buyerBefore = ledger.balanceOf("trader1");
        long sellerBefore = ledger.balanceOf("trader2");

        ledger.post(trade(5L, 9000L)); // 45000 paise

        assertThat(ledger.balanceOf("trader1")).isEqualTo(buyerBefore - 45000L);
        assertThat(ledger.balanceOf("trader2")).isEqualTo(sellerBefore + 45000L);
    }

    @Test
    void everyTransactionBalancesToZero() {
        ledger.post(trade(3L, 10000L));
        Long unbalanced = jdbc.sql("select count(*) from"
                        + " (select txn_id, sum(amount) s from ledger.posting group by txn_id) t where s <> 0")
                .query(Long.class)
                .single();
        assertThat(unbalanced).isZero();
    }

    @Test
    void redeliveryOfSameTradeIsANoOp() {
        TradeExecuted t = trade(2L, 10000L);
        long before = ledger.balanceOf("trader1");
        ledger.post(t);
        ledger.post(t); // same eventId → deduped
        assertThat(ledger.balanceOf("trader1")).isEqualTo(before - 20000L);
    }
}
