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
 * Callback invoked when an item fails in a stage configured with {@link ErrorPolicy#DEAD_LETTER}
 * (or exhausts retries with a configured dead-letter handler).
 *
 * @param <T> type of the failed item
 */
@FunctionalInterface
public interface DeadLetterHandler<T> {

    /**
     * Handles a failed item.
     *
     * @param item the item that could not be processed/consumed
     * @param error the exception thrown while handling the item
     * @param stageName resolved name of the stage where the failure occurred
     */
    void handle(T item, Throwable error, String stageName);

    /**
     * Adapts a typed {@link DeadLetterHandler} for use on {@link StageConfig}.
     *
     * @param itemType expected runtime class of items entering the stage
     * @param handler typed dead-letter handler
     * @param <T> expected item type
     * @return an untyped {@link DeadLetterHandler} casting items via {@code itemType.cast(item)}
     */
    static <T> DeadLetterHandler<Object> typed(Class<T> itemType, DeadLetterHandler<? super T> handler) {
        Objects.requireNonNull(itemType, "itemType must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        return (item, error, stageName) -> handler.handle(itemType.cast(item), error, stageName);
    }
}
