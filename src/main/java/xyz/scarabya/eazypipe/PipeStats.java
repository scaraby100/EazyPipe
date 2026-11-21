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

import java.time.Duration;
import java.util.Objects;

/**
 * Execution metrics captured for a pipeline run.
 *
 * @param elapsed total duration from start until completion (or elapsed time so far if still running)
 * @param itemsProduced number of items emitted by the source stage
 * @param itemsConsumed number of items delivered to and completed by the terminal sink stage (or all broadcast branches without drop/skip)
 * @param errorsSkipped number of item-level errors skipped under {@link PipeConfig.ErrorStrategy#CONTINUE_ON_ITEM_ERROR}
 * @param stageCount total number of stages in the pipeline (source + intermediate + sink)
 */
public record PipeStats(
        Duration elapsed,
        long itemsProduced,
        long itemsConsumed,
        long errorsSkipped,
        int stageCount) {

    public PipeStats {
        Objects.requireNonNull(elapsed, "elapsed must not be null");
        if (itemsProduced < 0 || itemsConsumed < 0 || errorsSkipped < 0 || stageCount < 0) {
            throw new IllegalArgumentException("Stat counts must be non-negative");
        }
    }
}
