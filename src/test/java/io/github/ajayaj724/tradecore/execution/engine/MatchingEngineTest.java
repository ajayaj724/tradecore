package io.github.ajayaj724.tradecore.execution.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MatchingEngineTest {

    private final MatchingEngine engine = new MatchingEngine();

    @Test
    void crossingBuyFillsRestingSellAtRestingPrice() {
        engine.submit("ACME", 1L, Side.SELL, 10000L, 5L); // resting ask @100.00
        List<Fill> fills = engine.submit("ACME", 2L, Side.BUY, 10500L, 5L); // marketable buy

        assertThat(fills).containsExactly(new Fill(2L, 1L, 10000L, 5L));
        assertThat(engine.bestBid("ACME")).isEmpty();
        assertThat(engine.bestAsk("ACME")).isEmpty();
    }

    @Test
    void partialFillRestsTheRemainder() {
        engine.submit("ACME", 1L, Side.SELL, 10000L, 3L);
        List<Fill> fills = engine.submit("ACME", 2L, Side.BUY, 10000L, 5L);

        assertThat(fills).containsExactly(new Fill(2L, 1L, 10000L, 3L));
        assertThat(engine.openQuantity("ACME", 2L)).isEqualTo(2L); // 2 remain resting as a bid
        assertThat(engine.bestBid("ACME")).hasValue(10000L);
    }

    @Test
    void nonCrossingOrdersBothRest() {
        engine.submit("ACME", 1L, Side.BUY, 9900L, 5L);
        List<Fill> fills = engine.submit("ACME", 2L, Side.SELL, 10100L, 5L);

        assertThat(fills).isEmpty();
        assertThat(engine.bestBid("ACME")).hasValue(9900L);
        assertThat(engine.bestAsk("ACME")).hasValue(10100L);
    }

    @Test
    void fifoWithinPriceLevelFillsEarliestFirst() {
        engine.submit("ACME", 1L, Side.BUY, 10000L, 5L); // earlier
        engine.submit("ACME", 2L, Side.BUY, 10000L, 5L); // later, same price
        List<Fill> fills = engine.submit("ACME", 3L, Side.SELL, 10000L, 5L);

        assertThat(fills).containsExactly(new Fill(1L, 3L, 10000L, 5L)); // order 1 filled, not 2
        assertThat(engine.openQuantity("ACME", 2L)).isEqualTo(5L);
    }
}
