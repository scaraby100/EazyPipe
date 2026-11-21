/*
 * Copyright 2017-2026 Alessandro Patriarca.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package xyz.scarabya.eazypipe.bench.scenarios;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import xyz.scarabya.eazypipe.EazyPipe;
import xyz.scarabya.eazypipe.ErrorPolicy;
import xyz.scarabya.eazypipe.PipeConfig;
import xyz.scarabya.eazypipe.PipeExecution;
import xyz.scarabya.eazypipe.RetrySpec;
import xyz.scarabya.eazypipe.StageConfig;
import xyz.scarabya.eazypipe.adaptive.AdaptiveConfig;
import xyz.scarabya.eazypipe.bench.scenarios.ScenarioSupport.CsvMetricsRecorder;
import xyz.scarabya.eazypipe.bench.scenarios.ScenarioSupport.LatencyReservoir;
import xyz.scarabya.eazypipe.bench.scenarios.ScenarioSupport.ScenarioResult;
import xyz.scarabya.eazypipe.bench.scenarios.ScenarioSupport.TimedItem;

/**
 * Runner for the 5 dynamic proof-of-value scenarios (A–E) comparing static configurations
 * against EazyPipe Gen-2 end-to-end auto-balancing.
 */
public final class ScenarioRunner {

    private ScenarioRunner() {
    }

    public static void main(String[] args) throws Exception {
        String target = args.length == 0 ? "all" : args[0].toLowerCase(Locale.ROOT);
        boolean small = "small".equalsIgnoreCase(System.getProperty("scenario.scale", "full"));
        long defaultSoak = small ? 500_000L : 5_000_000L;
        long soakItems = Long.getLong("scenario.soakItems", defaultSoak);

        Path outDir = ScenarioSupport.resolveScenarioDir();
        List<ScenarioResult> results = new ArrayList<>();
        boolean soakPassed = true;

        if (target.equals("all") || target.equals("a")) {
            results.addAll(runScenarioA(outDir, small));
        }
        if (target.equals("all") || target.equals("b")) {
            results.addAll(runScenarioB(outDir, small));
        }
        if (target.equals("all") || target.equals("c")) {
            results.addAll(runScenarioC(outDir, small));
        }
        if (target.equals("all") || target.equals("d")) {
            results.addAll(runScenarioD(outDir, small));
        }
        if (target.equals("all") || target.equals("e")) {
            List<ScenarioResult> eResults = runScenarioE(outDir, soakItems);
            results.addAll(eResults);
            for (ScenarioResult r : eResults) {
                if (!r.extra().contains("PASS")) {
                    soakPassed = false;
                }
            }
        }

        System.out.println();
        System.out.println("| Scenario | Mode | Items | Elapsed (ms) | Throughput (items/s) | p50 (ms) | p95 (ms) | p99 (ms) | Final Concurrencies | Decisions | Extra |");
        System.out.println("| :--- | :--- | ---: | ---: | ---: | ---: | ---: | ---: | :--- | ---: | :--- |");
        for (ScenarioResult r : results) {
            System.out.println(r.toMarkdownRow());
        }
        System.out.println();
        System.out.println("CSV time-series written to: " + outDir.toAbsolutePath());

        if (!soakPassed) {
            System.err.println("Scenario E soak/thread-leak check FAILED!");
            System.exit(1);
        }
    }

    private static List<ScenarioResult> runScenarioA(Path outDir, boolean small) throws Exception {
        int items = small ? 1_200 : 16_000;
        int cpuBudget = Math.min(8, Math.max(4, Runtime.getRuntime().availableProcessors()));
        int naiveC = 2;
        int balancedStaticC = Math.max(1, cpuBudget / 2);

        List<ScenarioResult> out = new ArrayList<>();
        out.add(executeScenarioA(outDir, "static-naive", items, cpuBudget, naiveC, naiveC, false));
        out.add(executeScenarioA(outDir, "static-balanced", items, cpuBudget, balancedStaticC, balancedStaticC, false));
        out.add(executeScenarioA(outDir, "adaptive", items, cpuBudget, 1, cpuBudget, true));
        return out;
    }

