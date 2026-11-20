# EazyPipe 2.0

**Concurrent, type-safe, backpressured data pipelines for Java 21+.**

EazyPipe lets you chain producer, transformation, and consumer stages in a clean fluent API while automatically managing **Java 21 Virtual Threads**, **bounded inter-stage backpressure**, **error propagation**, and **graceful lifecycle shutdown**.

---

## Features

- **Type-safe fluent API** — Built around standard Java generics, lambdas, and method references (no reflection, zero runtime dependencies).
- **Java 21 Virtual Threads by default** — Lightweight concurrency per stage out of the box, with optional daemon platform threads or caller-supplied `ExecutorService`.
- **Real backpressure** — Stages communicate via lock-based bounded FIFO channels (`ReentrantLock` + `Condition`) with zero busy-waiting and no virtual-thread pinning.
- **Per-stage parallelism & completion callbacks** — Configure worker count, buffer capacity, stage name, and an `onComplete` hook guaranteed to run once after every worker in the stage finishes.
- **Structured error handling** — Choose between `FAIL_FAST` (immediately aborts upstream/downstream channels and interrupts active workers) or `CONTINUE_ON_ITEM_ERROR` with custom `errorHandler` and skipped-error metrics.
- **Sync & async execution** — Run synchronously (`toList()`, `collect(...)`, `count()`, `run(...)`) or asynchronously via `PipeExecution<R>` (`AutoCloseable`, `CompletableFuture<R>`, `await(Duration)`, `cancel()`, `PipeStats`).

## Requirements

- **Java 21+**
- No external runtime dependencies (only JUnit 5 in `test` scope).

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
                .map(StageConfig.parallel(4), String::toUpperCase)
                .flatMap(s -> List.of(s, s + "!"))
                .toList();

        System.out.println(result);
    }
}
```

### Custom Producer (`PipeSource`), Multi-Item `PipeProcessor`, and `PipeSink`

```java
EazyPipe.<Integer>from(StageConfig.named("numbers"), emitter -> {
            for (int i = 1; i <= 1000; i++) {
                emitter.emit(i);
            }
        })
        .<String>stage(StageConfig.parallel(4), (n, emitter) -> {
            if (n % 2 == 0) {
                emitter.emit("even-" + n);
            }
        })
        .run(StageConfig.builder()
                .name("printer")
                .bufferCapacity(64)
                .onComplete(() -> System.out.println("Done!"))
                .build(),
                System.out::println);
```

### Stream Sources (`fromStream`)

Streams supplied via `EazyPipe.fromStream(...)` are lazily iterated and closed automatically via `try-with-resources`, even if a downstream stage fails or cancels:

```java
long lineCount = EazyPipe.fromStream(() -> Files.lines(Path.of("data.txt")))
        .filter(line -> !line.isBlank())
        .count();
```

---

## Configuration & Backpressure

### Per-Stage Options (`StageConfig`)

- `StageConfig.parallel(n)` — Run `n` concurrent workers for that stage.
- `StageConfig.withBuffer(capacity)` — Set bounded channel capacity (`0` inherits `PipeConfig.defaultBufferCapacity()`). When a stage is slower than upstream, the bounded channel applies backpressure and blocks upstream `emit(...)` calls without unbounded memory growth.
- `StageConfig.named("stage-name")` — Attach a human-readable stage name included in `PipeException.stageName()` and `errorHandler` callbacks.
- `StageConfig.builder()...onComplete(runnable).build()` — Combine all options including a completion hook invoked once when all workers in that stage finish normally.

### Global Options (`PipeConfig`)

```java
PipeConfig config = PipeConfig.builder()
        .defaultBufferCapacity(512)
        .executorMode(PipeConfig.ExecutorMode.VIRTUAL_THREADS) // or PLATFORM_THREADS / customExecutor(...)
        .errorStrategy(PipeConfig.ErrorStrategy.CONTINUE_ON_ITEM_ERROR)
        .errorHandler((stage, err) -> System.err.println(stage + ": " + err.getMessage()))
        .build();

List<Integer> parsed = EazyPipe.of("10", "invalid", "20")
        .configure(config)
        .map(StageConfig.named("parse"), Integer::parseInt)
        .toList();
```

When using `PipeConfig.withExecutor(myPool)` (or `.customExecutor(myPool)`), EazyPipe submits worker tasks to your executor and **never** shuts it down when the pipeline ends.

---

## Asynchronous Execution (`PipeExecution`)

```java
try (PipeExecution<List<String>> exec = EazyPipe.of("a", "b", "c")
        .map(String::toUpperCase)
        .startToList()) {

    List<String> items = exec.await(Duration.ofSeconds(5));
    PipeStats stats = exec.stats();
    System.out.printf("Items: %s | Elapsed: %s | Consumed: %d%n",
            items, stats.elapsed(), stats.itemsConsumed());
}
```

`PipeExecution<R>` also exposes `future()` (a defensive `CompletableFuture<R>` copy), `await()`, `join()`, `cancel()`, `isDone()`, `isFailed()`, and `isCancelled()`. Closing an unfinished `PipeExecution` cancels remaining work and waits for all workers to terminate cleanly.

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

## License

Licensed under the **Apache License, Version 2.0**. See [LICENSE](LICENSE) for full details.
