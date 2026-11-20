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
 * Checked-exception-friendly predicate used by {@link Pipeline#filter(PipePredicate)}.
 *
 * @param <T> the input item type
 */
@FunctionalInterface
public interface PipePredicate<T> {

    /**
     * Evaluates this predicate on the given item.
     *
     * @param item the item to test
     * @return {@code true} if the item should pass downstream, {@code false} otherwise
     * @throws Exception if evaluation fails
     */
    boolean test(T item) throws Exception;
}
