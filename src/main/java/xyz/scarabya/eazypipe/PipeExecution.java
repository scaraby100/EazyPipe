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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import xyz.scarabya.eazypipe.metrics.PipelineMetricsSnapshot;

/**
 * Handle representing a running or completed asynchronous {@link Pipeline} execution.
 *
 * <p>Implements {@link AutoCloseable}: closing an active execution cancels remaining work and waits
 * for all stage workers to exit cleanly. To stop an open/infinite source gracefully while letting
 * already-emitted items finish draining downstream, call {@link #drain()} or {@link #requestDrain()}.
 *
 * @param <R> the terminal result type ({@link Void} for {@code start(...)} runs)
 */
public final class PipeExecution<R> implements AutoCloseable {

    private final PipelineRuntime<R> runtime;

    PipeExecution(PipelineRuntime<R> runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
    }

    /**
     * Returns a defensive {@link CompletableFuture} view of this pipeline execution.
     * Completing the returned copy externally does not affect the running pipeline.
     *
     * @return a {@link CompletableFuture} completed when the pipeline finishes, fails, or is cancelled
     */
    public CompletableFuture<R> future() {
        return runtime.future();
    }

    /**
     * Waits for the pipeline to complete and returns its terminal result.
     *
     * <p>Any {@link Error} thrown by a stage is rethrown as-is (with stage context attached as a
     * suppressed {@link PipeException}).
     *
     * @return the terminal result (or {@code null} for {@code Void} runs)
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws PipeException if any pipeline stage failed
     * @throws CancellationException if the execution was cancelled
     */
    public R await() throws InterruptedException {
        try {
            return runtime.rawFuture().get();
        } catch (ExecutionException ex) {
            throw propagateFailure(ex.getCause());
        }
    }

    /**
     * Waits at most the given duration for the pipeline to complete and returns its terminal result.
     *
     * <p>Any {@link Error} thrown by a stage is rethrown as-is.
     *
     * @param timeout maximum time to wait (must not be {@code null})
     * @return the terminal result (or {@code null} for {@code Void} runs)
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws TimeoutException if the wait timed out
     * @throws PipeException if any pipeline stage failed
     * @throws CancellationException if the execution was cancelled
     */
    public R await(Duration timeout) throws InterruptedException, TimeoutException {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative: " + timeout);
        }
        try {
            return runtime.rawFuture().get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException ex) {
            throw propagateFailure(ex.getCause());
        }
    }

    /**
     * Waits for completion (wrapping {@link InterruptedException} into {@link PipeException}
     * while preserving the thread's interrupt flag) and returns the terminal result.
     *
     * <p>Any {@link Error} thrown by a stage is rethrown as-is.
     *
     * @return the terminal result (or {@code null} for {@code Void} runs)
     * @throws PipeException if any pipeline stage failed or the calling thread was interrupted
     * @throws CancellationException if the execution was cancelled
     */
    public R join() {
        try {
            return await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new PipeException("Interrupted while waiting for pipeline completion", ex);
        }
    }

    /**
     * Signals source stage(s) to stop producing cleanly without aborting downstream stages.
     * Already-emitted items continue draining through the remainder of the pipeline.
     */
    public void requestDrain() {
        runtime.requestDrain();
    }

    /**
     * Requests graceful drain via {@link #requestDrain()} and waits for all downstream stages
     * to finish processing in-flight items.
     *
     * @return the terminal result after draining completes
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws PipeException if a downstream stage fails while draining
     * @throws CancellationException if {@link #cancel()} is invoked concurrently
     */
    public R drain() throws InterruptedException {
        requestDrain();
        return await();
    }

    /**
     * Requests immediate cancellation of this pipeline execution, aborting channels and interrupting active workers.
     * Safe to invoke multiple times or after completion.
     */
    public void cancel() {
        runtime.cancel();
    }

    /**
     * Returns whether the pipeline has finished (normally, exceptionally, or by cancellation).
     *
     * @return {@code true} if finished
     */
    public boolean isDone() {
        return runtime.isDone();
    }

    /**
     * Returns whether this execution was cancelled via {@link #cancel()}.
     *
     * @return {@code true} if cancelled
     */
    public boolean isCancelled() {
        return runtime.isCancelled();
    }

    /**
     * Returns whether this execution terminated due to a failure (excluding cancellation).
     *
     * @return {@code true} if failed
     */
    public boolean isFailed() {
        return runtime.isFailed();
    }

    /**
     * Returns execution statistics collected so far (or final metrics once completed).
     *
     * @return a snapshot {@link PipeStats}
     */
    public PipeStats stats() {
        return runtime.stats();
    }

    /**
     * Returns detailed live or final observability metrics including per-stage telemetry
     * and recorded adaptive {@link xyz.scarabya.eazypipe.adaptive.ScalingDecision}s.
     *
     * @return a {@link PipelineMetricsSnapshot}
     */
    public PipelineMetricsSnapshot metricsSnapshot() {
        return runtime.metricsSnapshot();
    }

    /**
     * Cancels the pipeline if still running and waits until all stage workers have exited.
     */
    @Override
    public void close() {
        if (!isDone()) {
            cancel();
        }
        runtime.awaitTerminationUninterruptibly();
    }

    private static RuntimeException propagateFailure(Throwable cause) {
        if (cause instanceof PipeException pe) {
            return pe;
        }
        if (cause instanceof CancellationException ce) {
            return ce;
        }
        if (cause instanceof Error err) {
            throw err;
        }
        return new PipeException("Pipeline execution failed", cause);
    }
}