    private static ScenarioResult executeScenarioA(
            Path outDir, String mode, int items, int cpuBudget, int minC, int maxC, boolean adaptive)
            throws Exception {
        int initialC = adaptive ? Math.min(maxC, 2) : maxC;
        AdaptiveConfig adaptiveCfg = adaptive
                ? AdaptiveConfig.balanced().toBuilder()
                        .tickInterval(Duration.ofMillis(60))
                        .tuning(AdaptiveConfig.Tuning.balanced().toBuilder()
                                .persistenceTicks(2)
                                .cooldown(Duration.ofMillis(120))
                                .build())
                        .build()
                : AdaptiveConfig.disabled();

        try (CsvMetricsRecorder recorder = new CsvMetricsRecorder(outDir.resolve("A-" + mode + ".csv"))) {
            PipeConfig cfg = PipeConfig.builder()
                    .cpuBudget(cpuBudget)
                    .adaptive(adaptiveCfg)
                    .metricsListener(recorder)
                    .build();
            LatencyReservoir latencies = new LatencyReservoir();
            LongAdder consumed = new LongAdder();

            StageConfig c1 = StageConfig.cpuBound("cpu-1").concurrency(minC, initialC, maxC).bufferCapacity(32).build();
            StageConfig c2 = StageConfig.cpuBound("cpu-2").concurrency(minC, initialC, maxC).bufferCapacity(32).build();
            StageConfig c3 = StageConfig.cpuBound("cpu-3").concurrency(minC, initialC, maxC).bufferCapacity(32).build();

            long t0 = System.nanoTime();
            EazyPipe.<TimedItem>from(emitter -> {
                        for (int i = 0; i < items; i++) {
                            emitter.emit(new TimedItem(i, System.nanoTime()));
                        }
                    })
                    .configure(cfg)
                    .map(c1, item -> {
                        ScenarioSupport.busySpinNanos(item.id() < items / 2 ? 2_500_000L : 50_000L);
                        return item;
                    })
                    .map(c2, item -> {
                        ScenarioSupport.busySpinNanos(50_000L);
                        return item;
                    })
                    .map(c3, item -> {
                        ScenarioSupport.busySpinNanos(item.id() >= items / 2 ? 2_500_000L : 50_000L);
                        return item;
                    })
                    .run(item -> {
                        latencies.record(System.nanoTime() - item.createdNanos());
                        consumed.increment();
                    });
            long elapsedMs = Math.max(1L, (System.nanoTime() - t0) / 1_000_000L);
            return new ScenarioResult(
                    "A (Shifting CPU)", mode, consumed.sum(), elapsedMs,
                    consumed.sum() * 1000.0 / elapsedMs,
                    latencies.percentileMs(0.50), latencies.percentileMs(0.95), latencies.percentileMs(0.99),
                    recorder.formatFinalConcurrencies(), recorder.decisionCount(),
                    "cpuBudget=" + cpuBudget);
        }
    }

    private static List<ScenarioResult> runScenarioB(Path outDir, boolean small) throws Exception {
        int items = small ? 250 : 1_200;
        List<ScenarioResult> out = new ArrayList<>();
        out.add(executeScenarioB(outDir, "static-naive", items, false));
        out.add(executeScenarioB(outDir, "adaptive", items, true));
        return out;
    }

