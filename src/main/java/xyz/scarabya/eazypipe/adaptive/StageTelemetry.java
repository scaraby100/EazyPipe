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
package xyz.scarabya.eazypipe.adaptive;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Low-overhead, lock-free per-stage telemetry collector computing exponential moving averages (EMA)
 * of service latency, throughput, channel fill ratios, error rates, and blocking wait ratios.
 */
public final class StageTelemetry {

    /**
     * Immutable telemetry window reading emitted by {@link #sample}.
     */
    public record Snapshot(
            long completedItems,
            long errorItems,
            long droppedItems,
            int activeWorkers,
            double emaServiceNanos,
            double baselineServiceNanos,
            double emaThroughputPerSec,
            double emaErrorRate,
            double emaInputFill,
            double emaOutputFill,
            double emaInputWaitRatio,
            double emaOutputWaitRatio) {

        public double emaServiceMillis() {
            return emaServiceNanos / 1_000_000.0;
        }
    }

    private final LongAdder completedItems = new LongAdder();
    private final LongAdder errorItems = new LongAdder();
    private final LongAdder serviceTimeNanos = new LongAdder();
    private final AtomicInteger activeWorkers = new AtomicInteger();

    private long prevCompleted;
    private long prevErrors;
    private long prevServiceNanos;
    private long prevInputWaitNanos;
    private long prevOutputWaitNanos;
    private boolean initialized;
    private boolean hasLatencySample;

    private double emaServiceNanos;
    private double baselineServiceNanos;
    private double emaThroughputPerSec;
    private double emaErrorRate;
    private double emaInputFill;
    private double emaOutputFill;
    private double emaInputWaitRatio;
    private double emaOutputWaitRatio;
    private volatile Snapshot latestSnapshot = new Snapshot(0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);

    /**
     * Creates a new empty stage telemetry collector.
     */
    public StageTelemetry() {
    }

    public void onProcessingStart() {
        activeWorkers.incrementAndGet();
    }

    public void onProcessingSuccess(long pureServiceNanos) {
        completedItems.increment();
        serviceTimeNanos.add(Math.max(0L, pureServiceNanos));
        activeWorkers.decrementAndGet();
    }

    public void onProcessingError(long pureServiceNanos) {
        errorItems.increment();
        serviceTimeNanos.add(Math.max(0L, pureServiceNanos));
        activeWorkers.decrementAndGet();
    }

    public void onProcessingAborted() {
        activeWorkers.decrementAndGet();
    }

    public synchronized Snapshot sample(
            long windowNanos,
            double emaAlpha,
            double rawInputFill,
            double rawOutputFill,
            long totalInputWaitNanos,
            long totalOutputWaitNanos,
            long totalDropped) {

        long curCompleted = completedItems.sum();
        long curErrors = errorItems.sum();
        long curServiceNanos = serviceTimeNanos.sum();

        long deltaCompleted = Math.max(0L, curCompleted - prevCompleted);
        long deltaErrors = Math.max(0L, curErrors - prevErrors);
        long deltaService = Math.max(0L, curServiceNanos - prevServiceNanos);
        long deltaInWait = Math.max(0L, totalInputWaitNanos - prevInputWaitNanos);
        long deltaOutWait = Math.max(0L, totalOutputWaitNanos - prevOutputWaitNanos);

        prevCompleted = curCompleted;
        prevErrors = curErrors;
        prevServiceNanos = curServiceNanos;
        prevInputWaitNanos = totalInputWaitNanos;
        prevOutputWaitNanos = totalOutputWaitNanos;

        long deltaHandled = deltaCompleted + deltaErrors;
        long effectiveWindow = Math.max(1L, windowNanos);

        double instThroughput = (deltaCompleted * 1_000_000_000.0) / effectiveWindow;
        double instInWaitRatio = Math.min(1.0, (double) deltaInWait / effectiveWindow);
        double instOutWaitRatio = Math.min(1.0, (double) deltaOutWait / effectiveWindow);
        double clampedInFill = clamp01(rawInputFill);
        double clampedOutFill = clamp01(rawOutputFill);

        if (!initialized) {
            initialized = true;
            emaInputFill = clampedInFill;
            emaOutputFill = clampedOutFill;
            emaThroughputPerSec = instThroughput;
            emaInputWaitRatio = instInWaitRatio;
            emaOutputWaitRatio = instOutWaitRatio;
            emaErrorRate = deltaHandled > 0 ? (double) deltaErrors / deltaHandled : 0.0;
        } else {
            emaInputFill = ema(emaAlpha, clampedInFill, emaInputFill);
            emaOutputFill = ema(emaAlpha, clampedOutFill, emaOutputFill);
            emaThroughputPerSec = ema(emaAlpha, instThroughput, emaThroughputPerSec);
            emaInputWaitRatio = ema(emaAlpha, instInWaitRatio, emaInputWaitRatio);
            emaOutputWaitRatio = ema(emaAlpha, instOutWaitRatio, emaOutputWaitRatio);
            if (deltaHandled > 0) {
                double instErrorRate = (double) deltaErrors / deltaHandled;
                emaErrorRate = ema(emaAlpha, instErrorRate, emaErrorRate);
            }
        }

        if (deltaHandled > 0) {
            double avgService = (double) deltaService / deltaHandled;
            if (!hasLatencySample) {
                hasLatencySample = true;
                emaServiceNanos = avgService;
                baselineServiceNanos = avgService > 0.0 ? avgService : 0.0;
            } else {
                emaServiceNanos = ema(emaAlpha, avgService, emaServiceNanos);
                if (avgService > 0.0) {
                    baselineServiceNanos = baselineServiceNanos == 0.0
                            ? avgService
                            : Math.min(baselineServiceNanos, avgService);
                }
            }
        }

        Snapshot snapshot = new Snapshot(
                curCompleted,
                curErrors,
                totalDropped,
                activeWorkers.get(),
                emaServiceNanos,
                baselineServiceNanos,
                emaThroughputPerSec,
                emaErrorRate,
                emaInputFill,
                emaOutputFill,
                emaInputWaitRatio,
                emaOutputWaitRatio);
        latestSnapshot = snapshot;
        return snapshot;
    }

    public StageState classify(Snapshot snapshot, AdaptiveConfig config, boolean hasInput, boolean hasOutput) {
        if (hasOutput
                && (snapshot.emaOutputFill() > config.backpressuredThreshold()
                        || snapshot.emaOutputWaitRatio() > 0.50)) {
            return StageState.BACKPRESSURED;
        }
        if (hasInput
                && snapshot.emaInputFill() > config.bottleneckInputThreshold()
                && (!hasOutput || snapshot.emaOutputFill() < config.bottleneckOutputCeiling())) {
            return StageState.BOTTLENECK;
        }
        if (hasInput
                && snapshot.emaInputFill() < config.starvedThreshold()
                && (snapshot.emaInputWaitRatio() > 0.15 || snapshot.activeWorkers() == 0)) {
            return StageState.STARVED;
        }
        return StageState.BALANCED;
    }

    public Snapshot latestSnapshot() {
        return latestSnapshot;
    }

    public long completedItems() {
        return completedItems.sum();
    }

    public long errorItems() {
        return errorItems.sum();
    }

    public int activeWorkers() {
        return activeWorkers.get();
    }

    private static double ema(double alpha, double current, double previous) {
        return alpha * current + (1.0 - alpha) * previous;
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || value <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, value);
    }
}
