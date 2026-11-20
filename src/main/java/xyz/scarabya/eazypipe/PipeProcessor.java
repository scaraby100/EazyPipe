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
 * Intermediate processing stage transforming each input item into zero, one, or multiple output items.
 *
 * @param <I> the input item type
 * @param <O> the output item type
 */
@FunctionalInterface
public interface PipeProcessor<I, O> {

    /**
     * Processes a single input item and optionally emits output items downstream.
     *
     * @param item the input item
     * @param emitter the downstream emitter
     * @throws Exception if an error occurs while processing the item
     */
    void process(I item, PipeEmitter<O> emitter) throws Exception;
}
