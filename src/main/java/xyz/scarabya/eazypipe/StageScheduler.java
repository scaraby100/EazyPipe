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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Hybrid per-stage executor coordinator supporting {@link StageKind#IO_BOUND} virtual threads,
 * {@link StageKind#CPU_BOUND} daemon platform threads, and caller-supplied custom executors.
 */
final class StageScheduler {

    private final PipeConfig.ExecutorMode globalMode;
    private final ExecutorService customExecutor;
    private volatile ExecutorService virtualExecutor;
    private volatile ExecutorService cpuPlatformExecutor;
    private final Object lock = new Object();

    StageScheduler(PipeConfig config) {
        this.globalMode = config.executorMode();
        this.customExecutor = config.customExecutor();
    }

    Future<?> submit(StageKind configuredKind, Runnable task) {
        return executorFor(configuredKind).submit(task);
    }

    private ExecutorService executorFor(StageKind configuredKind) {
        if (configuredKind == StageKind.INHERIT && globalMode == PipeConfig.ExecutorMode.CUSTOM) {
            return customExecutor;
        }
        StageKind effective = (configuredKind != StageKind.INHERIT)
                ? configuredKind
                : (globalMode == PipeConfig.ExecutorMode.VIRTUAL_THREADS
                        ? StageKind.IO_BOUND
                        : StageKind.CPU_BOUND);

        if (effective == StageKind.IO_BOUND) {
            ExecutorService exec = virtualExecutor;
            if (exec == null) {
                synchronized (lock) {
                    exec = virtualExecutor;
                    if (exec == null) {
                        exec = Executors.newThreadPerTaskExecutor(
                                Thread.ofVirtual().name("eazypipe-vt-", 0).factory());
                        virtualExecutor = exec;
                    }
                }
            }
            return exec;
        }

        ExecutorService exec = cpuPlatformExecutor;
        if (exec == null) {
            synchronized (lock) {
                exec = cpuPlatformExecutor;
                if (exec == null) {
                    exec = Executors.newThreadPerTaskExecutor(
                            Thread.ofPlatform().name("eazypipe-cpu-", 0).daemon(true).factory());
                    cpuPlatformExecutor = exec;
                }
            }
        }
        return exec;
    }

    /**
     * Initiates an orderly shutdown of any executors created by EazyPipe.
     * Caller-provided custom executors are never shut down.
     */
    void shutdown() {
        ExecutorService vt = virtualExecutor;
        if (vt != null) {
            vt.shutdown();
        }
        ExecutorService cpu = cpuPlatformExecutor;
        if (cpu != null) {
            cpu.shutdown();
        }
    }
}
