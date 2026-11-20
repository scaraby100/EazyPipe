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

- **Type-safe fluent API (`module xyz.scarabya.eazypipe`)** — Zero reflection, zero runtime dependencies, full JPMS module descriptor (`java.base` only).
- **Hybrid per-stage scheduling** — Run `IO_BOUND` stages on **Java 21 Virtual Threads**, `CPU_BOUND` stages on daemon platform threads under a shared CPU budget, or bring your own `ExecutorService`.
- **Real lock-based backpressure & overflow policies** — Bounded FIFO channels built on `ReentrantLock` + `Condition` (zero busy-waiting, zero virtual-thread pinning) supporting `BLOCK`, `DROP_OLDEST`, `DROP_NEWEST`, and `FAIL`.
- **Downstream-aware adaptive concurrency (`AdaptiveController`)** — Automatically rebalances `CPU_BOUND` permits (`CpuBudgetAllocator`) and tunes `IO_BOUND` concurrency via AIMD/Vegas (`IoConcurrencyLimiter`) using EMA telemetry, deadband hysteresis, persistence, and per-stage cooldowns so fast upstream stages never scale up into a full downstream wall.
- **Production operators & mini-DAG** — `map`, `mapConcurrentOrdered`, `filter`, `flatMap`, `batch(maxSize, maxWait)`, `rateLimit`, `tee`, `broadcast`, `route`, `EazyPipe.merge`, `peek`, and `ContextPropagator`.
- **Structured resilience & observability** — Per-stage `ErrorPolicy` (`FAIL_FAST`, `SKIP`, `DEAD_LETTER`, `RETRY` with exponential backoff + jitter), typed `DeadLetterHandler<T>`, graceful `drain()` vs immediate `cancel()`, `PipeExecution<R>` (`AutoCloseable` + `CompletableFuture<R>`), and `PipelineMetricsListener` SPI.

## Requirements

- **Java 21+**
- Zero runtime dependencies (JUnit 5 in `test` scope only).

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

### Adaptive Hybrid CPU/IO Pipeline with Retries, Batching & Dead-Letter Handling

```java
try (PipeExecution<Void> exec = EazyPipe.fromStream(() -> Files.lines(Path.of("docs.txt")))
        .map(StageConfig.cpuBound("parse-chunk")
                        .concurrency(1, 8)
                        .bufferCapacity(256)
                        .build(),
                line -> parseChunk(line))
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
                .adaptive(AdaptiveConfig.builder()
                        .tickInterval(Duration.ofMillis(250))
                        .cpuBudget(Runtime.getRuntime().availableProcessors())
                        .onDecision(decision -> System.out.println("Autoscaler: " + decision))
                        .build())
                .build())
        .start(StageConfig.ioBound("vector-db")
                        .concurrency(1, 8)
                        .build(),
                batch -> upsertBatch(batch))) {

    exec.await();
}
```

### Production Operators (`mapConcurrentOrdered`, `rateLimit`, `tee`, `broadcast`, `route`, `merge`)

- `mapConcurrentOrdered(StageConfig, mapper)` — Processes items concurrently across workers while emitting downstream in strict FIFO input order.
- `batch(maxSize, maxWait)` — Groups items into immutable `List` batches flushed on size or timeout.
- `rateLimit(permitsPerSecond)` — Smoothly paces downstream throughput using an abort-aware lock-based limiter.
- `tee(sideConfig, sideSink)` — Forks every passing item asynchronously to a side sink while keeping the main flow running.
- `runBroadcast(...)` / `startBroadcast(...)` — Concurrent fan-out to multiple independent terminal sinks.
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
    System.out.printf("Consumed=%d Dropped=%d Elapsed=%s%n",
            metrics.itemsConsumed(), metrics.itemsDropped(), metrics.elapsed());
}
```

- Attach thread-local context (`MDC`, trace IDs, tenant IDs) across worker and item boundaries via `PipeConfig.builder().contextPropagator(...)`.
- Observe live stage metrics, scaling decisions, errors, dead letters, and channel overflows via `PipeConfig.builder().metricsListener(...)`.
- Use `PipeConfig.withExecutor(myPool)` (or `.customExecutor(myPool)`) when supplying your own `ExecutorService` — EazyPipe never shuts down caller-owned executors.

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

EazyPipe 2.0 focuses on **end-to-end auto-balancing** across heterogeneous CPU/IO stages while preserving a slim, zero-dependency micro-library core. See [docs/EAZYPIPE_ADAPTIVE_PLAN.md](docs/EAZYPIPE_ADAPTIVE_PLAN.md) for the Gen-2 adaptive architecture (fan-out aware CPU budgeting, downstream propagation brake, Vegas/retry I/O control), micro-core refactoring plan, JMH proof-of-value benchmarks, and Maven Central `2.0.0` release roadmap.

---

## License

Licensed under the **Apache License, Version 2.0**. See [LICENSE](LICENSE) for full details.
