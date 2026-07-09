package jmh;

import io.github.ajayaj724.tradecore.execution.engine.Fill;
import io.github.ajayaj724.tradecore.execution.engine.MatchingEngine;
import io.github.ajayaj724.tradecore.execution.engine.Side;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Phase 3C microbenchmarks for the framework-free {@link MatchingEngine}. Each benchmark is
 * state-neutral — the book returns to empty every invocation — so it measures steady-state hot-path
 * cost without unbounded growth. Run with:
 *
 * <pre>{@code
 *   mvn -Pjmh test-compile
 *   mvn -Pjmh exec:java -Dexec.mainClass=org.openjdk.jmh.Main -Dexec.classpathScope=test
 * }</pre>
 *
 * Returned values are handed to JMH so the JIT cannot dead-code-eliminate the work.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class MatchingEngineBenchmark {

    private static final long PRICE = 10_000L;
    private static final long QTY = 5L;
    private static final int DEPTH = 10;

    private MatchingEngine engine;
    private long nextId;

    @Setup(Level.Iteration)
    public void setUp() {
        engine = new MatchingEngine();
        nextId = 1L;
    }

    /** One order rests, the opposite side fully consumes it — the core rest+match+remove cycle. */
    @Benchmark
    public List<Fill> restThenFullyMatch() {
        engine.submit("ACME", nextId++, Side.BUY, PRICE, QTY); // rests on the bid side
        return engine.submit("ACME", nextId++, Side.SELL, PRICE, QTY); // crosses and fills; book empty
    }

    /** Build a 10-level ask book, then a single marketable BUY sweeps every level. */
    @Benchmark
    public List<Fill> buildDepthThenSweep() {
        for (int i = 0; i < DEPTH; i++) {
            engine.submit("INFY", nextId++, Side.SELL, PRICE + i, QTY);
        }
        return engine.submit("INFY", nextId++, Side.BUY, PRICE + DEPTH - 1, QTY * DEPTH);
    }
}
