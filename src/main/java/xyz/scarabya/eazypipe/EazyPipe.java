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
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Entry point for building concurrent, backpressured {@link Pipeline}s.
 *
 * <p>Example usage:
 * <pre>{@code
 * List<String> results = EazyPipe.of("alpha", "beta", "gamma")
 *         .filter(s -> s.length() > 4)
 *         .map(StageConfig.parallel(2), String::toUpperCase)
 *         .toList();
 * }</pre>
 */
public final class EazyPipe {

    private EazyPipe() {
    }

    /**
     * Creates a pipeline backed by the given {@link PipeSource} using default configuration.
     *
     * @param source item producer
     * @param <T> produced item type
     * @return a new {@link Pipeline}
     */
    public static <T> Pipeline<T> from(PipeSource<T> source) {
        return from(PipeConfig.defaults(), StageConfig.defaults(), source);
    }

    /**
     * Creates a pipeline backed by the given {@link PipeSource} and source {@link StageConfig}.
     *
     * @param stageConfig source stage configuration
     * @param source item producer
     * @param <T> produced item type
     * @return a new {@link Pipeline}
     */
    public static <T> Pipeline<T> from(StageConfig stageConfig, PipeSource<T> source) {
        return from(PipeConfig.defaults(), stageConfig, source);
    }

    /**
     * Creates a pipeline backed by the given {@link PipeSource} and global {@link PipeConfig}.
     *
     * @param config global pipeline configuration
     * @param source item producer
     * @param <T> produced item type
     * @return a new {@link Pipeline}
     */
    public static <T> Pipeline<T> from(PipeConfig config, PipeSource<T> source) {
        return from(config, StageConfig.defaults(), source);
    }

    /**
     * Creates a pipeline backed by the given {@link PipeSource}, global {@link PipeConfig},
     * and source {@link StageConfig}.
     *
     * @param config global pipeline configuration
     * @param stageConfig source stage configuration
     * @param source item producer
     * @param <T> produced item type
     * @return a new {@link Pipeline}
     */
    public static <T> Pipeline<T> from(
            PipeConfig config,
            StageConfig stageConfig,
            PipeSource<T> source) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(stageConfig, "stageConfig must not be null");
        Objects.requireNonNull(source, "source must not be null");
        return new Pipeline<>(
                config,
                new StageDefinition.SourceStage<>(stageConfig, source),
                List.of());
    }

    /**
     * Creates a pipeline emitting elements from the given {@link Iterable}.
     *
     * @param iterable source iterable
     * @param <T> item type
     * @return a new {@link Pipeline}
     */
    public static <T> Pipeline<T> fromIterable(Iterable<? extends T> iterable) {
        return fromIterable(PipeConfig.defaults(), iterable);
    }

    /**
     * Creates a pipeline emitting elements from the given {@link Iterable} with explicit {@link PipeConfig}.
     *
     * @param config global pipeline configuration
     * @param iterable source iterable
     * @param <T> item type
     * @return a new {@link Pipeline}
     */
    public static <T> Pipeline<T> fromIterable(PipeConfig config, Iterable<? extends T> iterable) {
        Objects.requireNonNull(iterable, "iterable must not be null");
        return from(config, StageConfig.named("iterable-source"), emitter -> emitter.emitAll(iterable));
    }

    /**
     * Creates a pipeline lazily consuming a {@link Stream} provided by {@code streamSupplier}.
     * The supplied stream is closed automatically via try-with-resources when production completes,
     * fails, or is cancelled.
     *
     * @param streamSupplier supplier creating the stream when the pipeline executes
     * @param <T> item type
     * @return a new {@link Pipeline}
     */
    public static <T> Pipeline<T> fromStream(Supplier<? extends Stream<? extends T>> streamSupplier) {
        return fromStream(PipeConfig.defaults(), streamSupplier);
    }

    /**
     * Creates a pipeline lazily consuming a {@link Stream} provided by {@code streamSupplier}
     * with explicit {@link PipeConfig}.
     *
     * @param config global pipeline configuration
     * @param streamSupplier supplier creating the stream when the pipeline executes
     * @param <T> item type
     * @return a new {@link Pipeline}
     */
    public static <T> Pipeline<T> fromStream(
            PipeConfig config,
            Supplier<? extends Stream<? extends T>> streamSupplier) {
        Objects.requireNonNull(streamSupplier, "streamSupplier must not be null");
        return from(config, StageConfig.named("stream-source"), emitter -> {
            try (Stream<? extends T> stream = Objects.requireNonNull(
                    streamSupplier.get(), "streamSupplier must not return null")) {
                Iterator<? extends T> iterator = stream.iterator();
                while (iterator.hasNext()) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Stream source interrupted");
                    }
                    emitter.emit(iterator.next());
                }
            }
        });
    }

    /**
     * Creates a pipeline emitting the provided fixed items in order.
     *
     * @param items non-null elements to emit
     * @param <T> item type
     * @return a new {@link Pipeline}
     */
    @SafeVarargs
    public static <T> Pipeline<T> of(T... items) {
        Objects.requireNonNull(items, "items must not be null");
        List<T> snapshot = new ArrayList<>(items.length);
        for (T item : items) {
            snapshot.add(Objects.requireNonNull(item, "items must not contain null elements"));
        }
        return fromIterable(Collections.unmodifiableList(snapshot));
    }
}
