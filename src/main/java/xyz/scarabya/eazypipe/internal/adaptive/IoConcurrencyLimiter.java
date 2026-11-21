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

import xyz.scarabya.eazypipe.adaptive.AdaptiveConfig;
import xyz.scarabya.eazypipe.adaptive.StageState;

/**
 * AIMD / Vegas concurrency controller with retry congestion signals and gradient verification
 * for {@code IO_BOUND} stages running on Java 21 virtual threads.
 */
public final class IoConcurrencyLimiter {

    private static final double HIGH_ERROR_THRESHOLD = 0.05;
    private static final double HEALTHY_ERROR_THRESHOLD = 0.01;
    private static final double MIN_THROUGHPUT_GAIN = 1.02;

    /**
     * Immutable input snapshot for an {@code IO_BOUND} stage.
     */
    public record IoStageView(
            String stageName,
            StageState state,
            int currentConcurrency,
            int minConcurrency,
            int maxConcurrency,
            double emaServiceNanos,
            double baselineServiceNanos,
            double emaErrorRate,
            double emaRetryRate,
            boolean braked,
            double throughputNow,
            double throughputBeforeLastIncrease,
            int lastIncreaseStep) {
    }

    private IoConcurrencyLimiter() {
    }

    /**
     * Computes the next concurrency target for an {@code IO_BOUND} stage.
     *
     * @param stage current stage view
     * @param tuning adaptive tuning thresholds
     * @return new concurrency clamped to {@code [minConcurrency, maxConcurrency]}
     */
    public static int computeTarget(IoStageView stage, AdaptiveConfig.Tuning tuning) {
        int current = stage.currentConcurrency();
        int min = stage.minConcurrency();
        int max = stage.maxConcurrency();

        boolean latencySaturated = stage.baselineServiceNanos() > 0.0
                && stage.emaServiceNanos() > tuning.ioLatencyBeta() * stage.baselineServiceNanos();
        boolean retrySaturated = stage.emaRetryRate() > tuning.retryRateThreshold();
        boolean errorSaturated = stage.emaErrorRate() > HIGH_ERROR_THRESHOLD;

        if (stage.state() == StageState.BACKPRESSURED
                || latencySaturated
                || retrySaturated
                || errorSaturated
                || (stage.braked() && stage.state() != StageState.BOTTLENECK)) {
            int stepDown = Math.max(1, (int) Math.ceil(current * 0.25));
            return Math.max(min, current - stepDown);
        }

        if (stage.state() == StageState.STARVED) {
            int stepDown = Math.max(1, (int) Math.floor(current * 0.20));
            return Math.max(min, current - stepDown);
        }

        if (stage.state() == StageState.BOTTLENECK && !stage.braked()) {
            boolean healthyLatency = stage.baselineServiceNanos() <= 0.0
                    || stage.emaServiceNanos() <= tuning.ioLatencyAlpha() * stage.baselineServiceNanos();
            boolean healthyErrors = stage.emaErrorRate() <= HEALTHY_ERROR_THRESHOLD;
            boolean healthyRetries = stage.emaRetryRate() <= tuning.retryRateThreshold() / 2.0;
            if (healthyLatency && healthyErrors && healthyRetries) {
                double thrBefore = stage.throughputBeforeLastIncrease();
                if (!Double.isNaN(thrBefore)
                        && stage.throughputNow() < thrBefore * MIN_THROUGHPUT_GAIN) {
                    int revertStep = Math.max(0, stage.lastIncreaseStep());
                    return Math.max(min, current - revertStep);
                }
                int stepUp = Math.max(1, (int) Math.floor(current * 0.10));
                return Math.min(max, current + stepUp);
            }
        }

        return Math.max(min, Math.min(max, current));
    }
}
