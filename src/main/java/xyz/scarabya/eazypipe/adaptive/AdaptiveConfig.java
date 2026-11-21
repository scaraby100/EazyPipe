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

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Configuration for the downstream-aware {@link AdaptiveController}.
 *
 * @param enabled whether adaptive concurrency control is active
 * @param tickInterval sampling and control loop interval (must be positive)
 * @param cpuBudget optional override for total concurrent permit budget across {@code CPU_BOUND} stages ({@code 0} to inherit from {@code PipeConfig})
 * @param emaAlpha exponential moving average smoothing factor in {@code (0.0, 1.0]}
 * @param ioLatencyAlpha Vegas/AIMD increase ceiling relative to baseline latency ({@code >= 1.0})
 * @param ioLatencyBeta Vegas/AIMD decrease threshold relative to baseline latency ({@code >= ioLatencyAlpha})
 * @param starvedThreshold input fill ratio below which a stage may be classified as {@link StageState#STARVED}
 * @param backpressuredThreshold output fill ratio above which a stage is classified as {@link StageState#BACKPRESSURED}
 * @param bottleneckInputThreshold input fill ratio above which a stage is considered backlogged
 * @param bottleneckOutputCeiling output fill ratio below which a backlogged stage can safely scale up
 * @param deadbandLow lower bound of the neutral deadband zone
 * @param deadbandHigh upper bound of the neutral deadband zone
 * @param persistenceTicks consecutive ticks in the same state required before applying a scale change ({@code >= 1})
 * @param cooldown minimum duration between successive concurrency changes on the same stage
 * @param onDecision optional listener notified whenever a {@link ScalingDecision} occurs
 * @param clock clock used for cooldown timestamps and decision events (injectable for deterministic testing)
 */
public record AdaptiveConfig(
        boolean enabled,
        Duration tickInterval,
        int cpuBudget,
        double emaAlpha,
        double ioLatencyAlpha,
        double ioLatencyBeta,
        double starvedThreshold,
        double backpressuredThreshold,
        double bottleneckInputThreshold,
        double bottleneckOutputCeiling,
        double deadbandLow,
        double deadbandHigh,
        int persistenceTicks,
        Duration cooldown,
        Consumer<ScalingDecision> onDecision,
        Clock clock) {

    public AdaptiveConfig {
        Objects.requireNonNull(tickInterval, "tickInterval must not be null");
        if (tickInterval.isZero() || tickInterval.isNegative()) {
            throw new IllegalArgumentException("tickInterval must be positive");
        }
        if (cpuBudget < 0) {
            throw new IllegalArgumentException("cpuBudget must be >= 0, got " + cpuBudget);
        }
        if (Double.isNaN(emaAlpha) || emaAlpha <= 0.0 || emaAlpha > 1.0) {
            throw new IllegalArgumentException("emaAlpha must be in (0.0, 1.0]");
        }
        if (Double.isNaN(ioLatencyAlpha) || ioLatencyAlpha < 1.0) {
            throw new IllegalArgumentException("ioLatencyAlpha must be >= 1.0");
        }
        if (Double.isNaN(ioLatencyBeta) || ioLatencyBeta < ioLatencyAlpha) {
            throw new IllegalArgumentException("ioLatencyBeta must be >= ioLatencyAlpha");
        }
        validateRatio("starvedThreshold", starvedThreshold);
        validateRatio("backpressuredThreshold", backpressuredThreshold);
        validateRatio("bottleneckInputThreshold", bottleneckInputThreshold);
        validateRatio("bottleneckOutputCeiling", bottleneckOutputCeiling);
        validateRatio("deadbandLow", deadbandLow);
        validateRatio("deadbandHigh", deadbandHigh);
        if (deadbandLow > deadbandHigh) {
            throw new IllegalArgumentException("deadbandLow must be <= deadbandHigh");
        }
        if (persistenceTicks < 1) {
            throw new IllegalArgumentException("persistenceTicks must be >= 1");
        }
        Objects.requireNonNull(cooldown, "cooldown must not be null");
        if (cooldown.isNegative()) {
            throw new IllegalArgumentException("cooldown must not be negative");
        }
        Objects.requireNonNull(clock, "clock must not be null");
    }

    private static void validateRatio(String name, double value) {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be in [0.0, 1.0], got " + value);
        }
    }

    /**
     * Returns an enabled default {@link AdaptiveConfig} tuned for typical mixed CPU/IO workloads.
     *
     * @return default enabled {@link AdaptiveConfig}
     */
    public static AdaptiveConfig defaults() {
        return builder().enabled(true).build();
    }

    /**
     * Returns a disabled {@link AdaptiveConfig} suitable for static concurrency execution or A/B comparisons.
     *
     * @return disabled {@link AdaptiveConfig}
     */
    public static AdaptiveConfig disabled() {
        return builder().enabled(false).build();
    }

    /**
     * Creates a new mutable {@link Builder}.
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link AdaptiveConfig}.
     */
    public static final class Builder {

        private boolean enabled = true;
        private Duration tickInterval = Duration.ofMillis(250);
        private int cpuBudget = 0;
        private double emaAlpha = 0.30;
        private double ioLatencyAlpha = 1.25;
        private double ioLatencyBeta = 1.60;
        private double starvedThreshold = 0.20;
        private double backpressuredThreshold = 0.80;
        private double bottleneckInputThreshold = 0.75;
        private double bottleneckOutputCeiling = 0.60;
        private double deadbandLow = 0.30;
        private double deadbandHigh = 0.70;
        private int persistenceTicks = 2;
        private Duration cooldown = Duration.ofSeconds(1);
        private Consumer<ScalingDecision> onDecision;
        private Clock clock = Clock.systemUTC();

        private Builder() {
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder tickInterval(Duration tickInterval) {
            this.tickInterval = tickInterval;
            return this;
        }

        public Builder cpuBudget(int cpuBudget) {
            this.cpuBudget = cpuBudget;
            return this;
        }

        public Builder emaAlpha(double emaAlpha) {
            this.emaAlpha = emaAlpha;
            return this;
        }

        public Builder ioLatencyAlpha(double ioLatencyAlpha) {
            this.ioLatencyAlpha = ioLatencyAlpha;
            return this;
        }

        public Builder ioLatencyBeta(double ioLatencyBeta) {
            this.ioLatencyBeta = ioLatencyBeta;
            return this;
        }

        public Builder starvedThreshold(double starvedThreshold) {
            this.starvedThreshold = starvedThreshold;
            return this;
        }

        public Builder backpressuredThreshold(double backpressuredThreshold) {
            this.backpressuredThreshold = backpressuredThreshold;
            return this;
        }

        public Builder bottleneckInputThreshold(double bottleneckInputThreshold) {
            this.bottleneckInputThreshold = bottleneckInputThreshold;
            return this;
        }

        public Builder bottleneckOutputCeiling(double bottleneckOutputCeiling) {
            this.bottleneckOutputCeiling = bottleneckOutputCeiling;
            return this;
        }

        public Builder deadband(double low, double high) {
            this.deadbandLow = low;
            this.deadbandHigh = high;
            return this;
        }

        public Builder persistenceTicks(int persistenceTicks) {
            this.persistenceTicks = persistenceTicks;
            return this;
        }

        public Builder cooldown(Duration cooldown) {
            this.cooldown = cooldown;
            return this;
        }

        public Builder onDecision(Consumer<ScalingDecision> onDecision) {
            this.onDecision = onDecision;
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public AdaptiveConfig build() {
            return new AdaptiveConfig(
                    enabled,
                    tickInterval,
                    cpuBudget,
                    emaAlpha,
                    ioLatencyAlpha,
                    ioLatencyBeta,
                    starvedThreshold,
                    backpressuredThreshold,
                    bottleneckInputThreshold,
                    bottleneckOutputCeiling,
                    deadbandLow,
                    deadbandHigh,
                    persistenceTicks,
                    cooldown,
                    onDecision,
                    clock);
        }
    }
}
