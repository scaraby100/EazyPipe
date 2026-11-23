# EazyPipe — Contesto Locale di Workspace

## Panoramica e Stack
- **Repository**: `https://github.com/jucasoft/EazyPipe.git` (branch `feat/v2-adaptive-gen2` allineato a `master`, tag `v2.0.0-RC1`)
- **Licenza**: Apache License 2.0 (`LICENSE`)
- **Build system**: Maven Wrapper `3.3.4` (`mvnw` LF / `mvnw.cmd`) con Maven `3.9.16`, coordinate `xyz.scarabya:EazyPipe:2.0.0-RC1`, packaging `jar` (+ `sources` `3.4.0` e `javadoc` `3.12.0`)
- **Target Java & JPMS**: Java 21 LTS (`maven.compiler.release=21`), `-Xlint:all -Werror`, modulo JPMS `xyz.scarabya.eazypipe` (`src/main/java/module-info.java`), **52** file Java in `src/main/java` (tutti `<= 400` LOC non-blank/comment)
- **Dipendenze**: zero dipendenze runtime (`java.base`); JUnit 5 (`5.14.4` via `junit-bom`) in scope `test`
- **CI/CD & Release**: GitHub Actions `.github/workflows/ci.yml` (`ubuntu-latest` e `windows-latest`, Temurin JDK 21) e `.github/workflows/release.yml` (pubblicazione automatica JAR/sources/javadoc/`SHA256SUMS.txt` su GitHub Releases per tag `v*`)

## Comandi Operativi (Windows)
- `mvn` non è nel `PATH` e `JAVA_HOME` non è impostata globalmente su questa macchina; usare il wrapper con JDK 21:
  ```powershell
  $env:JAVA_HOME = 'C:\dev\kit\jdk-21.0.11+10'; .\mvnw.cmd -B clean verify
  ```
- `maven-surefire-plugin` (`3.6.1`) include `--add-opens` / `--add-reads` per eseguire le suite package-private e `internal.adaptive` sul module-path JPMS.
- **Benchmark standalone (`eazypipe-benchmarks/`)**:
  ```powershell
  $env:JAVA_HOME = 'C:\dev\kit\jdk-21.0.11+10'
  .\mvnw.cmd -B install -DskipTests
  .\mvnw.cmd -B -f eazypipe-benchmarks\pom.xml package
  & 'C:\dev\kit\jdk-21.0.11+10\bin\java.exe' -jar eazypipe-benchmarks\target\benchmarks.jar -f 1 -wi 2 -i 3 -w 2s -r 3s -to 1m
  & 'C:\dev\kit\jdk-21.0.11+10\bin\java.exe' -Xmx64m -cp eazypipe-benchmarks\target\benchmarks.jar xyz.scarabya.eazypipe.bench.scenarios.ScenarioRunner all
  ```

## Architettura (`src/main/java/`)

