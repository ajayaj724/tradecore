package io.github.ajayaj724.tradecore.execution.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
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
    void cancellingRestingOrderFreesExactlyItsQuantityAndLeavesOthersUntouched(
            @ForAll("orderSequences") List<Input> inputs) {
        MatchingEngine engine = new MatchingEngine();
        inputs.forEach(in -> engine.submit("ACME", in.orderId(), in.side(), in.price(), in.quantity()));

        OptionalLong stillResting = inputs.stream()
                .mapToLong(Input::orderId)
                .filter(id -> engine.openQuantity("ACME", id) > 0)
                .findFirst();
        Assume.that(stillResting.isPresent());
        long target = stillResting.getAsLong();

        long targetOpenBefore = engine.openQuantity("ACME", target);
        Map<Long, Long> openBefore = new HashMap<>();
        inputs.forEach(in -> openBefore.put(in.orderId(), engine.openQuantity("ACME", in.orderId())));

        long cancelled = engine.cancel("ACME", target);

        assertThat(cancelled).isEqualTo(targetOpenBefore);
        assertThat(engine.openQuantity("ACME", target)).isZero();
        for (Input in : inputs) {
            if (in.orderId() == target) {
                continue;
            }
            assertThat(engine.openQuantity("ACME", in.orderId())).isEqualTo(openBefore.get(in.orderId()));
        }
        // The book still never crosses after the removal.
        if (engine.bestBid("ACME").isPresent() && engine.bestAsk("ACME").isPresent()) {
            assertThat(engine.bestBid("ACME").getAsLong())
                    .isLessThan(engine.bestAsk("ACME").getAsLong());
        }
    }

    @Property
    void cancelOfUnknownOrderReturnsZeroAndConservesTheBook(@ForAll("orderSequences") List<Input> inputs) {
        MatchingEngine engine = new MatchingEngine();
        inputs.forEach(in -> engine.submit("ACME", in.orderId(), in.side(), in.price(), in.quantity()));
        long totalOpenBefore = inputs.stream()
                .mapToLong(in -> engine.openQuantity("ACME", in.orderId()))
                .sum();

        assertThat(engine.cancel("ACME", 999_999L)).isZero(); // id never submitted (ids run 1..40)
        assertThat(engine.cancel("NO_SUCH_SYMBOL", 1L)).isZero(); // book never created

        long totalOpenAfter = inputs.stream()
                .mapToLong(in -> engine.openQuantity("ACME", in.orderId()))
                .sum();
        assertThat(totalOpenAfter).isEqualTo(totalOpenBefore);
    }

    @Property
    void filledPlusCancelledEqualsSubmittedForACancelledOrder(@ForAll("orderSequences") List<Input> inputs) {
        MatchingEngine engine = new MatchingEngine();
        Map<Long, Long> filled = new HashMap<>();
        Map<Long, Long> submitted = new HashMap<>();
        for (Input in : inputs) {
            submitted.put(in.orderId(), in.quantity());
            for (Fill f : engine.submit("ACME", in.orderId(), in.side(), in.price(), in.quantity())) {
                filled.merge(f.buyOrderId(), f.quantity(), Long::sum);
                filled.merge(f.sellOrderId(), f.quantity(), Long::sum);
            }
        }
        OptionalLong stillResting = inputs.stream()
                .mapToLong(Input::orderId)
                .filter(id -> engine.openQuantity("ACME", id) > 0)
                .findFirst();
        Assume.that(stillResting.isPresent());
        long target = stillResting.getAsLong();

        long cancelled = engine.cancel("ACME", target);

        // Conservation: nothing filled is lost, nothing is created — filled + cancelled = original size.
        assertThat(filled.getOrDefault(target, 0L) + cancelled).isEqualTo(submitted.get(target));
    }

    @Property
    void immediateOrCancelOrdersNeverRest(@ForAll("orderSequences") List<Input> inputs) {
        MatchingEngine engine = new MatchingEngine();
        inputs.forEach(in -> engine.submit("ACME", in.orderId(), in.side(), in.price(), in.quantity()));

        // A marketable IOC on each side crosses whatever it can, then drops the rest — never rests.
        engine.submitIoc("ACME", 10_001L, Side.BUY, 10_100L, 15L);
        engine.submitIoc("ACME", 10_002L, Side.SELL, 9_900L, 15L);

        assertThat(engine.openQuantity("ACME", 10_001L)).isZero();
        assertThat(engine.openQuantity("ACME", 10_002L)).isZero();
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
