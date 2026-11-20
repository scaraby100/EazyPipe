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

/**
 * Per-stage configuration controlling workload scheduling kind, concurrency bounds,
 * bounded channel capacity/overflow policy, error/retry/dead-letter policy, naming, and completion callbacks.
 *
 * @param name optional stage name used in metrics and error reporting (may be {@code null})
 * @param kind workload classification ({@link StageKind#CPU_BOUND}, {@link StageKind#IO_BOUND}, or {@link StageKind#INHERIT})
 * @param minConcurrency minimum concurrency permits ({@code >= 1})
 * @param initialConcurrency initial concurrency permits ({@code minConcurrency <= initialConcurrency <= maxConcurrency})
 * @param maxConcurrency maximum concurrency permits ({@code >= initialConcurrency})
 * @param bufferCapacity capacity of this stage's bounded input channel ({@code 0} inherits {@link PipeConfig#defaultBufferCapacity()})
 * @param overflowPolicy overflow behavior applied when the stage's input channel is full
 * @param errorPolicy item-level error policy for this stage ({@link ErrorPolicy#INHERIT} delegates to {@link PipeConfig#errorStrategy()})
 * @param retrySpec retry specification applied when {@code errorPolicy == ErrorPolicy.RETRY}
 * @param deadLetterHandler optional handler receiving failed items under {@link ErrorPolicy#DEAD_LETTER} or after retry exhaustion
 * @param onComplete optional callback invoked once after all workers of this stage finish normally (may be {@code null})
 */