    private static ScenarioResult executeScenarioB(Path outDir, String mode, int items, boolean adaptive)
            throws Exception {
        int bufferCap = adaptive ? 32 : 512;
        int initUp = adaptive ? 8 : 32;
        int minUp = adaptive ? 1 : 32;
        AdaptiveConfig adaptiveCfg = adaptive
                ? AdaptiveConfig.lowLatency().toBuilder()
                        .tickInterval(Duration.ofMillis(50))
                        .tuning(AdaptiveConfig.Tuning.lowLatency().toBuilder()
                                .cooldown(Duration.ofMillis(100))
                                .build())
                        .build()
                : AdaptiveConfig.disabled();

        System.gc();
        Thread.sleep(50);
        ScenarioSupport.resetHeapPeak();
        try (CsvMetricsRecorder recorder = new CsvMetricsRecorder(outDir.resolve("B-" + mode + ".csv"))) {
            PipeConfig cfg = PipeConfig.builder().adaptive(adaptiveCfg).metricsListener(recorder).build();
            LatencyReservoir latencies = new LatencyReservoir();
            LongAdder consumed = new LongAdder();

            StageConfig io1 = StageConfig.ioBound("io-1").concurrency(minUp, initUp, 32).bufferCapacity(bufferCap).build();
            StageConfig io2 = StageConfig.ioBound("io-2").concurrency(minUp, initUp, 32).bufferCapacity(bufferCap).build();
            StageConfig ioSink = StageConfig.ioBound("io-sink").concurrency(2, 2, 4).bufferCapacity(bufferCap).build();

            long t0 = System.nanoTime();
            EazyPipe.<TimedItem>from(emitter -> {
                        for (int i = 0; i < items; i++) {
                            emitter.emit(new TimedItem(i, System.nanoTime(), new byte[16_384]));
                        }
                    })
                    .configure(cfg)
                    .map(io1, item -> {
                        Thread.sleep(1);
                        return item;
                    })
                    .map(io2, item -> {
                        Thread.sleep(1);
                        return item;
                    })
                    .run(ioSink, item -> {
                        Thread.sleep(item.id() < (items * 3L) / 10L ? 1 : 20);
                        latencies.record(System.nanoTime() - item.createdNanos());
                        consumed.increment();
                    });
            long elapsedMs = Math.max(1L, (System.nanoTime() - t0) / 1_000_000L);
            double peakMb = ScenarioSupport.peakHeapMb();
            return new ScenarioResult(
                    "B (Downstream Stall)", mode, consumed.sum(), elapsedMs,
                    consumed.sum() * 1000.0 / elapsedMs,
                    latencies.percentileMs(0.50), latencies.percentileMs(0.95), latencies.percentileMs(0.99),
                    recorder.formatFinalConcurrencies(), recorder.decisionCount(),
                    String.format(Locale.ROOT, "peakHeap=%.1fMB brakes=%d", peakMb, recorder.brakeCount()));
        }
    }

    private static List<ScenarioResult> runScenarioC(Path outDir, boolean small) throws Exception {
        int items = small ? 350 : 6_000;
        List<ScenarioResult> out = new ArrayList<>();
        out.add(executeScenarioC(outDir, "static-naive", items, 48, 48, 48, false));
        out.add(executeScenarioC(outDir, "static-oracle", items, 16, 16, 16, false));
        out.add(executeScenarioC(outDir, "adaptive", items, 1, 4, 48, true));
        return out;
    }

