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
package xyz.scarabya.eazypipe.adaptive;

/**
 * Operational classification of a pipeline stage computed from windowed EMA telemetry.
 */
public enum StageState {
    /** Input channel is nearly empty and the stage spends significant time waiting for work. */
    STARVED,
    /** Output channel is nearly full or upstream emission is blocked by downstream saturation. */
    BACKPRESSURED,
    /** Input channel has queued backlog while the downstream channel has free capacity. */
    BOTTLENECK,
    /** Input and output utilization are within the configured deadband tolerance. */
    BALANCED
}
