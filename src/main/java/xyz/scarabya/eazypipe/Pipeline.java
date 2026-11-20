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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/**
 * Immutable, reusable fluent pipeline builder producing items of type {@code T}.
 *
 * <p>Intermediate operations ({@link #map}, {@link #filter}, {@link #flatMap}, {@link #stage},
 * {@link #peek}, {@link #configure}) return a new {@link Pipeline} instance without mutating
 * the current one, so a shared prefix can be branched safely into multiple terminal executions.
 *
 * @param <T> current output item type of the pipeline
 */
public final class Pipeline<T> {

    private final PipeConfig config;
    private final StageDefinition.SourceStage<?> sourceStage;
    private final List<StageDefinition.ProcessorStage<?, ?>> processorStages;

    Pipeline(
            PipeConfig config,
            StageDefinition.SourceStage<?> sourceStage,
            List<StageDefinition.ProcessorStage<?, ?>> processorStages) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.sourceStage = Objects.requireNonNull(sourceStage, "sourceStage must not be null");
        this.processorStages = List.copyOf(processorStages);
    }

    /**
     * Appends a 1-to-1 mapping stage with default {@link StageConfig}.
     *
     * @param mapper mapping function (must not return {@code null})
     * @param <R> output type
     * @return a new {@link Pipeline}
     */
    public <R> Pipeline<R> map(PipeFunction<? super T, ? extends R> mapper) {
        return map(StageConfig.defaults(), mapper);
    }

    /**
     * Appends a 1-to-1 mapping stage with explicit {@link StageConfig}.
     *
     * @param stageConfig stage configuration
     * @param mapper mapping function (must not return {@code null})
     * @param <R> output type
     * @return a new {@link Pipeline}
     */
    public <R> Pipeline<R> map(StageConfig stageConfig, PipeFunction<? super T, ? extends R> mapper) {
        Objects.requireNonNull(stageConfig, "stageConfig must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        return stage(stageConfig, (item, emitter) -> {
            R mapped = Objects.requireNonNull(mapper.apply(item), "Mapper function must not return null");
            emitter.emit(mapped);
        });
    }

    /**
     * Appends a filtering stage with default {@link StageConfig}.
     *
     * @param predicate predicate selecting items to forward
     * @return a new {@link Pipeline}
     */
    public Pipeline<T> filter(PipePredicate<? super T> predicate) {
        return filter(StageConfig.defaults(), predicate);
    }

    /**
     * Appends a filtering stage with explicit {@link StageConfig}.
     *
     * @param stageConfig stage configuration
     * @param predicate predicate selecting items to forward
     * @return a new {@link Pipeline}
     */
    public Pipeline<T> filter(StageConfig stageConfig, PipePredicate<? super T> predicate) {
        Objects.requireNonNull(stageConfig, "stageConfig must not be null");
        Objects.requireNonNull(predicate, "predicate must not be null");
        return stage(stageConfig, (item, emitter) -> {
            if (predicate.test(item)) {
                emitter.emit(item);
            }
        });
    }

    /**
     * Appends a 1-to-many expansion stage with default {@link StageConfig}.
     *
     * @param mapper function returning an {@link Iterable} of downstream items (or {@code null} for none)
     * @param <R> output type
     * @return a new {@link Pipeline}
     */
    public <R> Pipeline<R> flatMap(PipeFunction<? super T, ? extends Iterable<? extends R>> mapper) {
        return flatMap(StageConfig.defaults(), mapper);
    }

    /**
     * Appends a 1-to-many expansion stage with explicit {@link StageConfig}.
     *
     * @param stageConfig stage configuration
     * @param mapper function returning an {@link Iterable} of downstream items (or {@code null} for none)
     * @param <R> output type
     * @return a new {@link Pipeline}
     */
    public <R> Pipeline<R> flatMap(
            StageConfig stageConfig,
            PipeFunction<? super T, ? extends Iterable<? extends R>> mapper) {
        Objects.requireNonNull(stageConfig, "stageConfig must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        return stage(stageConfig, (item, emitter) -> {
            Iterable<? extends R> results = mapper.apply(item);
            if (results != null) {
                emitter.emitAll(results);
            }
        });
    }

    /**
     * Appends a general {@link PipeProcessor} stage with default {@link StageConfig}.
     *
     * @param processor processor logic
     * @param <R> output type
     * @return a new {@link Pipeline}
     */
    public <R> Pipeline<R> stage(PipeProcessor<? super T, R> processor) {
        return stage(StageConfig.defaults(), processor);
    }

    /**
     * Appends a general {@link PipeProcessor} stage with explicit {@link StageConfig}.
     *
     * @param stageConfig stage configuration
     * @param processor processor logic
     * @param <R> output type
     * @return a new {@link Pipeline}
     */
    public <R> Pipeline<R> stage(StageConfig stageConfig, PipeProcessor<? super T, R> processor) {
        Objects.requireNonNull(stageConfig, "stageConfig must not be null");
        Objects.requireNonNull(processor, "processor must not be null");
        List<StageDefinition.ProcessorStage<?, ?>> next = new ArrayList<>(processorStages.size() + 1);
        next.addAll(processorStages);
        next.add(new StageDefinition.ProcessorStage<>(stageConfig, processor));
        return new Pipeline<>(config, sourceStage, next);
    }

    /**
     * Appends a pass-through side-effect stage with default {@link StageConfig}.
     *
     * @param action action invoked for each passing item
     * @return a new {@link Pipeline}
     */
    public Pipeline<T> peek(PipeSink<? super T> action) {
        return peek(StageConfig.defaults(), action);
    }

    /**
     * Appends a pass-through side-effect stage with explicit {@link StageConfig}.
     *
     * @param stageConfig stage configuration
     * @param action action invoked for each passing item
     * @return a new {@link Pipeline}
     */
    public Pipeline<T> peek(StageConfig stageConfig, PipeSink<? super T> action) {
        Objects.requireNonNull(stageConfig, "stageConfig must not be null");
        Objects.requireNonNull(action, "action must not be null");
        return stage(stageConfig, (item, emitter) -> {
            action.consume(item);
            emitter.emit(item);
        });
    }

    /**
     * Returns a copy of this pipeline using the supplied {@link PipeConfig}.
     *
     * @param config new global execution configuration
     * @return a new {@link Pipeline}
     */
    public Pipeline<T> configure(PipeConfig config) {
        return new Pipeline<>(
                Objects.requireNonNull(config, "config must not be null"),
                sourceStage,
                processorStages);
    }

    /**
     * Starts executing this pipeline asynchronously into the given {@link PipeSink}.
     *
     * @param sink terminal item consumer
     * @return an active {@link PipeExecution} handle
     */
    public PipeExecution<Void> start(PipeSink<? super T> sink) {
        return start(StageConfig.defaults(), sink);
    }

    /**
     * Starts executing this pipeline asynchronously into the given {@link PipeSink}
     * with explicit {@link StageConfig}.
     *
     * @param stageConfig terminal stage configuration
     * @param sink terminal item consumer
     * @return an active {@link PipeExecution} handle
     */
    public PipeExecution<Void> start(StageConfig stageConfig, PipeSink<? super T> sink) {
        Objects.requireNonNull(stageConfig, "stageConfig must not be null");
        Objects.requireNonNull(sink, "sink must not be null");
        StageDefinition.SinkStage<T, Void> sinkStage =
                new StageDefinition.SinkStage<>(stageConfig, sink, () -> null);
        return PipelineRuntime.launch(config, sourceStage, processorStages, sinkStage);
    }

    /**
     * Starts executing this pipeline asynchronously and accumulates results using a standard {@link Collector}.
     * The collector sink runs with {@code parallelism = 1} so non-concurrent collectors are safe.
     *
     * @param collector Java {@link Collector}
     * @param <A> mutable accumulation type
     * @param <R> final result type
     * @return an active {@link PipeExecution} producing the collected result
     */
    public <A, R> PipeExecution<R> startCollect(Collector<? super T, A, R> collector) {
        Objects.requireNonNull(collector, "collector must not be null");
        A accumulator = collector.supplier().get();
        BiConsumer<A, ? super T> accumulatorFn = collector.accumulator();
        Function<A, R> finisher = collector.finisher();
        StageConfig sinkConfig = StageConfig.builder()
                .name("collect")
                .parallelism(1)
                .build();
        StageDefinition.SinkStage<T, R> sinkStage = new StageDefinition.SinkStage<>(
                sinkConfig,
                item -> accumulatorFn.accept(accumulator, item),
                () -> finisher.apply(accumulator));
        return PipelineRuntime.launch(config, sourceStage, processorStages, sinkStage);
    }

    /**
     * Starts executing this pipeline asynchronously and collects all items into an unmodifiable {@link List}.
     *
     * @return an active {@link PipeExecution} producing the resulting list
     */
    public PipeExecution<List<T>> startToList() {
        return startCollect(Collectors.toUnmodifiableList());
    }

    /**
     * Executes this pipeline synchronously into the given {@link PipeSink} and waits for completion.
     *
     * @param sink terminal item consumer
     */
    public void run(PipeSink<? super T> sink) {
        run(StageConfig.defaults(), sink);
    }

    /**
     * Executes this pipeline synchronously into the given {@link PipeSink} with explicit {@link StageConfig}.
     *
     * @param stageConfig terminal stage configuration
     * @param sink terminal item consumer
     */
    public void run(StageConfig stageConfig, PipeSink<? super T> sink) {
        try (PipeExecution<Void> execution = start(stageConfig, sink)) {
            execution.join();
        }
    }

    /**
     * Executes this pipeline synchronously and reduces all output items with the given {@link Collector}.
     *
     * @param collector Java {@link Collector}
     * @param <A> mutable accumulation type
     * @param <R> final result type
     * @return the collected result
     */
    public <A, R> R collect(Collector<? super T, A, R> collector) {
        try (PipeExecution<R> execution = startCollect(collector)) {
            return execution.join();
        }
    }

    /**
     * Executes this pipeline synchronously and collects all output items into an unmodifiable {@link List}.
     *
     * @return an unmodifiable {@link List} containing all emitted items
     */
    public List<T> toList() {
        try (PipeExecution<List<T>> execution = startToList()) {
            return execution.join();
        }
    }

    /**
     * Executes this pipeline synchronously and returns the total number of items reaching the sink.
     *
     * @return item count
     */
    public long count() {
        AtomicLong counter = new AtomicLong();
        StageDefinition.SinkStage<T, Long> sinkStage = new StageDefinition.SinkStage<>(
                StageConfig.named("count"),
                item -> counter.incrementAndGet(),
                counter::get);
        try (PipeExecution<Long> execution =
                     PipelineRuntime.launch(config, sourceStage, processorStages, sinkStage)) {
            return execution.join();
        }
    }
}
