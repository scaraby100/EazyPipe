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
 * Entry stage of a pipeline that produces items into a {@link PipeEmitter}.
 *
 * @param <O> the type of items emitted by this source
 */
@FunctionalInterface
public interface PipeSource<O> {

    /**
     * Produces items and sends them downstream through the supplied emitter.
     * The pipeline automatically closes the downstream channel once all workers for this stage return.
     *
     * @param emitter the downstream emitter
     * @throws Exception if an error occurs while producing items
     */
    void produce(PipeEmitter<O> emitter) throws Exception;
}
