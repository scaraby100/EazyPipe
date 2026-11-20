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
 * Per-stage error policy controlling what happens when processing or consuming an item throws an exception.
 */
public enum ErrorPolicy {
    /** Delegate to the global {@link PipeConfig.ErrorStrategy} (default). */
    INHERIT,
    /** Immediately abort all channels and terminate the pipeline with {@link PipeException}. */
    FAIL_FAST,
    /** Skip the failing item, increment skipped metrics, notify any global handler, and continue. */
    SKIP,
    /** Forward the failing item, exception, and stage name to {@link DeadLetterHandler} and continue. */
    DEAD_LETTER,
    /** Retry according to {@link RetrySpec}; on exhaustion, route to {@link DeadLetterHandler} if present or fail fast. */
    RETRY
}
