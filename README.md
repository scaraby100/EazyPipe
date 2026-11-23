<p align="center">
  <img src="docs/eazypipe-logo.png" alt="EazyPipe Logo" width="220" />
</p>

<h1 align="center">EazyPipe 2.0</h1>

<p align="center">
  <strong>Zero-dependency, backpressured, and end-to-end auto-balancing concurrent pipelines for Java 21+ Virtual Threads.</strong>
</p>

EazyPipe lets you compose producer, transformation, fan-out, and consumer stages in an immutable fluent API while managing **Java 21 Virtual Threads**, **hybrid CPU/IO scheduling**, **bounded inter-stage backpressure**, **downstream-aware end-to-end auto-balancing**, **retries & dead-letter handling**, and **graceful draining**.

---

## Features

- **Type-safe fluent API (`module xyz.scarabya.eazypipe`)** — Zero reflection, zero runtime dependencies, minimal JPMS surface (`java.base` only, all internal adaptive/runtime machinery hidden).
- **Hybrid per-stage scheduling** — Run `IO_BOUND` stages on **Java 21 Virtual Threads**, `CPU_BOUND` stages on a shared daemon platform-thread pool bounded by `PipeConfig.cpuBudget()`, or bring your own `ExecutorService`.
- **Real lock-based backpressure & overflow policies** — Bounded FIFO channels built on `ReentrantLock` + `Condition` (zero busy-waiting, zero virtual-thread pinning) supporting `BLOCK`, `DROP_OLDEST`, `DROP_NEWEST`, and `FAIL`.
- **Downstream-aware end-to-end auto-balancing (`.adaptive()`)** — Automatically rebalances shared `CPU_BOUND` permits (`fan-out`-aware across `filter`, `flatMap`, and `batch`), tunes `IO_BOUND` concurrency using moving Vegas baselines + retry saturation + gradient checks, propagates downstream congestion brakes in one tick, and applies Schmitt-trigger deadband hysteresis.
- **Production operators & mini-DAG** — `map`, `mapConcurrentOrdered`, `filter`, `flatMap`, `batch(maxSize, maxWait)`, drift-free `rateLimit(permitsPerSecond[, burst])`, `tee`, `broadcast`, `route`, `EazyPipe.merge`, `peek`, and `ContextPropagator`.
- **Structured resilience & observability** — Per-stage `ErrorPolicy` (`FAIL_FAST`, `SKIP`, `DEAD_LETTER`, `RETRY` with exponential backoff + jitter), typed `DeadLetterHandler<T>`, graceful `drain()` vs immediate `cancel()`, `PipeExecution<R>` (`AutoCloseable` + `CompletableFuture<R>`), explainable `ScalingDecision.explain()`, and `PipelineMetricsSnapshot.summary()`.

## Requirements

- **Java 21+**
- Zero runtime dependencies (JUnit 5 in `test` scope only).

---

## Installation (`2.0.0-RC1` — GitHub Release & Local Maven)

