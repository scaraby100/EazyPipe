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
 * Policy applied when a stage's bounded input channel reaches its capacity.
 */
public enum OverflowPolicy {
    /** Block the upstream producer until capacity frees up (true backpressure, default). */
    BLOCK,
    /** Evict the oldest buffered item in the channel to make room for the incoming item. */
    DROP_OLDEST,
    /** Discard the incoming item immediately without blocking the upstream producer. */
    DROP_NEWEST,
    /** Fail the pipeline immediately with {@link ChannelOverflowException}. */
    FAIL
}
