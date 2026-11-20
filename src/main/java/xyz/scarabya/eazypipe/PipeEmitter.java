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
 * Downstream emission handle passed to {@link PipeSource} and {@link PipeProcessor} stages.
 *
 * <p>Emitting items respects channel backpressure: if the downstream buffer is full,
 * {@link #emit(Object)} blocks until space becomes available or the pipeline is aborted/interrupted.
 *
 * @param <T> the type of items accepted by this emitter
 */
@FunctionalInterface
public interface PipeEmitter<T> {

    /**
     * Emits a single non-null item to the downstream stage.
     *
     * @param item the item to emit (must not be {@code null})
     * @throws InterruptedException if the worker thread is interrupted while waiting for buffer space
     * @throws NullPointerException if {@code item} is {@code null}
     */
    void emit(T item) throws InterruptedException;

    /**
     * Emits every item from the given {@link Iterable} in iteration order.
     *
     * @param items the items to emit (must not be {@code null} and must not contain {@code null} elements)
     * @throws InterruptedException if the worker thread is interrupted while waiting for buffer space
     * @throws NullPointerException if {@code items} or any element within it is {@code null}
     */
    default void emitAll(Iterable<? extends T> items) throws InterruptedException {
        Objects.requireNonNull(items, "items must not be null");
        for (T item : items) {
            emit(item);
        }
    }
}
