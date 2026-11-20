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
import java.util.Objects;
import xyz.scarabya.eazypipe.StageKind;

/**
 * Immutable event emitted whenever {@link AdaptiveController} changes a stage's concurrency target.
 *
 * @param timestamp instant when the decision was applied
 * @param stageName name of the adjusted stage
 * @param stageKind scheduling kind of the stage
 * @param state classified stage state that triggered the adjustment
 * @param previousConcurrency concurrency target before the change
 * @param newConcurrency concurrency target after the change
 * @param reason concise human-readable explanation of the adjustment
 * @param inputFillRatio smoothed input channel fill ratio in {@code [0.0, 1.0]}
 * @param outputFillRatio smoothed output channel fill ratio in {@code [0.0, 1.0]}
 * @param emaServiceTimeMillis smoothed per-item service latency in milliseconds
 */
public record ScalingDecision(
        Instant timestamp,
        String stageName,
        StageKind stageKind,
        StageState state,
        int previousConcurrency,
        int newConcurrency,
        String reason,
        double inputFillRatio,
        double outputFillRatio,
        double emaServiceTimeMillis) {

    public ScalingDecision {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(stageName, "stageName must not be null");
        Objects.requireNonNull(stageKind, "stageKind must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}
