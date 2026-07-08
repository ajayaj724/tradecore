package io.github.ajayaj724.tradecore.ledger;

import io.github.ajayaj724.tradecore.shared.CashPosted;
import io.github.ajayaj724.tradecore.shared.TradeExecuted;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Double-entry cash ledger. Cash for an account is the sum of its postings — never a mutable column. */
@Service
public class LedgerService {

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    LedgerService(JdbcClient jdbc, ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void post(TradeExecuted trade) {
        if (alreadyProcessed(trade.eventId())) {
            return;
        }
        long notional = trade.price() * trade.quantity();
        UUID txn = UUID.randomUUID();
        insertPosting(txn, trade.buyerAccount(), -notional);
        insertPosting(txn, trade.sellerAccount(), notional);
        jdbc.sql("insert into ledger.processed_event (event_id, processed_at) values (:e, :t)")
                .param("e", trade.eventId())
                .param("t", OffsetDateTime.now(clock))
                .update();
        events.publishEvent(new CashPosted(UUID.randomUUID(), trade.buyerAccount(), -notional, clock.instant()));
        events.publishEvent(new CashPosted(UUID.randomUUID(), trade.sellerAccount(), notional, clock.instant()));
    }

    public long balanceOf(String account) {
        return jdbc.sql("select coalesce(sum(amount), 0) from ledger.posting where account = :a")
                .param("a", account)
                .query(Long.class)
                .single();
    }

    private void insertPosting(UUID txn, String account, long amount) {
        jdbc.sql("insert into ledger.posting (txn_id, account, amount, kind) values (:t, :a, :amt, 'TRADE')")
                .param("t", txn)
                .param("a", account)
                .param("amt", amount)
                .update();
    }

    private boolean alreadyProcessed(UUID eventId) {
        return jdbc.sql("select count(*) from ledger.processed_event where event_id = :e")
                        .param("e", eventId)
                        .query(Long.class)
                        .single()
                > 0;
    }
}