public record StageConfig(
        String name,
        StageKind kind,
        int minConcurrency,
        int initialConcurrency,
        int maxConcurrency,
        int bufferCapacity,
        OverflowPolicy overflowPolicy,
        ErrorPolicy errorPolicy,
        RetrySpec retrySpec,
        DeadLetterHandler<Object> deadLetterHandler,
        Runnable onComplete) {

    public StageConfig {
        Objects.requireNonNull(kind, "kind must not be null");
        if (minConcurrency < 1) {
            throw new IllegalArgumentException("minConcurrency must be >= 1, got " + minConcurrency);
        }
        if (initialConcurrency < minConcurrency || initialConcurrency > maxConcurrency) {
            throw new IllegalArgumentException(
                    "initialConcurrency must be in [" + minConcurrency + ", " + maxConcurrency
                            + "], got " + initialConcurrency);
        }
        if (bufferCapacity < 0) {
            throw new IllegalArgumentException("bufferCapacity must be >= 0, got " + bufferCapacity);
        }
        Objects.requireNonNull(overflowPolicy, "overflowPolicy must not be null");
        Objects.requireNonNull(errorPolicy, "errorPolicy must not be null");
        Objects.requireNonNull(retrySpec, "retrySpec must not be null");
    }

    /**
     * Backward-compatible constructor creating a static-parallelism configuration with default policies.
     *
     * @param name optional stage name
     * @param parallelism fixed worker concurrency ({@code >= 1})
     * @param bufferCapacity bounded input channel capacity ({@code >= 0})
     * @param onComplete optional completion callback
     */
    public StageConfig(String name, int parallelism, int bufferCapacity, Runnable onComplete) {
        this(
                name,
                StageKind.INHERIT,
                parallelism,
                parallelism,
                parallelism,
                bufferCapacity,
                OverflowPolicy.BLOCK,
                ErrorPolicy.INHERIT,
                RetrySpec.none(),
                null,
                onComplete);
    }

    /**
     * Returns the configured initial/fixed concurrency for this stage.
     *
     * @return initial concurrency
     */
    public int parallelism() {
        return initialConcurrency;
    }

    /**
     * Returns a default stage configuration (single worker, inherited kind and buffer capacity).
     *
     * @return default {@link StageConfig}
     */
    public static StageConfig defaults() {
        return new StageConfig(null, 1, 0, null);
    }

    /**
     * Returns a static-parallelism configuration with the specified worker count.
     *
     * @param parallelism number of concurrent workers ({@code >= 1})
     * @return a new {@link StageConfig}
     */
    public static StageConfig parallel(int parallelism) {
        return new StageConfig(null, parallelism, 0, null);
    }

    /**
     * Returns a configuration with the specified bounded buffer capacity.
     *
     * @param bufferCapacity bounded channel capacity ({@code >= 0}, where {@code 0} inherits {@link PipeConfig#defaultBufferCapacity()})
     * @return a new {@link StageConfig}
     */
    public static StageConfig withBuffer(int bufferCapacity) {
        return new StageConfig(null, 1, bufferCapacity, null);
    }

    /**
     * Returns a configuration with the specified stage name.
     *
     * @param name stage name
     * @return a new {@link StageConfig}
     */
    public static StageConfig named(String name) {
        return new StageConfig(name, 1, 0, null);
    }

    /**
     * Starts building a {@link StageKind#CPU_BOUND} stage configuration.
     *
     * @return a pre-configured {@link Builder}
     */
    public static Builder cpuBound() {
        return builder().kind(StageKind.CPU_BOUND);
    }

    /**
     * Starts building a named {@link StageKind#CPU_BOUND} stage configuration.
     *
     * @param name stage name
     * @return a pre-configured {@link Builder}
     */
    public static Builder cpuBound(String name) {
        return builder().name(name).kind(StageKind.CPU_BOUND);
    }

    /**
     * Starts building a {@link StageKind#IO_BOUND} stage configuration.
     *
     * @return a pre-configured {@link Builder}
     */
    public static Builder ioBound() {
        return builder().kind(StageKind.IO_BOUND);
    }

    /**
     * Starts building a named {@link StageKind#IO_BOUND} stage configuration.
     *
     * @param name stage name
     * @return a pre-configured {@link Builder}
     */
    public static Builder ioBound(String name) {
        return builder().name(name).kind(StageKind.IO_BOUND);
    }

    /**
     * Creates a new mutable {@link Builder} initialized with default values.
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    int resolveBufferCapacity(int defaultCapacity) {
        return bufferCapacity == 0 ? defaultCapacity : bufferCapacity;
    }

    String resolveName(String fallback) {
        return (name == null || name.isBlank()) ? fallback : name;
    }

    StageKind resolveEffectiveKind(PipeConfig.ExecutorMode executorMode) {
        if (kind != StageKind.INHERIT) {
            return kind;
        }
        return switch (executorMode) {
            case VIRTUAL_THREADS -> StageKind.IO_BOUND;
            case PLATFORM_THREADS, CUSTOM -> StageKind.CPU_BOUND;
        };
    }

    /**
     * Fluent builder for {@link StageConfig}.
     */
    public static final class Builder {

        private String name;
        private StageKind kind = StageKind.INHERIT;
        private int minConcurrency = 1;
        private int initialConcurrency = 1;
        private int maxConcurrency = 1;
        private int bufferCapacity = 0;
        private OverflowPolicy overflowPolicy = OverflowPolicy.BLOCK;
        private ErrorPolicy errorPolicy = ErrorPolicy.INHERIT;
        private boolean errorPolicyExplicit;
        private RetrySpec retrySpec = RetrySpec.none();
        private DeadLetterHandler<Object> deadLetterHandler;
        private Runnable onComplete;

        private Builder() {
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder kind(StageKind kind) {
            this.kind = Objects.requireNonNull(kind, "kind must not be null");
            return this;
        }

        /**
         * Sets fixed static concurrency ({@code min = initial = max = parallelism}).
         *
         * @param parallelism fixed worker concurrency ({@code >= 1})
         * @return this builder
         */
        public Builder parallelism(int parallelism) {
            this.minConcurrency = parallelism;
            this.initialConcurrency = parallelism;
            this.maxConcurrency = parallelism;
            return this;
        }

        /**
         * Sets elastic concurrency bounds with initial concurrency equal to {@code min}.
         *
         * @param min minimum permits ({@code >= 1})
         * @param max maximum permits ({@code >= min})
         * @return this builder
         */
        public Builder concurrency(int min, int max) {
            return concurrency(min, min, max);
        }

        /**
         * Sets elastic concurrency bounds with an explicit starting permit target.
         *
         * @param min minimum permits ({@code >= 1})
         * @param initial initial permits in {@code [min, max]}
         * @param max maximum permits ({@code >= initial})
         * @return this builder
         */
        public Builder concurrency(int min, int initial, int max) {
            this.minConcurrency = min;
            this.initialConcurrency = initial;
            this.maxConcurrency = max;
            return this;
        }

        public Builder bufferCapacity(int bufferCapacity) {
            this.bufferCapacity = bufferCapacity;
            return this;
        }

        public Builder overflowPolicy(OverflowPolicy overflowPolicy) {
            this.overflowPolicy = Objects.requireNonNull(overflowPolicy, "overflowPolicy must not be null");
            return this;
        }

        public Builder errorPolicy(ErrorPolicy errorPolicy) {
            this.errorPolicy = Objects.requireNonNull(errorPolicy, "errorPolicy must not be null");
            this.errorPolicyExplicit = true;
            return this;
        }

        /**
         * Configures retries for this stage and selects {@link ErrorPolicy#RETRY} unless
         * {@link #errorPolicy(ErrorPolicy)} was set explicitly.
         *
         * @param retrySpec retry specification
         * @return this builder
         */
        public Builder retry(RetrySpec retrySpec) {
            this.retrySpec = Objects.requireNonNull(retrySpec, "retrySpec must not be null");
            if (!errorPolicyExplicit) {
                this.errorPolicy = ErrorPolicy.RETRY;
            }
            return this;
        }

        /**
         * Configures a dead-letter callback receiving failed items. If no explicit {@link #errorPolicy}
         * or {@link #retry} is active, sets {@link ErrorPolicy#DEAD_LETTER}; when paired with
         * {@link #retry(RetrySpec)}, items exhausting retries are forwarded to this handler.
         *
         * @param deadLetterHandler dead-letter callback
         * @return this builder
         */
        public Builder deadLetter(DeadLetterHandler<Object> deadLetterHandler) {
            this.deadLetterHandler = Objects.requireNonNull(
                    deadLetterHandler, "deadLetterHandler must not be null");
            if (!errorPolicyExplicit && this.errorPolicy == ErrorPolicy.INHERIT) {
                this.errorPolicy = ErrorPolicy.DEAD_LETTER;
            }
            return this;
        }

        /**
         * Configures a type-checked dead-letter callback receiving failed items cast to {@code itemType}.
         *
         * @param itemType expected runtime type of items entering the stage
         * @param deadLetterHandler typed dead-letter callback
         * @param <T> expected item type
         * @return this builder
         */
        public <T> Builder deadLetter(Class<T> itemType, DeadLetterHandler<? super T> deadLetterHandler) {
            return deadLetter(DeadLetterHandler.typed(itemType, deadLetterHandler));
        }

        public Builder onComplete(Runnable onComplete) {
            this.onComplete = onComplete;
            return this;
        }

        public StageConfig build() {
            return new StageConfig(
                    name,
                    kind,
                    minConcurrency,
                    initialConcurrency,
                    maxConcurrency,
                    bufferCapacity,
                    overflowPolicy,
                    errorPolicy,
                    retrySpec,
                    deadLetterHandler,
                    onComplete);
        }
    }
}
