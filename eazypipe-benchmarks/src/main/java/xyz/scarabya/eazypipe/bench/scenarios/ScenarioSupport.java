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

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import xyz.scarabya.eazypipe.adaptive.ScalingDecision;
import xyz.scarabya.eazypipe.metrics.PipelineMetricsListener;
import xyz.scarabya.eazypipe.metrics.StageMetricsSnapshot;

/**
 * Shared instrumentation helpers (latency sampling, CSV metrics recording, heap peak tracking, CPU spin)
 * for dynamic proof-of-value scenarios.
 */
final class ScenarioSupport {

    private ScenarioSupport() {
    }

    record TimedItem(long id, long createdNanos, byte[] payload) {
        TimedItem(long id, long createdNanos) {
            this(id, createdNanos, null);
        }
    }

    record ScenarioResult(
            String scenario,
            String mode,
            long items,
            long elapsedMs,
            double throughputPerSec,
            double p50Ms,
            double p95Ms,
            double p99Ms,
            String finalConcurrencies,
            int decisions,
            String extra) {

        String toMarkdownRow() {
            return String.format(
                    Locale.ROOT,
                    "| %s | %s | %d | %d | %.1f | %.2f | %.2f | %.2f | %s | %d | %s |",
                    scenario, mode, items, elapsedMs, throughputPerSec,
                    p50Ms, p95Ms, p99Ms, finalConcurrencies, decisions, extra);
        }
    }

    static final class LatencyReservoir {
        private static final int MAX_SAMPLES = 16_384;
        private final long[] samples = new long[MAX_SAMPLES];
        private long seen;
        private int count;

        synchronized void record(long nanos) {
            seen++;
            if (count < MAX_SAMPLES) {
                samples[count++] = nanos;
            } else {
                long idx = ThreadLocalRandom.current().nextLong(seen);
                if (idx < MAX_SAMPLES) {
                    samples[(int) idx] = nanos;
                }
            }
        }

        synchronized double percentileMs(double p) {
            if (count == 0) {
                return 0.0;
            }
            long[] copy = Arrays.copyOf(samples, count);
            Arrays.sort(copy);
            int idx = Math.min(copy.length - 1, Math.max(0, (int) Math.ceil(p * copy.length) - 1));
            return copy[idx] / 1_000_000.0;
        }
    }

    static final class CsvMetricsRecorder implements PipelineMetricsListener, AutoCloseable {
        private final BufferedWriter writer;
        private final long startNanos = System.nanoTime();
        private final Map<String, Integer> latestConcurrencies = new LinkedHashMap<>();
        private final AtomicInteger decisionCount = new AtomicInteger();
        private final AtomicInteger brakeCount = new AtomicInteger();

        CsvMetricsRecorder(Path csvPath) throws IOException {
            Files.createDirectories(csvPath.getParent());
            this.writer = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8);
            this.writer.write("elapsed_ms,stage,kind,state,target,active,completed,svc_ms,thr_per_s,in_fill,out_fill,fanout,flow");
            this.writer.newLine();
        }

        @Override
        public synchronized void onStageMetrics(StageMetricsSnapshot s) {
            latestConcurrencies.put(s.stageName(), s.targetConcurrency());
            long elapsedMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
            try {
                writer.write(String.format(
                        Locale.ROOT,
                        "%d,%s,%s,%s,%d,%d,%d,%.3f,%.2f,%.4f,%.4f,%s,%s",
                        elapsedMs,
                        s.stageName(),
                        s.stageKind(),
                        s.stageState(),
                        s.targetConcurrency(),
                        s.activeWorkers(),
                        s.itemsCompleted(),
                        s.emaServiceTimeMillis(),
                        s.emaThroughputPerSec(),
                        s.inputFillRatio(),
                        s.outputFillRatio(),
                        Double.isNaN(s.fanOutRatio()) ? "" : String.format(Locale.ROOT, "%.3f", s.fanOutRatio()),
                        Double.isNaN(s.relativeFlow()) ? "" : String.format(Locale.ROOT, "%.3f", s.relativeFlow())));
                writer.newLine();
            } catch (IOException ignored) {
                // Keep metrics listener non-blocking/non-fatal.
            }
        }

        @Override
        public void onScalingDecision(ScalingDecision decision) {
            decisionCount.incrementAndGet();
            if (decision.reason().startsWith("Downstream brake")) {
                brakeCount.incrementAndGet();
            }
        }

        synchronized String formatFinalConcurrencies() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> entry : latestConcurrencies.entrySet()) {
                if ("source".equals(entry.getKey())) {
                    continue;
                }
                if (!sb.isEmpty()) {
                    sb.append(", ");
                }
                sb.append(entry.getKey()).append('=').append(entry.getValue());
            }
            return sb.isEmpty() ? "-" : sb.toString();
        }

        int decisionCount() {
            return decisionCount.get();
        }

        int brakeCount() {
            return brakeCount.get();
        }

        @Override
        public synchronized void close() throws IOException {
            writer.flush();
            writer.close();
        }
    }

    static Path resolveScenarioDir() {
        Path sub = Path.of("eazypipe-benchmarks");
        if (Files.isDirectory(sub)) {
            return sub.resolve("target").resolve("scenarios");
        }
        return Path.of("target", "scenarios");
    }

    static void busySpinNanos(long nanos) {
        long target = System.nanoTime() + nanos;
        long acc = 0;
        while (System.nanoTime() < target) {
            acc += System.nanoTime() & 1L;
        }
        if (acc == Long.MIN_VALUE) {
            throw new IllegalStateException("Unreachable");
        }
    }

    static void resetHeapPeak() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                pool.resetPeakUsage();
            }
        }
    }

    static double peakHeapMb() {
        long bytes = 0L;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP && pool.getPeakUsage() != null) {
                bytes += pool.getPeakUsage().getUsed();
            }
        }
        return bytes / (1024.0 * 1024.0);
    }
}
