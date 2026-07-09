# ADR-0012: JMH microbenchmarks for the matching engine

- Status: Accepted
- Date: 2026-07-09
- Phase: 3C

## Context

The matching engine is deliberately framework-free (no Spring imports — a project invariant), which
makes it directly benchmarkable. The design spec calls for JMH engine benchmarks in Phase 3. We want
a committed, repeatable microbenchmark of the engine's hot path (`MatchingEngine.submit`) that
produces trustworthy numbers and guards against silent performance regressions in the order book.

## Decisions

**JMH with the Java API (`jmh-core` 1.37, `jmh-generator-annprocess` 1.37).** Versions verified
2026-07-09 against `repo1.maven.org` maven-metadata `<release>`. JMH is the tool named in the spec;
its warmup/fork/steady-state machinery is what makes JVM microbenchmarks credible. JDK 25 support was
confirmed by actually running it (forked, clean).

**State-neutral benchmarks so the engine returns to empty every invocation.** A mutable order book
benchmarked naively either grows without bound (pure rest) or drains (pure match), so neither
measures steady state. Both benchmarks are net-zero on the book:
- `restThenFullyMatch` — one order rests, the opposite side fully consumes it; the book is empty
  again. Measures the core rest + match + removal cycle (TreeMap insert, top-of-book lookup, Deque
  ops, fill construction, level removal).
- `buildDepthThenSweep` — build a 10-level ask book, then one marketable BUY sweeps all ten levels.
  Measures multi-level matching. Also net-empty.
Each `@Benchmark` returns its `List<Fill>` so the JIT cannot dead-code-eliminate the work.

**The whole JMH stack is isolated behind a `jmh` Maven profile.** The default build and CI gate never
resolve JMH or compile a benchmark. Benchmarks live in `src/jmh/java`, not a compile source root by
default; the profile adds it via `build-helper-maven-plugin` (3.6.1). Because the gate pins
`annotationProcessorPaths` to Error Prone + NullAway (and passing `-Xplugin:ErrorProne`), the profile
overrides the compiler config (`combine.self="override"`) to drop that arg and swap in JMH's
annotation processor for the benchmark build only — Error Prone/NullAway are the gate's job, not the
benchmark run's. Verified: `mvn verify` stays green with the profile present but inactive, with zero
JMH references.

**Run via a direct `java` invocation for proper forking.** JMH forks a fresh JVM per benchmark
(`@Fork(1)`) for isolation. Under `mvn exec:java` the forked JVM inherits Maven's classpath, not the
project's, and dies with `ClassNotFoundException: org.openjdk.jmh.runner.ForkedMain`. So the
documented recipe builds the test classpath and runs JMH directly:

```bash
mvn -Pjmh test-compile
mvn -Pjmh dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=target/jmh-cp.txt
java -cp "target/test-classes:target/classes:$(cat target/jmh-cp.txt)" org.openjdk.jmh.Main
```

`mvn -Pjmh exec:java -Dexec.mainClass=org.openjdk.jmh.Main -Dexec.classpathScope=test -Dexec.args="-f 0"`
also works for a quick in-process (unforked) run.

## Results (reference local hardware, JDK 25)

Config `-f 1 -wi 2 -i 3 -r 1s -w 1s` (the committed annotations default to 3 warmup / 5 measurement
iterations for a more rigorous run):

| Benchmark | Throughput | Avg time |
|---|---|---|
| `restThenFullyMatch` (rest + full match) | ~38.9 ops/µs | ~26 ns/op |
| `buildDepthThenSweep` (10 rests + 10-level sweep) | ~3.7 ops/µs | ~273 ns/op |

~26 ns for a two-order rest+match cycle and ~273 ns to process eleven orders that build and clear a
ten-deep book (~25 ns/order) are consistent and healthy for an in-memory `TreeMap`/`ArrayDeque` book.
As with the Phase 3B SLOs, these are reference-environment numbers; the durable value is the
repeatable harness and the regression signal.

## Consequences

- A committed, runnable engine benchmark and a documented baseline; re-run after any order-book
  change to catch regressions.
- JMH stays entirely out of `mvn verify` — the gate's dependency surface, processors, and coverage
  are unchanged.

## Follow-ups (not blocking)

- Additional scenarios (partial fills, worst-case deep-book insert ordering) are more `@Benchmark`
  methods on the same class.
- A nightly job could track the numbers over time alongside the Gatling SLOs.