Pre-release builds are distributed via [GitHub Releases (`v2.0.0-RC1`)](https://github.com/jucasoft/EazyPipe/releases/tag/v2.0.0-RC1) so you can use EazyPipe in any **Java 21+ Maven project** without waiting for Maven Central publishing.

### GitHub Release & Downloadable Artifacts

Every `v*` tag triggers [`.github/workflows/release.yml`](.github/workflows/release.yml), which compiles the module on Java 21, runs the full test suite, and attaches the release bundle (`pre-release` when the tag includes a suffix such as `-RC1`):

- `EazyPipe-2.0.0-RC1.jar` — JPMS library JAR (`module xyz.scarabya.eazypipe`, `requires java.base`, exporting `xyz.scarabya.eazypipe`, `xyz.scarabya.eazypipe.adaptive`, and `xyz.scarabya.eazypipe.metrics`; internal engine encapsulated in `xyz.scarabya.eazypipe.internal.adaptive`)
- `EazyPipe-2.0.0-RC1-sources.jar` — Java sources for IDE navigation/debugging
- `EazyPipe-2.0.0-RC1-javadoc.jar` — Compiled Javadoc archive
- `EazyPipe-2.0.0-RC1.pom` — Project POM metadata (`xyz.scarabya:EazyPipe:2.0.0-RC1`)
- `SHA256SUMS.txt` — SHA-256 checksums for all attached artifacts

### Option A — Download Release JARs and install into local Maven (`~/.m2`)

Download the 4 artifact files (and `SHA256SUMS.txt`) from [v2.0.0-RC1](https://github.com/jucasoft/EazyPipe/releases/tag/v2.0.0-RC1), optionally verify checksums (`sha256sum -c SHA256SUMS.txt` on Linux/macOS or `Get-FileHash -Algorithm SHA256` on PowerShell), and install them once into your local Maven repository:

**Linux / macOS:**
```bash
mvn install:install-file \
  -Dfile=EazyPipe-2.0.0-RC1.jar \
  -Dsources=EazyPipe-2.0.0-RC1-sources.jar \
  -Djavadoc=EazyPipe-2.0.0-RC1-javadoc.jar \
  -DpomFile=EazyPipe-2.0.0-RC1.pom
```

**Windows PowerShell:**
```powershell
mvn install:install-file `
  -Dfile=EazyPipe-2.0.0-RC1.jar `
  -Dsources=EazyPipe-2.0.0-RC1-sources.jar `
  -Djavadoc=EazyPipe-2.0.0-RC1-javadoc.jar `
  -DpomFile=EazyPipe-2.0.0-RC1.pom
```

Then declare the dependency in your Java project's `pom.xml`:

```xml
<dependency>
    <groupId>xyz.scarabya</groupId>
    <artifactId>EazyPipe</artifactId>
    <version>2.0.0-RC1</version>
</dependency>
```

If your consuming project uses Java Modules (`module-info.java`):

```java
module com.example.myapp {
    requires xyz.scarabya.eazypipe;
}
```

### Option B — Build & install directly from source (Maven Wrapper)

```bash
git clone https://github.com/jucasoft/EazyPipe.git
cd EazyPipe
./mvnw -B clean install        # Windows PowerShell: .\mvnw.cmd -B clean install
```

This runs the JUnit 5 suite and installs `EazyPipe-2.0.0-RC1.jar`, `-sources.jar`, `-javadoc.jar`, and `.pom` into `~/.m2/repository/xyz/scarabya/EazyPipe/2.0.0-RC1/`.

---

## Quick Start

```java
import xyz.scarabya.eazypipe.EazyPipe;
import xyz.scarabya.eazypipe.StageConfig;

import java.util.List;

public class Example {
    public static void main(String[] args) {
        List<String> result = EazyPipe.of("alpha", "be", "gamma", "delta")
                .filter(s -> s.length() > 2)
                .mapConcurrentOrdered(StageConfig.parallel(4), String::toUpperCase)
                .flatMap(s -> List.of(s, s + "!"))
                .toList();

        System.out.println(result);
    }
}
```

### End-to-End Auto-Balancing with `.adaptive()` (Presets, Retries, Rate Limit Burst & Dead-Letter)

Enable end-to-end adaptive concurrency in one call with `.adaptive()` (uses `AdaptiveConfig.balanced()`), or select `AdaptiveConfig.throughput()` / `AdaptiveConfig.lowLatency()` / `AdaptiveConfig.disabled()`:

```java
AdaptiveConfig adaptive = AdaptiveConfig.balanced().toBuilder()
        .tickInterval(Duration.ofMillis(200))
        .onDecision(decision -> System.out.println(decision.explain()))
        .build();

try (PipeExecution<Void> exec = EazyPipe.fromStream(() -> Files.lines(Path.of("docs.txt")))
        .map(StageConfig.cpuBound("parse-chunk")
                        .concurrency(1, 8)
                        .bufferCapacity(256)
                        .build(),
                line -> parseChunk(line))
        .rateLimit(3_000.0, 64)
        .map(StageConfig.ioBound("embed")
                        .concurrency(2, 64)
                        .bufferCapacity(128)
                        .retry(RetrySpec.exponential(3, Duration.ofMillis(100)))
                        .deadLetter(String.class, (chunk, err, stage) ->
                                System.err.printf("Skipped in %s (%s): %s%n", stage, chunk, err.getMessage()))
                        .build(),
                chunk -> embed(chunk))
        .batch("batch-upsert", 100, Duration.ofMillis(250))
        .configure(PipeConfig.builder()
                .cpuBudget(Runtime.getRuntime().availableProcessors())
                .build())
        .adaptive(adaptive)
        .start(StageConfig.ioBound("vector-db")
                        .concurrency(1, 8)
                        .build(),
                batch -> upsertBatch(batch))) {

    exec.await();
    System.out.println(exec.metricsSnapshot().summary());
}
```

### Production Operators (`mapConcurrentOrdered`, `rateLimit`, `tee`, `broadcast`, `route`, `merge`)

- `mapConcurrentOrdered(StageConfig, mapper)` — Processes items concurrently across workers while emitting downstream in strict FIFO input order.
- `batch(maxSize, maxWait)` — Groups items into immutable `List` batches flushed on size or timeout.
- `rateLimit(permitsPerSecond)` / `rateLimit(permitsPerSecond, burst)` — Drift-free GCRA token bucket that paces downstream throughput and is abort/drain-aware.
- `tee(sideConfig, sideSink)` — Forks every passing item asynchronously to a side sink with full stage telemetry while keeping the main flow running.
- `runBroadcast(...)` / `startBroadcast(...)` — Concurrent fan-out to multiple independent terminal sinks (consumes counted on all-branch completion).
- `runRoute(classifier, routes, defaultSink)` — Key-based routing across terminal sinks.
- `EazyPipe.merge(sources...)` — Merges multiple concurrent `PipeSource` producers into a single stream.

---

## Lifecycle Control (`drain` vs `cancel`), Context & Metrics

```java
try (PipeExecution<List<Integer>> exec = EazyPipe.<Integer>from(emitter -> {
            int i = 0;
            while (!Thread.currentThread().isInterrupted()) {
                emitter.emit(i++);
            }
        })
        .map(n -> n * 2)
        .startToList()) {

    Thread.sleep(50);
    // Stops the source cleanly and waits for all in-flight items to drain downstream:
    List<Integer> drained = exec.drain();
    PipelineMetricsSnapshot metrics = exec.metricsSnapshot();
    System.out.println(metrics.summary());
}
```

- Attach thread-local context (`MDC`, trace IDs, tenant IDs) across worker and item boundaries via `PipeConfig.builder().contextPropagator(...)`.
- Observe live stage metrics, explainable scaling decisions (`ScalingDecision.explain()`), errors, dead letters, and channel overflows via `PipeConfig.builder().metricsListener(...)`.
- Use `PipeConfig.withExecutor(myPool)` (or `.customExecutor(myPool)`) when supplying your own `ExecutorService` — EazyPipe never shuts down caller-owned executors.

---

## Benchmarks & Proof of Value

Measured on Windows 11 Pro (`Intel Core i7-14700HX`, OpenJDK `21.0.11+10-LTS`) using the standalone `eazypipe-benchmarks` harness (`JMH 1.37` + Scenarios A–E under `-Xmx64m`). Full data, Mermaid charts, JMH methodology, and honest trade-offs live in [docs/BENCHMARKS.md](docs/BENCHMARKS.md).

| Scenario | Comparison | Adaptive Result | Gain vs Naive Static |
| :--- | :--- | :--- | :--- |
| **A — Shifting CPU** (`cpuBudget=8`) | `static-naive` (`784.5/s`) vs `adaptive` | **2,251.9 items/s** (`7,105 ms`) | **2.87× throughput** (`1.43×` vs static-balanced) |
| **B — Downstream Stall** (`-Xmx64m`) | `static-naive` (`p95 10,920.81 ms`, `27.4 MB`) vs `adaptive` | **p95 1,320.01 ms**, **11.8 MB heap** | **8.3× lower p95**, **2.3× lower heap** |
| **C — IO Knee `K=16` + 429s** | `static-naive` (`416.4/s`, `7,784` 429s, `730` DLQ) vs `adaptive` | **1,249.0 items/s**, **`0` 429s, `0` DLQ** | **3.0× throughput**, zero lost items |
| **D — AI/RAG Ingestion** (`flatMap 8×` + `batch 32`) | `static-naive` (`293.8/s`) vs `adaptive` | **1,376.9 items/s** (`io-embed=11`) | **4.7× throughput** (fan-out aware) |
| **E — Soak & Chaos + Drain** (`-Xmx64m`) | 5M items + chaos faults + live `drain()` | **567,939.1 items/s**, `15.2 MB` heap | **0 thread leak** (`36->36`, `eazypipeAlive=0`) |

- **JMH CPU microbenchmark (10k items × 2 stages)**: `EazyPipe` Static (`11.827 ops/s`) is **1.48× faster** than Project Reactor (`7.967 ops/s`), and Gen-2 `Adaptive` (`11.334 ops/s`) adds only **~4.2%** monitoring overhead while remaining **1.42× faster** than Reactor.

**Reproduce locally:**
```powershell
.\mvnw.cmd -B install -DskipTests
.\mvnw.cmd -B -f eazypipe-benchmarks\pom.xml package
java -jar eazypipe-benchmarks\target\benchmarks.jar -f 1 -wi 2 -i 3 -w 2s -r 3s -to 1m
java -Xmx64m -cp eazypipe-benchmarks\target\benchmarks.jar xyz.scarabya.eazypipe.bench.scenarios.ScenarioRunner all
```

---

## Building & Testing

The repository includes the **Maven Wrapper** (`mvnw` / `mvnw.cmd`), so Maven does not need to be installed globally.

**Linux / macOS:**
```bash
./mvnw clean verify
```

**Windows (PowerShell):**
```powershell
.\mvnw.cmd clean verify
```

---

## Roadmap & Architecture

EazyPipe 2.0 focuses on **end-to-end auto-balancing** across heterogeneous CPU/IO stages while preserving a slim, zero-dependency micro-library core. See [docs/EAZYPIPE_ADAPTIVE_PLAN.md](docs/EAZYPIPE_ADAPTIVE_PLAN.md) for the Gen-2 adaptive architecture (fan-out aware CPU budgeting, downstream propagation brake, Vegas/retry I/O control), micro-core refactoring plan, and Maven Central `2.0.0` release roadmap, and [docs/BENCHMARKS.md](docs/BENCHMARKS.md) for full benchmark results.

---

## License

Licensed under the **Apache License, Version 2.0**. See [LICENSE](LICENSE) for full details.
