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

/**
 * Per-stage configuration controlling concurrency, backpressure buffer capacity, naming, and completion callbacks.
 *
 * @param name optional stage name used in error reporting (may be {@code null})
 * @param parallelism number of concurrent worker tasks for this stage ({@code >= 1})
 * @param bufferCapacity capacity of this stage's bounded input channel ({@code 0} inherits {@link PipeConfig#defaultBufferCapacity()})
 * @param onComplete optional callback invoked once after all workers of this stage finish normally (may be {@code null})
 */
public record StageConfig(
        String name,
        int parallelism,
        int bufferCapacity,
        Runnable onComplete) {

    public StageConfig {
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be >= 1, got " + parallelism);
        }
        if (bufferCapacity < 0) {
            throw new IllegalArgumentException("bufferCapacity must be >= 0, got " + bufferCapacity);
        }
    }

    /**
     * Returns a default stage configuration (single worker, inherited buffer capacity, no callback).
     *
     * @return default {@link StageConfig}
     */
    public static StageConfig defaults() {
        return new StageConfig(null, 1, 0, null);
    }

    /**
     * Returns a configuration with the specified worker parallelism.
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

    /**
     * Fluent builder for {@link StageConfig}.
     */
    public static final class Builder {

        private String name;
        private int parallelism = 1;
        private int bufferCapacity = 0;
        private Runnable onComplete;

        private Builder() {
        }

        /**
         * Sets the stage name.
         *
         * @param name stage name
         * @return this builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the worker parallelism for this stage.
         *
         * @param parallelism number of concurrent workers ({@code >= 1})
         * @return this builder
         */
        public Builder parallelism(int parallelism) {
            this.parallelism = parallelism;
            return this;
        }

        /**
         * Sets the bounded buffer capacity for this stage.
         *
         * @param bufferCapacity capacity ({@code >= 0}, where {@code 0} inherits {@link PipeConfig#defaultBufferCapacity()})
         * @return this builder
         */
        public Builder bufferCapacity(int bufferCapacity) {
            this.bufferCapacity = bufferCapacity;
            return this;
        }

        /**
         * Sets a callback executed once after all workers of this stage complete normally.
         *
         * @param onComplete completion callback
         * @return this builder
         */
        public Builder onComplete(Runnable onComplete) {
            this.onComplete = onComplete;
            return this;
        }

        /**
         * Builds the {@link StageConfig}.
         *
         * @return a new {@link StageConfig}
         */
        public StageConfig build() {
            return new StageConfig(name, parallelism, bufferCapacity, onComplete);
        }
    }
}