    private static ScenarioResult executeScenarioC(
            Path outDir, String mode, int items, int minC, int initC, int maxC, boolean adaptive)
            throws Exception {
        AdaptiveConfig adaptiveCfg = adaptive
                ? AdaptiveConfig.balanced().toBuilder()
                        .tickInterval(Duration.ofMillis(50))
                        .tuning(AdaptiveConfig.Tuning.balanced().toBuilder()
                                .persistenceTicks(2)
                                .cooldown(Duration.ofMillis(100))
                                .build())
                        .build()
                : AdaptiveConfig.disabled();

        try (CsvMetricsRecorder recorder = new CsvMetricsRecorder(outDir.resolve("C-" + mode + ".csv"))) {
            PipeConfig cfg = PipeConfig.builder().adaptive(adaptiveCfg).metricsListener(recorder).build();
            LatencyReservoir latencies = new LatencyReservoir();
            AtomicInteger inFlight = new AtomicInteger();
            LongAdder retries = new LongAdder();
            LongAdder completed = new LongAdder();
            LongAdder droppedDlq = new LongAdder();

            StageConfig apiStage = StageConfig.ioBound("ext-api")
                    .concurrency(minC, initC, maxC)
                    .bufferCapacity(64)
                    .retry(RetrySpec.fixed(3, Duration.ofMillis(2)))
                    .deadLetter((item, err, st) -> droppedDlq.increment())
                    .build();

            long t0 = System.nanoTime();
            EazyPipe.<TimedItem>from(emitter -> {
                        for (int i = 0; i < items; i++) {
                            emitter.emit(new TimedItem(i, System.nanoTime()));
                        }
                    })
                    .configure(cfg)
                    .map(apiStage, item -> {
                        int c = inFlight.incrementAndGet();
                        try {
                            long sleepMs = c <= 16 ? 5L : Math.round(5.0 * Math.pow(c / 16.0, 2.0));
                            Thread.sleep(Math.min(200L, sleepMs));
                            if (c > 16) {
                                double p429 = Math.min(0.60, (c - 16) * 0.04);
                                if (ThreadLocalRandom.current().nextDouble() < p429) {
                                    retries.increment();
                                    throw new IllegalStateException("HTTP 429 at inFlight=" + c);
                                }
                            }
                            return item;
                        } finally {
                            inFlight.decrementAndGet();
                        }
                    })
                    .run(item -> {
                        latencies.record(System.nanoTime() - item.createdNanos());
                        completed.increment();
                    });
            long elapsedMs = Math.max(1L, (System.nanoTime() - t0) / 1_000_000L);
            return new ScenarioResult(
                    "C (IO Knee K=16)", mode, completed.sum(), elapsedMs,
                    completed.sum() * 1000.0 / elapsedMs,
                    latencies.percentileMs(0.50), latencies.percentileMs(0.95), latencies.percentileMs(0.99),
                    recorder.formatFinalConcurrencies(), recorder.decisionCount(),
                    "K=16 429s=" + retries.sum() + " dlq=" + droppedDlq.sum());
        }
    }

    private static List<ScenarioResult> runScenarioD(Path outDir, boolean small) throws Exception {
        int docs = small ? 80 : 1_200;
        List<ScenarioResult> out = new ArrayList<>();
        out.add(executeScenarioD(outDir, "static-naive", docs, 8, 2, 1, false));
        out.add(executeScenarioD(outDir, "static-oracle", docs, 1, 24, 4, false));
        out.add(executeScenarioD(outDir, "adaptive", docs, 1, 2, 1, true));
        return out;
    }

