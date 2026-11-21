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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/**
 * Immutable, reusable fluent pipeline builder producing items of type {@code T}.
 *
 * <p>Intermediate operations ({@link #map}, {@link #mapConcurrentOrdered}, {@link #filter},
 * {@link #flatMap}, {@link #batch}, {@link #rateLimit}, {@link #tee}, {@link #stage},
 * {@link #peek}, {@link #configure}) return a new {@link Pipeline} instance without mutating
 * the current one, so a shared prefix can be branched safely into multiple terminal executions.
 *
 * @param <T> current output item type of the pipeline
 */
public final class Pipeline<T> {

    private final PipeConfig config;
    private final StageDefinition.SourceStage<?> sourceStage;
    private final List<StageDefinition> intermediateStages;

    Pipeline(
            PipeConfig config,
            StageDefinition.SourceStage<?> sourceStage,
            List<StageDefinition> intermediateStages) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.sourceStage = Objects.requireNonNull(sourceStage, "sourceStage must not be null");
        this.intermediateStages = List.copyOf(intermediateStages);
    }

    private <R> Pipeline<R> appendStage(StageDefinition nextStage) {
        List<StageDefinition> next = new ArrayList<>(intermediateStages.size() + 1);
        next.addAll(intermediateStages);
        next.add(nextStage);
        return new Pipeline<>(config, sourceStage, next);
    }

    /**
     * Appends a 1-to-1 mapping stage with default {@link StageConfig}.
     */
    public <R> Pipeline<R> map(PipeFunction<? super T, ? extends R> mapper) {
        return map(StageConfig.defaults(), mapper);
    }

    /**
     * Appends a 1-to-1 mapping stage with explicit {@link StageConfig}.
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
     * Appends a concurrent mapping stage that executes items in parallel across up to
     * {@link StageConfig#maxConcurrency()} workers while emitting downstream strictly in input FIFO order.
     *
     * @param stageConfig stage configuration
     * @param mapper mapping function (must not return {@code null})
     * @param <R> output type
     * @return a new {@link Pipeline}
     */
    public <R> Pipeline<R> mapConcurrentOrdered(
            StageConfig stageConfig,
            PipeFunction<? super T, ? extends R> mapper) {
        Objects.requireNonNull(stageConfig, "stageConfig must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        return appendStage(new StageDefinition.OrderedMapStage<>(stageConfig, mapper));
    }

    /**
     * Appends a filtering stage with default {@link StageConfig}.
     */
    public Pipeline<T> filter(PipePredicate<? super T> predicate) {
        return filter(StageConfig.defaults(), predicate);
    }

    /**
     * Appends a filtering stage with explicit {@link StageConfig}.
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
     */
    public <R> Pipeline<R> flatMap(PipeFunction<? super T, ? extends Iterable<? extends R>> mapper) {
        return flatMap(StageConfig.defaults(), mapper);
    }

    /**
     * Appends a 1-to-many expansion stage with explicit {@link StageConfig}.
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
     * Groups incoming items into immutable {@link List} batches, flushing when either {@code maxSize}
     * items are collected or {@code maxWait} elapses since the first item in the batch arrived.
     */
    public Pipeline<List<T>> batch(int maxSize, Duration maxWait) {
        return batch(StageConfig.defaults(), maxSize, maxWait);
    }

    /**
     * Named overload of {@link #batch(int, Duration)}.
     */
    public Pipeline<List<T>> batch(String stageName, int maxSize, Duration maxWait) {
        return batch(StageConfig.named(stageName), maxSize, maxWait);
    }

    /**
     * Configurable overload of {@link #batch(int, Duration)}.
     */
    public Pipeline<List<T>> batch(StageConfig stageConfig, int maxSize, Duration maxWait) {
        Objects.requireNonNull(stageConfig, "stageConfig must not be null");
        return appendStage(new StageDefinition.BatchStage<>(stageConfig, maxSize, maxWait));
    }

    /**
     * Throttles throughput passing through this point in the pipeline to at most {@code permitsPerSecond}.
     */
    public Pipeline<T> rateLimit(double permitsPerSecond) {
        return rateLimit(StageConfig.defaults(), permitsPerSecond, 1);
    }

    /**
     * Throttles throughput passing through this point in the pipeline to at most {@code permitsPerSecond}
     * while allowing an initial/idle burst of up to {@code burst} permits.
     */
    public Pipeline<T> rateLimit(double permitsPerSecond, int burst) {
        return rateLimit(StageConfig.defaults(), permitsPerSecond, burst);
    }

    /**
     * Configurable overload of {@link #rateLimit(double)}.
     */
    public Pipeline<T> rateLimit(StageConfig stageConfig, double permitsPerSecond) {
        return rateLimit(stageConfig, permitsPerSecond, 1);
    }

    /**
     * Configurable overload of {@link #rateLimit(double, int)}.
     */
    public Pipeline<T> rateLimit(StageConfig stageConfig, double permitsPerSecond, int burst) {
        Objects.requireNonNull(stageConfig, "stageConfig must not be null");
        return appendStage(new StageDefinition.RateLimitStage<>(stageConfig, permitsPerSecond, burst));
    }

    /**
     * Forks every passing item asynchronously to {@code sideSink} while continuing to forward it downstream.
     */
    public Pipeline<T> tee(PipeSink<? super T> sideSink) {
        return tee(StageConfig.defaults(), sideSink);
    }

    /**
     * Configurable overload of {@link #tee(PipeSink)} controlling buffering, concurrency,
     * and error/overflow behavior of the side branch.
     */
    public Pipeline<T> tee(StageConfig sideStageConfig, PipeSink<? super T> sideSink) {
        Objects.requireNonNull(sideStageConfig, "sideStageConfig must not be null");
        Objects.requireNonNull(sideSink, "sideSink must not be null");
        return appendStage(new StageDefinition.TeeStage<>(
                StageConfig.named(sideStageConfig.name() != null ? sideStageConfig.name() + "-main" : null),
                sideStageConfig,
                sideSink));
    }

    /**
     * Appends a general {@link PipeProcessor} stage with default {@link StageConfig}.
     */
    public <R> Pipeline<R> stage(PipeProcessor<? super T, R> processor) {
        return stage(StageConfig.defaults(), processor);
    }

    /**
     * Appends a general {@link PipeProcessor} stage with explicit {@link StageConfig}.
     */
    public <R> Pipeline<R> stage(StageConfig stageConfig, PipeProcessor<? super T, R> processor) {
        Objects.requireNonNull(stageConfig, "stageConfig must not be null");
        Objects.requireNonNull(processor, "processor must not be null");
        return appendStage(new StageDefinition.ProcessorStage<>(stageConfig, processor));
    }

    /**
     * Appends a pass-through side-effect stage with default {@link StageConfig}.
     */
    public Pipeline<T> peek(PipeSink<? super T> action) {
        return peek(StageConfig.defaults(), action);
    }

    /**
     * Appends a pass-through side-effect stage with explicit {@link StageConfig}.
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
     */
    public Pipeline<T> configure(PipeConfig config) {
        return new Pipeline<>(
                Objects.requireNonNull(config, "config must not be null"),
                sourceStage,
                intermediateStages);
    }

    // -------------------------------------------------------------------------
    // Asynchronous Terminal Operations
    // -------------------------------------------------------------------------

    /**
     * Starts executing this pipeline asynchronously into the given {@link PipeSink}.
     */
    public PipeExecution<Void> start(PipeSink<? super T> sink) {
        return start(StageConfig.defaults(), sink);
    }

    /**
     * Starts executing this pipeline asynchronously into the given {@link PipeSink}
     * with explicit {@link StageConfig}.
     */
    public PipeExecution<Void> start(StageConfig stageConfig, PipeSink<? super T> sink) {
        Objects.requireNonNull(stageConfig, "stageConfig must not be null");
        Objects.requireNonNull(sink, "sink must not be null");
        StageDefinition.SinkStage<T, Void> sinkStage =
                new StageDefinition.SinkStage<>(stageConfig, sink, () -> null);
        return PipelineRuntime.launch(config, sourceStage, intermediateStages, sinkStage);
    }

    /**
     * Starts executing this pipeline asynchronously, broadcasting every item concurrently to all {@code sinks}.
     */
    @SafeVarargs
    public final PipeExecution<Void> startBroadcast(PipeSink<? super T>... sinks) {
        Objects.requireNonNull(sinks, "sinks must not be null");
        List<PipeSink<? super T>> copy = new ArrayList<>(sinks.length);
        for (PipeSink<? super T> s : sinks) {
            copy.add(Objects.requireNonNull(s, "sinks must not contain null elements"));
        }
        return startBroadcast(StageConfig.defaults(), copy);
    }

    /**
     * Starts executing this pipeline asynchronously, broadcasting every item concurrently to all {@code sinks}.
     */
    public PipeExecution<Void> startBroadcast(List<PipeSink<? super T>> sinks) {
        return startBroadcast(StageConfig.defaults(), sinks);
    }

    /**
     * Configurable overload of {@link #startBroadcast(List)}.
     */
    public PipeExecution<Void> startBroadcast(StageConfig stageConfig, List<PipeSink<? super T>> sinks) {
        Objects.requireNonNull(stageConfig, "stageConfig must not be null");
        Objects.requireNonNull(sinks, "sinks must not be null");
        return PipelineRuntime.launch(
                config,
                sourceStage,
                intermediateStages,
                new StageDefinition.BroadcastSinkStage<>(stageConfig, sinks));
    }

    /**
     * Starts executing this pipeline asynchronously, routing each item via {@code classifier}
     * to the matching sink in {@code routes} (failing if no route matches).
     */
    public <K> PipeExecution<Void> startRoute(
            PipeFunction<? super T, ? extends K> classifier,
            Map<K, PipeSink<? super T>> routes) {
        return startRoute(StageConfig.defaults(), classifier, routes, null);
    }

    /**
     * Starts executing this pipeline asynchronously, routing each item via {@code classifier}
     * to the matching sink in {@code routes} or falling back to {@code defaultSink}.
     */
    public <K> PipeExecution<Void> startRoute(
            StageConfig stageConfig,
            PipeFunction<? super T, ? extends K> classifier,
            Map<K, PipeSink<? super T>> routes,
            PipeSink<? super T> defaultSink) {
        Objects.requireNonNull(stageConfig, "stageConfig must not be null");
        Objects.requireNonNull(classifier, "classifier must not be null");
        Objects.requireNonNull(routes, "routes must not be null");
        return PipelineRuntime.launch(
                config,
                sourceStage,
                intermediateStages,
                new StageDefinition.RouteSinkStage<>(stageConfig, classifier, routes, defaultSink));
    }

    /**
     * Starts executing this pipeline asynchronously and accumulates results using a standard {@link Collector}.
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
        return PipelineRuntime.launch(config, sourceStage, intermediateStages, sinkStage);
    }

    /**
     * Starts executing this pipeline asynchronously and collects all items into an unmodifiable {@link List}.
     */
    public PipeExecution<List<T>> startToList() {
        return startCollect(Collectors.toUnmodifiableList());
    }

    // -------------------------------------------------------------------------
    // Synchronous Terminal Operations
    // -------------------------------------------------------------------------

    /**
     * Executes this pipeline synchronously into the given {@link PipeSink} and waits for completion.
     */
    public void run(PipeSink<? super T> sink) {
        run(StageConfig.defaults(), sink);
    }

    /**
     * Executes this pipeline synchronously into the given {@link PipeSink} with explicit {@link StageConfig}.
     */
    public void run(StageConfig stageConfig, PipeSink<? super T> sink) {
        try (PipeExecution<Void> execution = start(stageConfig, sink)) {
            execution.join();
        }
    }

    /**
     * Executes this pipeline synchronously, broadcasting every item to all {@code sinks}.
     */
    @SafeVarargs
    public final void runBroadcast(PipeSink<? super T>... sinks) {
        try (PipeExecution<Void> execution = startBroadcast(sinks)) {
            execution.join();
        }
    }

    /**
     * Executes this pipeline synchronously, broadcasting every item to all {@code sinks}.
     */
    public void runBroadcast(List<PipeSink<? super T>> sinks) {
        try (PipeExecution<Void> execution = startBroadcast(sinks)) {
            execution.join();
        }
    }

    /**
     * Executes this pipeline synchronously, routing each item via {@code classifier}.
     */
    public <K> void runRoute(
            PipeFunction<? super T, ? extends K> classifier,
            Map<K, PipeSink<? super T>> routes) {
        try (PipeExecution<Void> execution = startRoute(classifier, routes)) {
            execution.join();
        }
    }

    /**
     * Executes this pipeline synchronously, routing each item via {@code classifier} or {@code defaultSink}.
     */
    public <K> void runRoute(
            StageConfig stageConfig,
            PipeFunction<? super T, ? extends K> classifier,
            Map<K, PipeSink<? super T>> routes,
            PipeSink<? super T> defaultSink) {
        try (PipeExecution<Void> execution = startRoute(stageConfig, classifier, routes, defaultSink)) {
            execution.join();
        }
    }

    /**
     * Executes this pipeline synchronously and reduces all output items with the given {@link Collector}.
     */
    public <A, R> R collect(Collector<? super T, A, R> collector) {
        try (PipeExecution<R> execution = startCollect(collector)) {
            return execution.join();
        }
    }

    /**
     * Executes this pipeline synchronously and collects all output items into an unmodifiable {@link List}.
     */
    public List<T> toList() {
        try (PipeExecution<List<T>> execution = startToList()) {
            return execution.join();
        }
    }

    /**
     * Executes this pipeline synchronously and returns the total number of items reaching the sink.
     */
    public long count() {
        AtomicLong counter = new AtomicLong();
        StageDefinition.SinkStage<T, Long> sinkStage = new StageDefinition.SinkStage<>(
                StageConfig.named("count"),
                item -> counter.incrementAndGet(),
                counter::get);
        try (PipeExecution<Long> execution =
                     PipelineRuntime.launch(config, sourceStage, intermediateStages, sinkStage)) {
            return execution.join();
        }
    }
}
