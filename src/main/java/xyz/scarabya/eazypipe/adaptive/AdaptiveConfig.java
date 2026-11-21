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
 * Configuration for the downstream-aware adaptive concurrency controller.
 *
 * @param enabled whether adaptive concurrency control is active
 * @param tickInterval sampling and control loop interval (must be positive)
 * @param cpuBudget optional override for total concurrent permit budget across {@code CPU_BOUND} stages ({@code 0} to inherit from {@code PipeConfig})
 * @param tuning fine-grained smoothing, Schmitt-trigger, and limiter tuning parameters
 * @param onDecision optional listener notified whenever a {@link ScalingDecision} occurs
 * @param clock clock used for cooldown timestamps and decision events (injectable for deterministic testing)
 */
public record AdaptiveConfig(
        boolean enabled,
        Duration tickInterval,
        int cpuBudget,
        Tuning tuning,
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
        Objects.requireNonNull(tuning, "tuning must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Returns the recommended balanced {@link AdaptiveConfig} preset.
     *
     * @return balanced enabled {@link AdaptiveConfig}
     */
    public static AdaptiveConfig balanced() {
        return builder().enabled(true).tickInterval(Duration.ofMillis(250)).tuning(Tuning.balanced()).build();
    }

    /**
     * Alias for {@link #balanced()}.
     *
     * @return default enabled {@link AdaptiveConfig}
     */
    public static AdaptiveConfig defaults() {
        return balanced();
    }

    /**
     * Returns a throughput-oriented {@link AdaptiveConfig} preset tolerating fuller queues and scaling faster.
     *
     * @return throughput-oriented {@link AdaptiveConfig}
     */
    public static AdaptiveConfig throughput() {
        return builder().enabled(true).tickInterval(Duration.ofMillis(250)).tuning(Tuning.throughput()).build();
    }

    /**
     * Returns a low-latency {@link AdaptiveConfig} preset keeping queues short and backing off rapidly.
     *
     * @return low-latency {@link AdaptiveConfig}
     */
    public static AdaptiveConfig lowLatency() {
        return builder().enabled(true).tickInterval(Duration.ofMillis(100)).tuning(Tuning.lowLatency()).build();
    }

    /**
     * Returns a disabled {@link AdaptiveConfig} suitable for static concurrency execution or A/B comparisons.
     *
     * @return disabled {@link AdaptiveConfig}
     */
    public static AdaptiveConfig disabled() {
        return builder().enabled(false).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .enabled(enabled)
                .tickInterval(tickInterval)
                .cpuBudget(cpuBudget)
                .tuning(tuning)
                .onDecision(onDecision)
                .clock(clock);
    }

    /**
     * Fine-grained thresholds and smoothing parameters for the adaptive control loop.
     */
    public record Tuning(
            double emaAlpha,
            double ioLatencyAlpha,
            double ioLatencyBeta,
            double starvedThreshold,
            double backpressuredThreshold,
            double bottleneckInputThreshold,
            double bottleneckOutputCeiling,
            double deadbandLow,
            double deadbandHigh,
            double minRelativeChange,
            double baselineDecay,
            double retryRateThreshold,
            int persistenceTicks,
            Duration cooldown) {

        public Tuning {
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
            validateRatio("minRelativeChange", minRelativeChange);
            validateRatio("baselineDecay", baselineDecay);
            validateRatio("retryRateThreshold", retryRateThreshold);
            if (deadbandLow > deadbandHigh) {
                throw new IllegalArgumentException("deadbandLow must be <= deadbandHigh");
            }
            if (starvedThreshold > deadbandLow) {
                throw new IllegalArgumentException(
                        "starvedThreshold (" + starvedThreshold + ") must be <= deadbandLow (" + deadbandLow + ")");
            }
            double upperCeiling = Math.min(bottleneckInputThreshold, backpressuredThreshold);
            if (deadbandHigh > upperCeiling) {
                throw new IllegalArgumentException(
                        "deadbandHigh (" + deadbandHigh
                                + ") must be <= min(bottleneckInputThreshold, backpressuredThreshold) ("
                                + upperCeiling + ")");
            }
            if (persistenceTicks < 1) {
                throw new IllegalArgumentException("persistenceTicks must be >= 1");
            }
            Objects.requireNonNull(cooldown, "cooldown must not be null");
            if (cooldown.isNegative()) {
                throw new IllegalArgumentException("cooldown must not be negative");
            }
        }

        private static void validateRatio(String name, double value) {
            if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
                throw new IllegalArgumentException(name + " must be in [0.0, 1.0], got " + value);
            }
        }

        public static Tuning balanced() {
            return new Tuning(
                    0.30, 1.25, 1.60,
                    0.20, 0.80, 0.75, 0.60,
                    0.30, 0.70, 0.10, 0.01, 0.05,
                    2, Duration.ofSeconds(1));
        }

        public static Tuning throughput() {
            return new Tuning(
                    0.30, 1.50, 2.00,
                    0.10, 0.90, 0.60, 0.75,
                    0.25, 0.55, 0.05, 0.01, 0.10,
                    2, Duration.ofMillis(500));
        }

        public static Tuning lowLatency() {
            return new Tuning(
                    0.50, 1.10, 1.30,
                    0.15, 0.60, 0.50, 0.40,
                    0.20, 0.45, 0.10, 0.02, 0.02,
                    1, Duration.ofMillis(250));
        }

        public static Builder builder() {
            return new Builder();
        }

        public Builder toBuilder() {
            return new Builder(this);
        }

        public static final class Builder {

            private double emaAlpha;
            private double ioLatencyAlpha;
            private double ioLatencyBeta;
            private double starvedThreshold;
            private double backpressuredThreshold;
            private double bottleneckInputThreshold;
            private double bottleneckOutputCeiling;
            private double deadbandLow;
            private double deadbandHigh;
            private double minRelativeChange;
            private double baselineDecay;
            private double retryRateThreshold;
            private int persistenceTicks;
            private Duration cooldown;

            private Builder() {
                this(balanced());
            }

            private Builder(Tuning base) {
                this.emaAlpha = base.emaAlpha;
                this.ioLatencyAlpha = base.ioLatencyAlpha;
                this.ioLatencyBeta = base.ioLatencyBeta;
                this.starvedThreshold = base.starvedThreshold;
                this.backpressuredThreshold = base.backpressuredThreshold;
                this.bottleneckInputThreshold = base.bottleneckInputThreshold;
                this.bottleneckOutputCeiling = base.bottleneckOutputCeiling;
                this.deadbandLow = base.deadbandLow;
                this.deadbandHigh = base.deadbandHigh;
                this.minRelativeChange = base.minRelativeChange;
                this.baselineDecay = base.baselineDecay;
                this.retryRateThreshold = base.retryRateThreshold;
                this.persistenceTicks = base.persistenceTicks;
                this.cooldown = base.cooldown;
            }

            public Builder emaAlpha(double emaAlpha) { this.emaAlpha = emaAlpha; return this; }
            public Builder ioLatencyAlpha(double ioLatencyAlpha) { this.ioLatencyAlpha = ioLatencyAlpha; return this; }
            public Builder ioLatencyBeta(double ioLatencyBeta) { this.ioLatencyBeta = ioLatencyBeta; return this; }
            public Builder starvedThreshold(double starvedThreshold) { this.starvedThreshold = starvedThreshold; return this; }
            public Builder backpressuredThreshold(double backpressuredThreshold) { this.backpressuredThreshold = backpressuredThreshold; return this; }
            public Builder bottleneckInputThreshold(double bottleneckInputThreshold) { this.bottleneckInputThreshold = bottleneckInputThreshold; return this; }
            public Builder bottleneckOutputCeiling(double bottleneckOutputCeiling) { this.bottleneckOutputCeiling = bottleneckOutputCeiling; return this; }
            public Builder deadbandLow(double deadbandLow) { this.deadbandLow = deadbandLow; return this; }
            public Builder deadbandHigh(double deadbandHigh) { this.deadbandHigh = deadbandHigh; return this; }
            public Builder deadband(double low, double high) { this.deadbandLow = low; this.deadbandHigh = high; return this; }
            public Builder minRelativeChange(double minRelativeChange) { this.minRelativeChange = minRelativeChange; return this; }
            public Builder baselineDecay(double baselineDecay) { this.baselineDecay = baselineDecay; return this; }
            public Builder retryRateThreshold(double retryRateThreshold) { this.retryRateThreshold = retryRateThreshold; return this; }
            public Builder persistenceTicks(int persistenceTicks) { this.persistenceTicks = persistenceTicks; return this; }
            public Builder cooldown(Duration cooldown) { this.cooldown = cooldown; return this; }

            public Tuning build() {
                return new Tuning(
                        emaAlpha, ioLatencyAlpha, ioLatencyBeta,
                        starvedThreshold, backpressuredThreshold, bottleneckInputThreshold, bottleneckOutputCeiling,
                        deadbandLow, deadbandHigh, minRelativeChange, baselineDecay, retryRateThreshold,
                        persistenceTicks, cooldown);
            }
        }
    }

    /**
     * Fluent builder for {@link AdaptiveConfig}.
     */
    public static final class Builder {

        private boolean enabled = true;
        private Duration tickInterval = Duration.ofMillis(250);
        private int cpuBudget = 0;
        private Tuning tuning = Tuning.balanced();
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

        public Builder tuning(Tuning tuning) {
            this.tuning = tuning;
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
            return new AdaptiveConfig(enabled, tickInterval, cpuBudget, tuning, onDecision, clock);
        }
    }
}
