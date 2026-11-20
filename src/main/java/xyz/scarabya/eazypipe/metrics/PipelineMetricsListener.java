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

import xyz.scarabya.eazypipe.OverflowPolicy;
import xyz.scarabya.eazypipe.adaptive.ScalingDecision;

/**
 * Zero-dependency event-driven SPI for observing pipeline telemetry, scaling decisions,
 * and resilience events (errors, dead-letters, channel drops).
 *
 * <p>Implementations must be thread-safe and non-blocking; all callbacks default to no-op.
 */
public interface PipelineMetricsListener {

    /**
     * Returns a no-op {@link PipelineMetricsListener}.
     *
     * @return no-op listener
     */
    static PipelineMetricsListener noop() {
        return new PipelineMetricsListener() { };
    }

    /**
     * Invoked periodically (and on completion) with an updated snapshot for each stage.
     *
     * @param snapshot current stage metrics snapshot
     */
    default void onStageMetrics(StageMetricsSnapshot snapshot) {
    }

    /**
     * Invoked whenever the adaptive controller adjusts a stage's concurrency target.
     *
     * @param decision scaling decision event
     */
    default void onScalingDecision(ScalingDecision decision) {
    }

    /**
     * Invoked when an item-level exception is observed (whether retried, skipped, dead-lettered, or fatal).
     *
     * @param stageName failing stage name
     * @param error thrown exception
     */
    default void onItemError(String stageName, Throwable error) {
    }

    /**
     * Invoked when a failed item is routed to a dead-letter handler.
     *
     * @param stageName stage name
     * @param error root cause
     */
    default void onDeadLetter(String stageName, Throwable error) {
    }

    /**
     * Invoked when an item is dropped or rejected due to a channel {@link OverflowPolicy}.
     *
     * @param stageName downstream stage owning the overflowing input channel
     * @param policy policy applied
     */
    default void onChannelOverflow(String stageName, OverflowPolicy policy) {
    }

    /**
     * Invoked once when the pipeline finishes (normally, exceptionally, or by cancellation).
     *
     * @param snapshot final pipeline metrics snapshot
     */
    default void onPipelineCompleted(PipelineMetricsSnapshot snapshot) {
    }
}
