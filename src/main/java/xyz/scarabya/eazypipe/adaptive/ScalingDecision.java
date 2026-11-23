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

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import xyz.scarabya.eazypipe.StageKind;

/**
 * Immutable event emitted whenever the adaptive controller changes a stage's concurrency target.
 *
 * @param timestamp instant when the decision was applied
 * @param stageName name of the adjusted stage
 * @param stageIndex zero-based position of the stage in the pipeline
 * @param stageKind scheduling kind of the stage
 * @param state classified stage state that triggered the adjustment
 * @param previousConcurrency concurrency target before the change
 * @param newConcurrency concurrency target after the change
 * @param reason concise human-readable explanation of the adjustment
 * @param inputFillRatio smoothed input channel fill ratio in {@code [0.0, 1.0]}
 * @param outputFillRatio smoothed output channel fill ratio in {@code [0.0, 1.0]}
 * @param emaServiceTimeMillis smoothed per-item service latency in milliseconds
 * @param latencyRatio ratio of smoothed service latency to baseline latency ({@code 0.0} if no baseline yet)
 * @param relativeFlow cumulative fan-out flow multiplier relative to pipeline input
 * @param dominantBottleneck name of the pipeline-wide dominant bottleneck stage, or {@code ""} if none
 * @param downstreamState state of the immediate downstream main stage, or {@code null} for terminal stages
 */
public record ScalingDecision(
        Instant timestamp,
        String stageName,
        int stageIndex,
        StageKind stageKind,
        StageState state,
        int previousConcurrency,
        int newConcurrency,
        String reason,
        double inputFillRatio,
        double outputFillRatio,
        double emaServiceTimeMillis,
        double latencyRatio,
        double relativeFlow,
        String dominantBottleneck,
        StageState downstreamState) {

    public ScalingDecision {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(stageName, "stageName must not be null");
        Objects.requireNonNull(stageKind, "stageKind must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        dominantBottleneck = dominantBottleneck != null ? dominantBottleneck : "";
    }

    /**
     * Returns a compact single-line diagnostic explanation suitable for operational logs.
     *
     * @return formatted single-line diagnosis
     */
    public String explain() {
        String dom = dominantBottleneck.isEmpty() ? "none" : dominantBottleneck;
        String ds = downstreamState != null ? downstreamState.name() : "NONE";
        return String.format(
                Locale.ROOT,
                "[%s#%d] %s %s %d->%d | dominant=%s latency=%.2fx flow=%.2f downstream=%s | %s",
                stageName,
                stageIndex,
                stageKind,
                state,
                previousConcurrency,
                newConcurrency,
                dom,
                latencyRatio,
                relativeFlow,
                ds,
                reason);
    }
}
