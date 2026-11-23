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

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Shared fixed-size platform-thread worker pool enforcing {@link PipeConfig#cpuBudget()}
 * across all {@link StageKind#CPU_BOUND} stages without starving virtual-thread carriers.
 */
final class CpuWorkerPool {

    private final ThreadPoolExecutor executor;

    CpuWorkerPool(int cpuBudget) {
        if (cpuBudget < 1) {
            throw new IllegalArgumentException("cpuBudget must be >= 1, got " + cpuBudget);
        }
        this.executor = new ThreadPoolExecutor(
                cpuBudget,
                cpuBudget,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                Thread.ofPlatform().name("eazypipe-cpu-", 0).daemon(true).factory());
    }

    /**
     * Executes {@code action} on the shared CPU pool, returning queue wait time in nanoseconds.
     */
    long execute(ThrowingRunnable action) throws Exception {
        Objects.requireNonNull(action, "action must not be null");
        long submitNanos = System.nanoTime();
        Future<Long> future = executor.submit((Callable<Long>) () -> {
            long queueWaitNanos = Math.max(0L, System.nanoTime() - submitNanos);
            action.run();
            return queueWaitNanos;
        });
        try {
            return future.get();
        } catch (InterruptedException ex) {
            future.cancel(true);
            throw ex;
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception e) {
                throw e;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(cause);
        }
    }

    void shutdown() {
        executor.shutdown();
    }
}
