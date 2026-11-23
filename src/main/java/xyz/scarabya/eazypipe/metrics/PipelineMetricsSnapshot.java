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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    /**
     * Returns a compact multi-line human-readable summary suitable for application logs.
     *
     * @return formatted metrics summary
     */
    public String summary() {
        List<String> lines = new ArrayList<>(stages.size() + 2);
        lines.add(String.format(
                Locale.ROOT,
                "elapsed=%dms produced=%d consumed=%d skipped=%d dropped=%d",
                elapsed.toMillis(),
                itemsProduced,
                itemsConsumed,
                errorsSkipped,
                itemsDropped));
        for (StageMetricsSnapshot s : stages) {
            lines.add(String.format(
                    Locale.ROOT,
                    "  %s %s %s c=%d active=%d thr=%.1f/s svc=%.2f ms in=%.0f%% out=%.0f%% fanout=%s flow=%s",
                    s.stageName(),
                    s.stageKind(),
                    s.stageState(),
                    s.targetConcurrency(),
                    s.activeWorkers(),
                    s.emaThroughputPerSec(),
                    s.emaServiceTimeMillis(),
                    s.inputFillRatio() * 100.0,
                    s.outputFillRatio() * 100.0,
                    formatRatio(s.fanOutRatio()),
                    formatRatio(s.relativeFlow())));
        }
        if (!recentDecisions.isEmpty()) {
            lines.add("  last: " + recentDecisions.get(recentDecisions.size() - 1).explain());
        }
        return String.join("\n", lines);
    }

    private static String formatRatio(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
