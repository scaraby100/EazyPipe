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

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import xyz.scarabya.eazypipe.adaptive.ScalingDecision;

/**
 * Point-in-time observability snapshot across an entire pipeline execution.
 *
 * @param elapsed duration since execution start (or total duration if completed)
 * @param itemsProduced total items emitted by the source stage(s)
 * @param itemsConsumed total items consumed by the terminal sink stage(s)
 * @param errorsSkipped total item errors skipped/dead-lettered
 * @param itemsDropped total items dropped across all bounded channels due to overflow policies
 * @param stages per-stage telemetry snapshots in pipeline order
 * @param recentDecisions scaling decisions recorded during this execution
 */
public record PipelineMetricsSnapshot(
        Duration elapsed,
        long itemsProduced,
        long itemsConsumed,
        long errorsSkipped,
        long itemsDropped,
        List<StageMetricsSnapshot> stages,
        List<ScalingDecision> recentDecisions) {

    public PipelineMetricsSnapshot {
        Objects.requireNonNull(elapsed, "elapsed must not be null");
        stages = List.copyOf(stages);
        recentDecisions = List.copyOf(recentDecisions);
    }
}
