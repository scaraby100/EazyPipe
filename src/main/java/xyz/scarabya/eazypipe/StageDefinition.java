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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Internal immutable description of a pipeline stage.
 */
sealed interface StageDefinition
        permits StageDefinition.SourceStage,
                StageDefinition.ProcessorStage,
                StageDefinition.OrderedMapStage,
                StageDefinition.BatchStage,
                StageDefinition.RateLimitStage,
                StageDefinition.TeeStage,
                StageDefinition.SinkStage,
                StageDefinition.BroadcastSinkStage,
                StageDefinition.RouteSinkStage {

    StageConfig config();

    record SourceStage<O>(StageConfig config, List<PipeSource<? extends O>> sources) implements StageDefinition {
        public SourceStage {
            Objects.requireNonNull(config, "config must not be null");
            Objects.requireNonNull(sources, "sources must not be null");
            if (sources.isEmpty()) {
                throw new IllegalArgumentException("sources must not be empty");
            }
            sources = List.copyOf(sources);
        }
    }

    record ProcessorStage<I, O>(StageConfig config, PipeProcessor<? super I, O> processor)
            implements StageDefinition {
        public ProcessorStage {
            Objects.requireNonNull(config, "config must not be null");
            Objects.requireNonNull(processor, "processor must not be null");
        }
    }

    record OrderedMapStage<I, O>(StageConfig config, PipeFunction<? super I, ? extends O> mapper)
            implements StageDefinition {
        public OrderedMapStage {
            Objects.requireNonNull(config, "config must not be null");
            Objects.requireNonNull(mapper, "mapper must not be null");
        }
    }

    record BatchStage<I>(StageConfig config, int maxSize, Duration maxWait) implements StageDefinition {
        public BatchStage {
            Objects.requireNonNull(config, "config must not be null");
            if (maxSize < 1) {
                throw new IllegalArgumentException("maxSize must be >= 1, got " + maxSize);
            }
            Objects.requireNonNull(maxWait, "maxWait must not be null");
            if (maxWait.isNegative()) {
                throw new IllegalArgumentException("maxWait must not be negative");
            }
        }
    }

    record RateLimitStage<I>(StageConfig config, double permitsPerSecond, int burst) implements StageDefinition {
        public RateLimitStage {
            Objects.requireNonNull(config, "config must not be null");
            if (Double.isNaN(permitsPerSecond) || Double.isInfinite(permitsPerSecond) || permitsPerSecond <= 0.0) {
                throw new IllegalArgumentException("permitsPerSecond must be positive and finite");
            }
            if (burst < 1) {
                throw new IllegalArgumentException("burst must be >= 1, got " + burst);
            }
        }
    }

    record TeeStage<I>(StageConfig config, StageConfig sideConfig, PipeSink<? super I> sideSink)
            implements StageDefinition {
        public TeeStage {
            Objects.requireNonNull(config, "config must not be null");
            Objects.requireNonNull(sideConfig, "sideConfig must not be null");
            Objects.requireNonNull(sideSink, "sideSink must not be null");
        }
    }

    record SinkStage<I, R>(StageConfig config, PipeSink<? super I> sink, Supplier<R> resultSupplier)
            implements StageDefinition {
        public SinkStage {
            Objects.requireNonNull(config, "config must not be null");
            Objects.requireNonNull(sink, "sink must not be null");
            Objects.requireNonNull(resultSupplier, "resultSupplier must not be null");
        }
    }

    record BroadcastSinkStage<I>(StageConfig config, List<PipeSink<? super I>> sinks) implements StageDefinition {
        public BroadcastSinkStage {
            Objects.requireNonNull(config, "config must not be null");
            Objects.requireNonNull(sinks, "sinks must not be null");
            if (sinks.isEmpty()) {
                throw new IllegalArgumentException("sinks must not be empty");
            }
            sinks = List.copyOf(sinks);
        }
    }

    record RouteSinkStage<I, K>(
            StageConfig config,
            PipeFunction<? super I, ? extends K> classifier,
            Map<K, PipeSink<? super I>> routes,
            PipeSink<? super I> defaultSink) implements StageDefinition {
        public RouteSinkStage {
            Objects.requireNonNull(config, "config must not be null");
            Objects.requireNonNull(classifier, "classifier must not be null");
            Objects.requireNonNull(routes, "routes must not be null");
            routes = Map.copyOf(routes);
        }
    }
}