    private static ScenarioResult executeScenarioD(
            Path outDir, String mode, int docs, int parseC, int embedC, int dbC, boolean adaptive)
            throws Exception {
        int cpuBudget = Math.max(4, Runtime.getRuntime().availableProcessors());
        AdaptiveConfig adaptiveCfg = adaptive
                ? AdaptiveConfig.balanced().toBuilder()
                        .tickInterval(Duration.ofMillis(50))
                        .tuning(AdaptiveConfig.Tuning.balanced().toBuilder()
                                .persistenceTicks(2)
                                .cooldown(Duration.ofMillis(100))
                                .build())
                        .build()
                : AdaptiveConfig.disabled();

        try (CsvMetricsRecorder recorder = new CsvMetricsRecorder(outDir.resolve("D-" + mode + ".csv"))) {
            PipeConfig cfg = PipeConfig.builder()
                    .cpuBudget(cpuBudget)
                    .adaptive(adaptiveCfg)
                    .metricsListener(recorder)
                    .build();
            LatencyReservoir latencies = new LatencyReservoir();
            LongAdder chunksConsumed = new LongAdder();

            StageConfig parseCfg = adaptive
                    ? StageConfig.cpuBound("cpu-parse").concurrency(1, 2, cpuBudget).bufferCapacity(32).build()
                    : StageConfig.cpuBound("cpu-parse").concurrency(parseC, parseC, parseC).bufferCapacity(32).build();
            StageConfig embedCfg = adaptive
                    ? StageConfig.ioBound("io-embed").concurrency(2, 4, 32).bufferCapacity(128)
                            .retry(RetrySpec.fixed(2, Duration.ofMillis(2))).deadLetter((i, e, s) -> { }).build()
                    : StageConfig.ioBound("io-embed").concurrency(embedC, embedC, embedC).bufferCapacity(128)
                            .retry(RetrySpec.fixed(2, Duration.ofMillis(2))).deadLetter((i, e, s) -> { }).build();
            StageConfig dbCfg = adaptive
                    ? StageConfig.ioBound("vector-db").concurrency(1, 2, 8).bufferCapacity(32).build()
                    : StageConfig.ioBound("vector-db").concurrency(dbC, dbC, dbC).bufferCapacity(32).build();

            long t0 = System.nanoTime();
            EazyPipe.<TimedItem>from(emitter -> {
                        for (int i = 0; i < docs; i++) {
                            emitter.emit(new TimedItem(i, System.nanoTime()));
                        }
                    })
                    .configure(cfg)
                    .map(parseCfg, doc -> {
                        ScenarioSupport.busySpinNanos(400_000L);
                        return doc;
                    })
                    .flatMap(doc -> {
                        List<TimedItem> chunks = new ArrayList<>(8);
                        for (int c = 0; c < 8; c++) {
                            chunks.add(new TimedItem(doc.id() * 8L + c, doc.createdNanos()));
                        }
                        return chunks;
                    })
                    .rateLimit(3000.0, 64)
                    .map(embedCfg, chunk -> {
                        Thread.sleep(3);
                        return chunk;
                    })
                    .batch("batch-32", 32, Duration.ofMillis(50))
                    .run(dbCfg, batch -> {
                        Thread.sleep(6);
                        long now = System.nanoTime();
                        for (TimedItem item : batch) {
                            latencies.record(now - item.createdNanos());
                            chunksConsumed.increment();
                        }
                    });
            long elapsedMs = Math.max(1L, (System.nanoTime() - t0) / 1_000_000L);
            return new ScenarioResult(
                    "D (AI/RAG Fan-Out)", mode, chunksConsumed.sum(), elapsedMs,
                    chunksConsumed.sum() * 1000.0 / elapsedMs,
                    latencies.percentileMs(0.50), latencies.percentileMs(0.95), latencies.percentileMs(0.99),
                    recorder.formatFinalConcurrencies(), recorder.decisionCount(),
                    docs + " docs -> 8x chunks");
        }
    }

