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
 * Terminal stage of a pipeline that consumes incoming items.
 *
 * @param <I> the input item type
 */
@FunctionalInterface
public interface PipeSink<I> {

    /**
     * Consumes a single input item.
     *
     * @param item the item received from the upstream stage
     * @throws Exception if an error occurs while consuming the item
     */
    void consume(I item) throws Exception;
}