### `xyz.scarabya.eazypipe` *(esportato)*
- **API fluente e sorgenti/operatori**: `EazyPipe` (`from`, `fromIterable`, `fromStream`, `merge`, `of`), `Pipeline<T>` immutabile (`map`, `mapConcurrentOrdered`, `filter`, `flatMap`, `batch`, `rateLimit` con burst, `tee`, `stage`, `peek`, `configure`, `adaptive()`, `adaptive(AdaptiveConfig)`, `start`/`run`, `startBroadcast`/`runBroadcast`, `startRoute`/`runRoute`, `startCollect`/`collect`, `startToList`/`toList`, `count`).
- **Interfacce funzionali**: `PipeSource`, `PipeProcessor`, `PipeSink`, `PipeEmitter`, `PipeFunction`, `PipePredicate`, `DeadLetterHandler`, `ContextPropagator`.
- **Configurazione & Lifecycle**: `StageConfig` (`StageKind`, `concurrency(min, [initial,] max)`, `bufferCapacity`, `OverflowPolicy`, `ErrorPolicy`, `RetrySpec`, `DeadLetterHandler`, `onComplete`), `PipeConfig` (`ExecutorMode`, `ErrorStrategy`, `cpuBudget` unica fonte di verità, `withAdaptive(...)`, `AdaptiveConfig`, `ContextPropagator`, `PipelineMetricsListener`), `PipeExecution<R>` (`AutoCloseable`, `CompletableFuture<R>`, `await`/`join`, `requestDrain`/`drain`, `cancel`, `stats`, `metricsSnapshot`), `PipeStats`, `PipeException`, `ChannelOverflowException`.
- **Interni package-private (lock-based, zero pinning Virtual Threads, runtime modulare)**: `BoundedChannel` (`ReentrantLock` + `Condition`, `OverflowPolicy`, `trySend`, `receiveBatch` con signal immediato, `receiveSequenced`, wait telemetry), `BroadcastItem`, `ContextSupport`, `CpuWorkerPool` (pool fisso platform daemon condiviso su `cpuBudget`), `DynamicPermitPool` (lazy shrink), `LifecycleController`, `OrderedReorderBuffer`, `PipeAbortedException`, `PipeDrainingException`, `PipelineOrchestrator`, `PipelineRuntime`, `StageDefinition`, `StageExecutionPolicy`, `StageScheduler`, `StageTaskPlanner`, `StageWorkerLoops`, `TerminalWorkerLoops`, `ThrowingRunnable`, `TokenBucketRateLimiter` (GCRA privo di deriva con burst), `WorkerTask`.

### `xyz.scarabya.eazypipe.adaptive` *(esportato)*
- Contratti pubblici minimi: `AdaptiveConfig` (record con `Tuning`, preset `balanced()`, `defaults()`, `throughput()`, `lowLatency()`, `disabled()`), `StageState` (`STARVED`, `BACKPRESSURED`, `BOTTLENECK`, `BALANCED`), `ScalingDecision` (con `explain()`).

### `xyz.scarabya.eazypipe.internal.adaptive` *(NON esportato in `module-info.java`)*
- Motore adattivo Gen-2: `AdaptiveController`, `StageTelemetry` (normalizzazione per concurrency, decadimento baseline, fan-out EMA, retry rate, Schmitt trigger su `deadbandLow`/`deadbandHigh`), `TopologyAnalyzer` (flussi cumulativi $r_i$, dominant bottleneck, downstream brake one-tick), `CpuBudgetAllocator` (indicizzato per posizione e pesato su fan-out), `IoConcurrencyLimiter` (Vegas + backoff retry + gradient check).

### `xyz.scarabya.eazypipe.metrics` *(esportato)*
- SPI zero-dependency: `PipelineMetricsListener`, `PipelineMetricsSnapshot` (con `summary()`), `StageMetricsSnapshot` (con `fanOutRatio`, `relativeFlow`).

## Benchmark, Test e Note Locali
- **Benchmark standalone (`eazypipe-benchmarks/`)**: `PipelineThroughputBenchmark` (JMH `1.37` vs Reactor `3.7.0`, manual `BlockingQueue`, `IntStream.parallel()`), `ScenarioRunner` / `ScenarioSupport` (Scenari A–E e CSV time-series in `eazypipe-benchmarks/target/scenarios/`). Report pubblicato in `docs/BENCHMARKS.md`.
- **Suite di test (`src/test/java/`)**: **11** classi JUnit 5, **83** test verdi (`BoundedChannelTest`, `DynamicPermitPoolTest`, `EazyPipeConcurrencyTest`, `EazyPipeCoreTest`, `EazyPipeErrorHandlingTest`, `EazyPipeOperatorsTest`, `EazyPipeResilienceTest`, `TokenBucketRateLimiterTest`, `internal.adaptive.AdaptiveControllerTest`, `internal.adaptive.StageTelemetryTest`, `internal.adaptive.TopologyAnalyzerTest`).
- **Documentazione & contesto tracciato**: `GEMINI.md`, `docs/EAZYPIPE_2_0_PLAN.md`, `docs/EAZYPIPE_ADAPTIVE_PLAN.md` e `docs/BENCHMARKS.md` sono tracciati in Git su `feat/v2-adaptive-gen2` / `master`.