    private static List<ScenarioResult> runScenarioE(Path outDir, long soakItems) throws Exception {
        // Warm up JVM virtual-thread carrier pool before taking baseline thread count.
        int cores = Math.max(2, Runtime.getRuntime().availableProcessors());
        List<Thread> warmup = new ArrayList<>(cores);
        for (int i = 0; i < cores; i++) {
            warmup.add(Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        for (Thread t : warmup) {
            t.join();
        }

        ThreadMXBean tBean = ManagementFactory.getThreadMXBean();
        int threadsBefore = tBean.getThreadCount();
        ScenarioSupport.resetHeapPeak();

        AdaptiveConfig adaptiveCfg = AdaptiveConfig.balanced().toBuilder()
                .tickInterval(Duration.ofMillis(100))
                .build();

        long elapsedSoakMs;
        long consumedSoak;
        int soakDecisions;
        String soakConcurrencies;
        try (CsvMetricsRecorder recorder = new CsvMetricsRecorder(outDir.resolve("E-soak.csv"))) {
            PipeConfig cfg = PipeConfig.builder()
                    .errorStrategy(PipeConfig.ErrorStrategy.CONTINUE_ON_ITEM_ERROR)
                    .adaptive(adaptiveCfg)
                    .metricsListener(recorder)
                    .build();

            StageConfig cpuCfg = StageConfig.cpuBound("soak-cpu")
                    .concurrency(1, 2, Math.max(2, Runtime.getRuntime().availableProcessors()))
                    .bufferCapacity(256)
                    .build();
            StageConfig chaosIoCfg = StageConfig.ioBound("soak-io")
                    .concurrency(2, 4, 16)
                    .bufferCapacity(256)
                    .retry(RetrySpec.fixed(1, Duration.ZERO))
                    .errorPolicy(ErrorPolicy.RETRY)
                    .deadLetter((item, err, st) -> { })
                    .build();

            LongAdder consumed = new LongAdder();
            AtomicInteger transientToggle = new AtomicInteger();
            long t0 = System.nanoTime();
            EazyPipe.<Long>from(emitter -> {
                        for (long i = 0; i < soakItems; i++) {
                            emitter.emit(i);
                        }
                    })
                    .configure(cfg)
                    .map(cpuCfg, n -> n + 1L)
                    .map(chaosIoCfg, n -> {
                        if (n % 5_000L == 0L) {
                            throw new IllegalArgumentException("fatal-chaos");
                        }
                        if (n % 1_000L == 0L && (transientToggle.incrementAndGet() & 1) == 1) {
                            throw new IllegalStateException("transient-chaos");
                        }
                        return n;
                    })
                    .run(n -> consumed.increment());
            elapsedSoakMs = Math.max(1L, (System.nanoTime() - t0) / 1_000_000L);
            consumedSoak = consumed.sum();
            soakDecisions = recorder.decisionCount();
            soakConcurrencies = recorder.formatFinalConcurrencies();
        }

        // Part 2: mid-run graceful drain verification.
        long drainElapsedMs;
        long drainConsumed;
        try (CsvMetricsRecorder drainRec = new CsvMetricsRecorder(outDir.resolve("E-drain.csv"))) {
            PipeExecution<Void> exec = EazyPipe.<Long>from(emitter -> {
                        long i = 0;
                        while (!Thread.currentThread().isInterrupted()) {
                            emitter.emit(i++);
                        }
                    })
                    .configure(PipeConfig.builder().adaptive(adaptiveCfg).metricsListener(drainRec).build())
                    .map(StageConfig.ioBound("drain-io").concurrency(1, 4, 8).build(), n -> n * 2L)
                    .start(n -> { });
            long d0 = System.nanoTime();
            Thread.sleep(150);
            exec.drain();
            drainElapsedMs = Math.max(1L, (System.nanoTime() - d0) / 1_000_000L);
            drainConsumed = exec.stats().itemsConsumed();
            if (exec.stats().itemsProduced() != drainConsumed) {
                throw new AssertionError("Drain mismatch: produced="
                        + exec.stats().itemsProduced() + ", consumed=" + drainConsumed);
            }
        }

        Thread.sleep(250);
        int threadsAfter = tBean.getThreadCount();
        Set<Thread> liveThreads = Thread.getAllStackTraces().keySet();
        long liveEazyThreads = liveThreads.stream()
                .filter(Thread::isAlive)
                .filter(t -> t.getName().startsWith("eazypipe-"))
                .count();
        boolean leakFree = liveEazyThreads == 0 && Math.abs(threadsAfter - threadsBefore) <= 1;
        String status = leakFree ? "PASS" : "FAIL";
        double peakMb = ScenarioSupport.peakHeapMb();

        return List.of(
                new ScenarioResult(
                        "E (Soak & Chaos)", "adaptive", consumedSoak, elapsedSoakMs,
                        consumedSoak * 1000.0 / elapsedSoakMs,
                        0.0, 0.0, 0.0, soakConcurrencies, soakDecisions,
                        String.format(Locale.ROOT, "%s peakHeap=%.1fMB thr=%d->%d eazypipeAlive=%d",
                                status, peakMb, threadsBefore, threadsAfter, liveEazyThreads)),
                new ScenarioResult(
                        "E (Graceful Drain)", "adaptive", drainConsumed, drainElapsedMs,
                        drainConsumed * 1000.0 / drainElapsedMs,
                        0.0, 0.0, 0.0, "-", 0, status));
    }
}
