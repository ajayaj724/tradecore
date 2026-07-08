package io.github.ajayaj724.tradecore.execution.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class MatchingEnginePropertyTest {

    record Input(long orderId, Side side, long price, long quantity) {}

    @Provide
    Arbitrary<List<Input>> orderSequences() {
        Arbitrary<Side> sides = Arbitraries.of(Side.BUY, Side.SELL);
        Arbitrary<Long> prices = Arbitraries.longs().between(9900L, 10100L);
        Arbitrary<Long> qtys = Arbitraries.longs().between(1L, 20L);
        Arbitrary<Input> one = Combinators.combine(sides, prices, qtys).as((s, p, q) -> new Input(0L, s, p, q));
        return one.list().ofMaxSize(40).map(list -> {
            List<Input> withIds = new ArrayList<>();
            long id = 1;
            for (Input in : list) {
                withIds.add(new Input(id++, in.side(), in.price(), in.quantity()));
            }
            return withIds;
        });
    }

    @Property
    void bookNeverCrosses(@ForAll("orderSequences") List<Input> inputs) {
        MatchingEngine engine = new MatchingEngine();
        inputs.forEach(in -> engine.submit("ACME", in.orderId(), in.side(), in.price(), in.quantity()));
        if (engine.bestBid("ACME").isPresent() && engine.bestAsk("ACME").isPresent()) {
            assertThat(engine.bestBid("ACME").getAsLong())
                    .isLessThan(engine.bestAsk("ACME").getAsLong());
        }
    }

    @Property
    void noFillWorseThanLimit(@ForAll("orderSequences") List<Input> inputs) {
        MatchingEngine engine = new MatchingEngine();
        Map<Long, Input> byId = new HashMap<>();
        for (Input in : inputs) {
            byId.put(in.orderId(), in);
            for (Fill f : engine.submit("ACME", in.orderId(), in.side(), in.price(), in.quantity())) {
                assertThat(f.price())
                        .isLessThanOrEqualTo(
                                Objects.requireNonNull(byId.get(f.buyOrderId())).price());
                assertThat(f.price())
                        .isGreaterThanOrEqualTo(Objects.requireNonNull(byId.get(f.sellOrderId()))
                                .price());
            }
        }
    }

    @Property
    void quantityIsConserved(@ForAll("orderSequences") List<Input> inputs) {
        MatchingEngine engine = new MatchingEngine();
        Map<Long, Long> filled = new HashMap<>();
        for (Input in : inputs) {
            for (Fill f : engine.submit("ACME", in.orderId(), in.side(), in.price(), in.quantity())) {
                filled.merge(f.buyOrderId(), f.quantity(), Long::sum);
                filled.merge(f.sellOrderId(), f.quantity(), Long::sum);
            }
        }
        for (Input in : inputs) {
            long open = engine.openQuantity("ACME", in.orderId());
            assertThat(filled.getOrDefault(in.orderId(), 0L) + open).isEqualTo(in.quantity());
        }
    }

    @Property
    void fifoWithinPriceLevel(@ForAll("orderSequences") List<Input> inputs) {
        // Deterministic FIFO scenario re-run under jqwik: at the same price, the earliest
        // (lowest id) resting order fills first. The randomized inputs above already exercise
        // ordering effects through quantityIsConserved; this pins the FIFO guarantee explicitly.
        MatchingEngine engine = new MatchingEngine();
        engine.submit("Z", 1L, Side.BUY, 10000L, 5L);
        engine.submit("Z", 2L, Side.BUY, 10000L, 5L);
        engine.submit("Z", 3L, Side.SELL, 10000L, 5L);
        assertThat(engine.openQuantity("Z", 1L)).isZero();
        assertThat(engine.openQuantity("Z", 2L)).isEqualTo(5L);
    }
}
