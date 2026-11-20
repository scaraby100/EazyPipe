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
 * Checked-exception-friendly mapping function used by {@link Pipeline#map(PipeFunction)}
 * and {@link Pipeline#flatMap(PipeFunction)}.
 *
 * @param <T> the input type
 * @param <R> the result type
 */
@FunctionalInterface
public interface PipeFunction<T, R> {

    /**
     * Applies this function to the given argument.
     *
     * @param item the input item
     * @return the mapped result
     * @throws Exception if mapping fails
     */
    R apply(T item) throws Exception;
}
