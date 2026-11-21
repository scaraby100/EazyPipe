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
package xyz.scarabya.eazypipe.internal.adaptive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import xyz.scarabya.eazypipe.adaptive.AdaptiveConfig;
import xyz.scarabya.eazypipe.adaptive.StageState;

class StageTelemetryTest {

    @Test
    void waitRatiosAreNormalizedByConcurrency() {
        StageTelemetry telemetry = new StageTelemetry();
        long windowNanos = 100_000_000L; // 100ms
        long totalInputWait = 0L;
        long totalOutputWait = 0L;

        // First sample: input wait = 4 * window at concurrency 4 -> 1.0
        totalInputWait += 4L * windowNanos;
        totalOutputWait += 2L * windowNanos;
        StageTelemetry.Snapshot fullWait = telemetry.sample(
                windowNanos, 1.0, 0.0, 0.0, totalInputWait, totalOutputWait, 0L, 4, 0.01);
        assertEquals(1.0, fullWait.emaInputWaitRatio(), 1e-6);
        assertEquals(0.5, fullWait.emaOutputWaitRatio(), 1e-6);

        // Second sample: input wait = 1 * window at concurrency 4 -> 0.25
        totalInputWait += windowNanos;
        totalOutputWait += 4L * windowNanos;
        StageTelemetry.Snapshot partialWait = telemetry.sample(
                windowNanos, 1.0, 0.0, 0.0, totalInputWait, totalOutputWait, 0L, 4, 0.01);
        assertEquals(0.25, partialWait.emaInputWaitRatio(), 1e-6);
        assertEquals(1.0, partialWait.emaOutputWaitRatio(), 1e-6);
    }

    @Test
    void latencyBaselineDecaysUpwardOverTimeAndDropsImmediatelyOnLowerSample() {
        StageTelemetry telemetry = new StageTelemetry();
        long windowNanos = 100_000_000L;
        long oneMs = 1_000_000L;
        long tenMs = 10_000_000L;
        long twoMs = 2_000_000L;

        // Anomalous low 1ms sample sets initial baseline.
        telemetry.onProcessingStart();
        telemetry.onProcessingSuccess(oneMs);
        StageTelemetry.Snapshot initial = telemetry.sample(
                windowNanos, 1.0, 0.5, 0.5, 0L, 0L, 0L, 1, 0.01);
        assertEquals((double) oneMs, initial.baselineServiceNanos(), 1e-3);

        // 200 ticks at 10ms with baselineDecay = 0.01 -> baseline climbs past 5ms toward 10ms.
        StageTelemetry.Snapshot decayed = initial;
        for (int i = 0; i < 200; i++) {
            telemetry.onProcessingStart();
            telemetry.onProcessingSuccess(tenMs);
            decayed = telemetry.sample(
                    windowNanos, 1.0, 0.5, 0.5, 0L, 0L, 0L, 1, 0.01);
        }
        assertTrue(decayed.baselineServiceNanos() > 5_000_000.0,
                "Expected decayed baseline > 5ms, got " + decayed.baselineServiceNanos());
        assertTrue(decayed.baselineServiceNanos() <= (double) tenMs,
                "Expected decayed baseline <= 10ms, got " + decayed.baselineServiceNanos());

        // New lower 2ms sample drops baseline immediately to 2ms.
        telemetry.onProcessingStart();
        telemetry.onProcessingSuccess(twoMs);
        StageTelemetry.Snapshot lowered = telemetry.sample(
                windowNanos, 1.0, 0.5, 0.5, 0L, 0L, 0L, 1, 0.01);
        assertEquals((double) twoMs, lowered.baselineServiceNanos(), 1e-3);
    }

    @Test
    void schmittTriggerHysteresisStabilizesBottleneckAndStarvedTransitions() {
        StageTelemetry telemetry = new StageTelemetry();
        AdaptiveConfig.Tuning tuning = AdaptiveConfig.Tuning.balanced();

        // BOTTLENECK: enters above 0.75, stays inside deadband [0.70, 0.75] when previous==BOTTLENECK,
        // exits below deadbandHigh (0.70), and does not re-enter at 0.72 from BALANCED.
        assertEquals(StageState.BOTTLENECK, telemetry.classify(
                snapshotWith(0.80, 0.10, 0.0, 0.0, 1), tuning, true, true, StageState.BALANCED));
        assertEquals(StageState.BOTTLENECK, telemetry.classify(
                snapshotWith(0.72, 0.10, 0.0, 0.0, 1), tuning, true, true, StageState.BOTTLENECK));
        assertEquals(StageState.BALANCED, telemetry.classify(
                snapshotWith(0.65, 0.10, 0.0, 0.0, 1), tuning, true, true, StageState.BOTTLENECK));
        assertEquals(StageState.BALANCED, telemetry.classify(
                snapshotWith(0.72, 0.10, 0.0, 0.0, 1), tuning, true, true, StageState.BALANCED));

        // STARVED: enters below 0.20, stays inside [0.20, 0.30] when previous==STARVED,
        // exits above deadbandLow (0.30), and does not re-enter at 0.25 from BALANCED.
        assertEquals(StageState.STARVED, telemetry.classify(
                snapshotWith(0.15, 0.10, 0.25, 0.0, 1), tuning, true, true, StageState.BALANCED));
        assertEquals(StageState.STARVED, telemetry.classify(
                snapshotWith(0.25, 0.10, 0.25, 0.0, 1), tuning, true, true, StageState.STARVED));
        assertEquals(StageState.BALANCED, telemetry.classify(
                snapshotWith(0.35, 0.10, 0.25, 0.0, 1), tuning, true, true, StageState.STARVED));
        assertEquals(StageState.BALANCED, telemetry.classify(
                snapshotWith(0.25, 0.10, 0.25, 0.0, 1), tuning, true, true, StageState.BALANCED));
    }

    private static StageTelemetry.Snapshot snapshotWith(
            double inFill, double outFill, double inWait, double outWait, int activeWorkers) {
        return new StageTelemetry.Snapshot(
                0L, 0L, 0L, activeWorkers,
                0.0, 0.0, 0.0, 0.0,
                inFill, outFill, inWait, outWait);
    }
}
