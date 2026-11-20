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
package xyz.scarabya.eazypipe;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import xyz.scarabya.eazypipe.adaptive.AdaptiveConfig;
import xyz.scarabya.eazypipe.metrics.PipelineMetricsListener;

/**
 * Global configuration for executing a {@link Pipeline}.
 *
 * @param defaultBufferCapacity default capacity for inter-stage channels ({@code >= 1})
 * @param executorMode default threading model used when a stage leaves {@link StageKind#INHERIT}
 * @param customExecutor caller-supplied executor when {@code executorMode == ExecutorMode.CUSTOM}, otherwise {@code null}
 * @param errorStrategy default strategy applied when a stage uses {@link ErrorPolicy#INHERIT}
 * @param errorHandler optional listener notified as {@code (stageName, throwable)} when an item error is skipped
 * @param cpuBudget global worker/permit budget for {@link StageKind#CPU_BOUND} stages ({@code >= 1})
 * @param adaptiveConfig adaptive controller configuration ({@link AdaptiveConfig#disabled()} by default)
 * @param contextPropagator optional thread-context propagator from caller into workers and items
 * @param metricsListener optional zero-dependency telemetry/event listener
 */
public record PipeConfig(
        int defaultBufferCapacity,
        ExecutorMode executorMode,
        ExecutorService customExecutor,
        ErrorStrategy errorStrategy,
        BiConsumer<String, Throwable> errorHandler,
        int cpuBudget,
        AdaptiveConfig adaptiveConfig,
        ContextPropagator contextPropagator,
        PipelineMetricsListener metricsListener) {

    /**
     * Default inter-stage bounded channel capacity ({@value}).
     */
    public static final int DEFAULT_BUFFER_CAPACITY = 1024;

    /**
     * Execution threading strategy for pipeline stage workers.
     */
    public enum ExecutorMode {
        /** One Java 21 virtual thread per stage worker (default). */
        VIRTUAL_THREADS,
        /** One daemon platform thread per stage worker. */
        PLATFORM_THREADS,
        /** Caller-provided {@link ExecutorService}; never shut down by EazyPipe. */
        CUSTOM
    }

    /**
     * Global error handling strategy for stage failures when {@link ErrorPolicy#INHERIT} is active.
     */
    public enum ErrorStrategy {
        /** Immediately abort all channels and cancel all workers on any failure (default). */
        FAIL_FAST,
        /**
         * Skip failing items in processor and sink stages, record them in {@link PipeStats#errorsSkipped()},
         * and continue processing remaining items. Source failures and {@link Error}s always fail fast.
         */
        CONTINUE_ON_ITEM_ERROR
    }

    public PipeConfig {
        if (defaultBufferCapacity < 1) {
            throw new IllegalArgumentException(
                    "defaultBufferCapacity must be >= 1, got " + defaultBufferCapacity);
        }
        Objects.requireNonNull(executorMode, "executorMode must not be null");
        Objects.requireNonNull(errorStrategy, "errorStrategy must not be null");
        if (executorMode == ExecutorMode.CUSTOM && customExecutor == null) {
            throw new IllegalArgumentException(
                    "customExecutor must not be null when executorMode is CUSTOM");
        }
        if (executorMode != ExecutorMode.CUSTOM && customExecutor != null) {
            throw new IllegalArgumentException(
                    "customExecutor must be null unless executorMode is CUSTOM");
        }
        if (cpuBudget < 1) {
            throw new IllegalArgumentException("cpuBudget must be >= 1, got " + cpuBudget);
        }
        Objects.requireNonNull(adaptiveConfig, "adaptiveConfig must not be null");
    }

    /**
     * Backward-compatible 5-argument constructor using default CPU budget and disabled adaptive control.
     */
    public PipeConfig(
            int defaultBufferCapacity,
            ExecutorMode executorMode,
            ExecutorService customExecutor,
            ErrorStrategy errorStrategy,
            BiConsumer<String, Throwable> errorHandler) {
        this(
                defaultBufferCapacity,
                executorMode,
                customExecutor,
                errorStrategy,
                errorHandler,
                defaultCpuBudget(),
                AdaptiveConfig.disabled(),
                null,
                null);
    }

    private static int defaultCpuBudget() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    /**
     * Returns the default pipeline configuration (virtual threads, buffer capacity 1024, fail-fast).
     *
     * @return default {@link PipeConfig}
     */
    public static PipeConfig defaults() {
        return builder().build();
    }

    /**
     * Returns a configuration using Java 21 virtual threads.
     *
     * @return a virtual-thread {@link PipeConfig}
     */
    public static PipeConfig virtualThreads() {
        return defaults();
    }

    /**
     * Returns a configuration using daemon platform threads.
     *
     * @return a platform-thread {@link PipeConfig}
     */
    public static PipeConfig platformThreads() {
        return builder().executorMode(ExecutorMode.PLATFORM_THREADS).build();
    }

    /**
     * Returns a configuration that submits worker tasks to the given {@link ExecutorService}.
     * EazyPipe does not shut down custom executors when a pipeline finishes.
     *
     * @param customExecutor the executor to use (must not be {@code null})
     * @return a custom-executor {@link PipeConfig}
     */
    public static PipeConfig withExecutor(ExecutorService customExecutor) {
        return builder().customExecutor(customExecutor).build();
    }

    /**
     * Creates a new mutable {@link Builder} initialized with default values.
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link PipeConfig}.
     */
    public static final class Builder {

        private int defaultBufferCapacity = DEFAULT_BUFFER_CAPACITY;
        private ExecutorMode executorMode = ExecutorMode.VIRTUAL_THREADS;
        private ExecutorService customExecutor;
        private ErrorStrategy errorStrategy = ErrorStrategy.FAIL_FAST;
        private BiConsumer<String, Throwable> errorHandler;
        private int cpuBudget = defaultCpuBudget();
        private boolean cpuBudgetExplicit;
        private AdaptiveConfig adaptiveConfig = AdaptiveConfig.disabled();
        private ContextPropagator contextPropagator;
        private PipelineMetricsListener metricsListener;

        private Builder() {
        }

        public Builder defaultBufferCapacity(int defaultBufferCapacity) {
            this.defaultBufferCapacity = defaultBufferCapacity;
            return this;
        }

        public Builder executorMode(ExecutorMode executorMode) {
            this.executorMode = Objects.requireNonNull(executorMode, "executorMode must not be null");
            if (executorMode != ExecutorMode.CUSTOM) {
                this.customExecutor = null;
            }
            return this;
        }

        public Builder customExecutor(ExecutorService customExecutor) {
            this.customExecutor = Objects.requireNonNull(customExecutor, "customExecutor must not be null");
            this.executorMode = ExecutorMode.CUSTOM;
            return this;
        }

        public Builder errorStrategy(ErrorStrategy errorStrategy) {
            this.errorStrategy = Objects.requireNonNull(errorStrategy, "errorStrategy must not be null");
            return this;
        }

        public Builder errorHandler(BiConsumer<String, Throwable> errorHandler) {
            this.errorHandler = errorHandler;
            return this;
        }

        public Builder cpuBudget(int cpuBudget) {
            this.cpuBudget = cpuBudget;
            this.cpuBudgetExplicit = true;
            return this;
        }

        public Builder adaptive(AdaptiveConfig adaptiveConfig) {
            this.adaptiveConfig = Objects.requireNonNull(adaptiveConfig, "adaptiveConfig must not be null");
            if (!cpuBudgetExplicit && adaptiveConfig.enabled()) {
                this.cpuBudget = adaptiveConfig.cpuBudget();
            }
            return this;
        }

        public Builder contextPropagator(ContextPropagator contextPropagator) {
            this.contextPropagator = contextPropagator;
            return this;
        }

        public Builder metricsListener(PipelineMetricsListener metricsListener) {
            this.metricsListener = metricsListener;
            return this;
        }

        public PipeConfig build() {
            return new PipeConfig(
                    defaultBufferCapacity,
                    executorMode,
                    customExecutor,
                    errorStrategy,
                    errorHandler,
                    cpuBudget,
                    adaptiveConfig,
                    contextPropagator,
                    metricsListener);
        }
    }
}
