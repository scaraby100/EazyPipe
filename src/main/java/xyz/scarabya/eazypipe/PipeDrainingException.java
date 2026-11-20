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
 * Internal control-flow exception thrown by {@link BoundedChannel#send} when the pipeline enters
 * graceful drain mode, signaling source workers to stop emitting cleanly without failing the run.
 */
final class PipeDrainingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    PipeDrainingException() {
        super("Pipeline is draining", null, false, false);
    }
}
