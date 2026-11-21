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
 * Workload classification used for per-stage scheduling and adaptive concurrency allocation.
 */
public enum StageKind {
    /** Inherits the global {@link PipeConfig.ExecutorMode}. */
    INHERIT,
    /**
     * Compute-intensive stage whose items execute on a shared platform-thread pool sized by
     * {@link PipeConfig#cpuBudget()}. Per-item emissions are buffered until item completion;
     * for unbounded per-item streaming or fan-out expansions, use {@link #IO_BOUND}.
     */
    CPU_BOUND,
    /** Blocking or network I/O stage scheduled on Java 21 virtual threads with AIMD/Vegas concurrency control. */
    IO_BOUND
}
