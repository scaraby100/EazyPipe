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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pipeline completion, graceful draining, cancellation, and fail-fast abort coordinator.
 */
final class LifecycleController<R> {

    private final PipelineRuntime<R> runtime;

    LifecycleController(PipelineRuntime<R> runtime) {
        this.runtime = runtime;
    }

    void closeSideOnMainEnd(int mainStageIndex, BoundedChannel<Object> sideChannel) {
        if (runtime.stageRemainingWorkers[mainStageIndex].decrementAndGet() == 0) {
            try {
                finishStage(mainStageIndex);
            } finally {
                sideChannel.close();
            }
        }
    }

    void finishAuxStage(String auxName, StageConfig auxConfig, AtomicInteger remaining) {
        if (remaining.decrementAndGet() == 0) {
            try {
                if (!runtime.terminated.get() && auxConfig.onComplete() != null) {
                    runtime.context.withLaunchContext(auxConfig.onComplete());
                }
            } catch (Throwable t) {
                fail(auxName, t);
            }
        }
    }

    void onWorkerEnded(WorkerTask task) {
        if (task.customStageEnd != null) {
            task.customStageEnd.accept(runtime);
        } else if (task.stageIndex >= 0) {
            if (runtime.stageRemainingWorkers[task.stageIndex].decrementAndGet() == 0) {
                finishStage(task.stageIndex);
            }
        }
        if (runtime.totalRemainingWorkers.decrementAndGet() == 0) {
            finalizeRuntime();
        }
    }

    @SuppressWarnings("unchecked")
    private void finishStage(int stageIndex) {
        StageDefinition stage = runtime.stages.get(stageIndex);
        String stageName = runtime.stageNames.get(stageIndex);
        try {
            if (!runtime.terminated.get()) {
                Runnable onComplete = stage.config().onComplete();
                if (onComplete != null) {
                    runtime.context.withLaunchContext(onComplete);
                }
                if (stage instanceof StageDefinition.SinkStage<?, ?> sinkStage) {
                    runtime.context.withLaunchContext(() -> {
                        R result = ((StageDefinition.SinkStage<?, R>) sinkStage).resultSupplier().get();
                        runtime.resultRef.set(result);
                    });
                }
            }
        } catch (Throwable t) {
            fail(stageName, t);
        } finally {
            if (stageIndex < runtime.channels.size()) {
                runtime.channels.get(stageIndex).close();
            }
        }
    }

    private void finalizeRuntime() {
        try {
            runtime.adaptiveController.stop();
            // Sample final stage snapshots once before completing.
            try {
                runtime.adaptiveController.tick();
            } catch (Throwable ignored) {
                // Best-effort final metrics refresh.
            }

            synchronized (runtime.completionLock) {
                runtime.endNanos.compareAndSet(-1L, System.nanoTime());
                Throwable failure = runtime.firstFailure.get();
                if (failure != null) {
                    runtime.completionFuture.completeExceptionally(failure);
                } else {
                    runtime.completionFuture.complete(runtime.resultRef.get());
                }
            }
            runtime.scheduler.shutdown();
            if (runtime.cpuPool != null) {
                runtime.cpuPool.shutdown();
            }
            try {
                runtime.metricsListener.onPipelineCompleted(runtime.metricsSnapshot());
            } catch (Throwable ignored) {
                // Listener exceptions must never block worker termination signaling.
            }
        } finally {
            runtime.allWorkersDone.countDown();
        }
    }

    void fail(String stageName, Throwable error) {
        Throwable wrapped = wrapFailure(stageName, error);
        boolean primarySet;
        synchronized (runtime.completionLock) {
            if (runtime.completionFuture.isDone()) {
                return;
            }
            primarySet = runtime.firstFailure.compareAndSet(null, wrapped);
            if (primarySet) {
                runtime.terminated.set(true);
            }
        }
        if (primarySet) {
            abortAllStructures(wrapped);
            cancelAllWorkers();
        } else {
            Throwable primary = runtime.firstFailure.get();
            if (primary != null
                    && primary != wrapped
                    && !(error instanceof PipeAbortedException)
                    && !(error instanceof PipeDrainingException)
                    && !(error instanceof CancellationException)) {
                synchronized (primary) {
                    primary.addSuppressed(wrapped);
                }
            }
        }
    }

    private static Throwable wrapFailure(String stageName, Throwable error) {
        if (error instanceof PipeException || error instanceof CancellationException) {
            return error;
        }
        if (error instanceof Error err) {
            err.addSuppressed(new PipeException(
                    stageName, "Stage '" + stageName + "' threw an Error: " + err, null));
            return err;
        }
        String message = error.getMessage();
        String detail = (message == null || message.isBlank())
                ? "Stage '" + stageName + "' failed with " + error.getClass().getSimpleName()
                : "Stage '" + stageName + "' failed: " + message;
        return new PipeException(stageName, detail, error);
    }

    private void abortAllStructures(Throwable cause) {
        for (BoundedChannel<Object> channel : runtime.allChannels) {
            channel.abort(cause);
        }
        for (DynamicPermitPool pool : runtime.stagePermitPools) {
            pool.abort(cause);
        }
        for (OrderedReorderBuffer buffer : runtime.reorderBuffers) {
            buffer.abort(cause);
        }
        for (TokenBucketRateLimiter limiter : runtime.rateLimiters) {
            limiter.abort(cause);
        }
    }

    private void cancelAllWorkers() {
        List<WorkerTask> snapshot;
        synchronized (runtime.workersLock) {
            snapshot = new ArrayList<>(runtime.submittedTasks);
        }
        for (WorkerTask task : snapshot) {
            cancelTask(task);
        }
    }

    private void cancelTask(WorkerTask task) {
        if (task.state.compareAndSet(WorkerTask.STATE_PENDING, WorkerTask.STATE_DONE)) {
            Future<?> f = task.future;
            if (f != null) {
                f.cancel(true);
            }
            task.finishOnCancel();
        } else if (task.state.get() == WorkerTask.STATE_RUNNING) {
            Future<?> f = task.future;
            if (f != null) {
                f.cancel(true);
            }
        }
    }

    void requestDrain() {
        if (runtime.completionFuture.isDone() || runtime.terminated.get()) {
            return;
        }
        if (runtime.draining.compareAndSet(false, true)) {
            if (!runtime.channels.isEmpty()) {
                runtime.channels.get(0).beginDrain();
            }
            List<WorkerTask> sourceSnapshot;
            synchronized (runtime.workersLock) {
                sourceSnapshot = new ArrayList<>(runtime.sourceTasks);
            }
            for (WorkerTask srcTask : sourceSnapshot) {
                cancelTask(srcTask);
            }
        }
    }

    void cancel() {
        CancellationException cancelEx;
        boolean newlyCancelled;
        synchronized (runtime.completionLock) {
            if (runtime.completionFuture.isDone()) {
                return;
            }
            newlyCancelled = runtime.terminated.compareAndSet(false, true);
            if (newlyCancelled) {
                runtime.cancelled.set(true);
                cancelEx = new CancellationException("Pipeline execution was cancelled");
                runtime.firstFailure.compareAndSet(null, cancelEx);
            } else {
                cancelEx = null;
            }
        }
        if (newlyCancelled) {
            abortAllStructures(cancelEx);
        }
        cancelAllWorkers();
    }
}
