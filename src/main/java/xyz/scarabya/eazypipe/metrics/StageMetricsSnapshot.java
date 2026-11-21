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
package xyz.scarabya.eazypipe.metrics;

import java.util.Objects;
import xyz.scarabya.eazypipe.StageKind;
import xyz.scarabya.eazypipe.adaptive.StageState;

/**
 * Immutable snapshot of per-stage operational metrics.
 *
 * @param stageName resolved stage name
 * @param stageKind effective scheduling classification
 * @param stageState latest classified state
 * @param targetConcurrency current target concurrency permit limit
 * @param activeWorkers number of items actively executing right now
 * @param itemsCompleted total items successfully handled by this stage
 * @param itemsErrored total item-level errors observed in this stage
 * @param itemsDropped total items dropped on the stage's input channel due to overflow policies
 * @param emaServiceTimeMillis smoothed per-item service latency in milliseconds
 * @param emaThroughputPerSec smoothed throughput in items/second
 * @param inputFillRatio current input channel fill ratio in {@code [0.0, 1.0]}
 * @param outputFillRatio current output channel fill ratio in {@code [0.0, 1.0]}
 * @param fanOutRatio smoothed output-to-input item ratio ({@code Double.NaN} for auxiliary branches)
 * @param relativeFlow cumulative fan-out flow multiplier ({@code Double.NaN} for auxiliary branches)
 */
public record StageMetricsSnapshot(
        String stageName,
        StageKind stageKind,
        StageState stageState,
        int targetConcurrency,
        int activeWorkers,
        long itemsCompleted,
        long itemsErrored,
        long itemsDropped,
        double emaServiceTimeMillis,
        double emaThroughputPerSec,
        double inputFillRatio,
        double outputFillRatio,
        double fanOutRatio,
        double relativeFlow) {

    public StageMetricsSnapshot {
        Objects.requireNonNull(stageName, "stageName must not be null");
        Objects.requireNonNull(stageKind, "stageKind must not be null");
        Objects.requireNonNull(stageState, "stageState must not be null");
    }
}
