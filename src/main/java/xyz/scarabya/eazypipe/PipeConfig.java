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

/**
 * Global configuration for executing a {@link Pipeline}.
 *
 * @param defaultBufferCapacity default capacity for inter-stage channels ({@code >= 1})
 * @param executorMode threading model used to run stage workers
 * @param customExecutor caller-supplied executor when {@code executorMode == ExecutorMode.CUSTOM}, otherwise {@code null}
 * @param errorStrategy strategy applied when processing or consuming an individual item throws an {@link Exception}
 * @param errorHandler optional listener notified as {@code (stageName, throwable)} when an item error is skipped
 */
public record PipeConfig(
        int defaultBufferCapacity,
        ExecutorMode executorMode,
        ExecutorService customExecutor,
        ErrorStrategy errorStrategy,
        BiConsumer<String, Throwable> errorHandler) {

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
     * Error handling strategy for stage failures.
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
    }

    /**
     * Returns the default pipeline configuration (virtual threads, buffer capacity 1024, fail-fast).
     *
     * @return default {@link PipeConfig}
     */
    public static PipeConfig defaults() {
        return new PipeConfig(
                DEFAULT_BUFFER_CAPACITY,
                ExecutorMode.VIRTUAL_THREADS,
                null,
                ErrorStrategy.FAIL_FAST,
                null);
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
        return new PipeConfig(
                DEFAULT_BUFFER_CAPACITY,
                ExecutorMode.PLATFORM_THREADS,
                null,
                ErrorStrategy.FAIL_FAST,
                null);
    }

    /**
     * Returns a configuration that submits worker tasks to the given {@link ExecutorService}.
     * EazyPipe does not shut down custom executors when a pipeline finishes.
     *
     * @param customExecutor the executor to use (must not be {@code null})
     * @return a custom-executor {@link PipeConfig}
     */
    public static PipeConfig withExecutor(ExecutorService customExecutor) {
        Objects.requireNonNull(customExecutor, "customExecutor must not be null");
        return new PipeConfig(
                DEFAULT_BUFFER_CAPACITY,
                ExecutorMode.CUSTOM,
                customExecutor,
                ErrorStrategy.FAIL_FAST,
                null);
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

        private Builder() {
        }

        /**
         * Sets the default bounded channel capacity for stages that do not specify their own.
         *
         * @param defaultBufferCapacity capacity ({@code >= 1})
         * @return this builder
         */
        public Builder defaultBufferCapacity(int defaultBufferCapacity) {
            this.defaultBufferCapacity = defaultBufferCapacity;
            return this;
        }

        /**
         * Sets the executor mode. Selecting {@link ExecutorMode#VIRTUAL_THREADS} or
         * {@link ExecutorMode#PLATFORM_THREADS} clears any previously set custom executor.
         *
         * @param executorMode the executor mode
         * @return this builder
         */
        public Builder executorMode(ExecutorMode executorMode) {
            this.executorMode = Objects.requireNonNull(executorMode, "executorMode must not be null");
            if (executorMode != ExecutorMode.CUSTOM) {
                this.customExecutor = null;
            }
            return this;
        }

        /**
         * Configures a custom {@link ExecutorService} and switches {@code executorMode} to {@link ExecutorMode#CUSTOM}.
         *
         * @param customExecutor the executor service (must not be {@code null})
         * @return this builder
         */
        public Builder customExecutor(ExecutorService customExecutor) {
            this.customExecutor = Objects.requireNonNull(customExecutor, "customExecutor must not be null");
            this.executorMode = ExecutorMode.CUSTOM;
            return this;
        }

        /**
         * Sets the error handling strategy.
         *
         * @param errorStrategy the error strategy
         * @return this builder
         */
        public Builder errorStrategy(ErrorStrategy errorStrategy) {
            this.errorStrategy = Objects.requireNonNull(errorStrategy, "errorStrategy must not be null");
            return this;
        }

        /**
         * Sets an optional handler invoked when an item error is skipped under
         * {@link ErrorStrategy#CONTINUE_ON_ITEM_ERROR}.
         *
         * @param errorHandler callback receiving {@code (stageName, throwable)}
         * @return this builder
         */
        public Builder errorHandler(BiConsumer<String, Throwable> errorHandler) {
            this.errorHandler = errorHandler;
            return this;
        }

        /**
         * Builds the {@link PipeConfig}.
         *
         * @return a new {@link PipeConfig}
         */
        public PipeConfig build() {
            return new PipeConfig(
                    defaultBufferCapacity,
                    executorMode,
                    customExecutor,
                    errorStrategy,
                    errorHandler);
        }
    }
}
