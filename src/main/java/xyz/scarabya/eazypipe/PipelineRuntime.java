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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import xyz.scarabya.eazypipe.adaptive.AdaptiveController;
import xyz.scarabya.eazypipe.adaptive.StageTelemetry;
import xyz.scarabya.eazypipe.metrics.PipelineMetricsListener;
import xyz.scarabya.eazypipe.metrics.PipelineMetricsSnapshot;

/**
 * Shared execution state hub coordinating runtime channels, telemetry, permits, lifecycle, and metrics.
 */
final class PipelineRuntime<R> {

    final PipeConfig config;
    final List<StageDefinition> stages;
    final List<String> stageNames;
    final List<BoundedChannel<Object>> channels;
    final List<BoundedChannel<Object>> allChannels;
    final List<DynamicPermitPool> stagePermitPools;
    final List<StageTelemetry> stageTelemetries;
    final List<OrderedReorderBuffer> reorderBuffers;
    final List<TokenBucketRateLimiter> rateLimiters;
    final StageScheduler scheduler;
    final CpuWorkerPool cpuPool;
    final PipelineMetricsListener metricsListener;
    final AdaptiveController adaptiveController;
    final ContextSupport context;
    final StageExecutionPolicy executionPolicy;
    final LifecycleController<R> lifecycle;

    final AtomicInteger[] stageRemainingWorkers;
    final AtomicInteger totalRemainingWorkers;
    final List<WorkerTask> submittedTasks = new ArrayList<>();
    final List<WorkerTask> sourceTasks = new ArrayList<>();
    final Object workersLock = new Object();
    final Object completionLock = new Object();

    final AtomicBoolean terminated = new AtomicBoolean(false);
    final AtomicBoolean draining = new AtomicBoolean(false);
    final AtomicBoolean cancelled = new AtomicBoolean(false);
    final AtomicReference<Throwable> firstFailure = new AtomicReference<>();
    final AtomicReference<R> resultRef = new AtomicReference<>();
    final CompletableFuture<R> completionFuture = new CompletableFuture<>();
    final CountDownLatch allWorkersDone = new CountDownLatch(1);

    final AtomicLong itemsProduced = new AtomicLong();
    final AtomicLong itemsConsumed = new AtomicLong();
    final AtomicLong errorsSkipped = new AtomicLong();
    final long startNanos = System.nanoTime();
    final AtomicLong endNanos = new AtomicLong(-1L);

    PipelineRuntime(
            PipeConfig config,
            List<StageDefinition> stages,
            List<String> stageNames,
            List<BoundedChannel<Object>> channels,
            List<BoundedChannel<Object>> allChannels,
            List<DynamicPermitPool> stagePermitPools,
            List<StageTelemetry> stageTelemetries,
            List<OrderedReorderBuffer> reorderBuffers,
            List<TokenBucketRateLimiter> rateLimiters,
            StageScheduler scheduler,
            CpuWorkerPool cpuPool,
            ContextPropagator contextPropagator,
            ContextPropagator.ContextSnapshot launchContext,
            PipelineMetricsListener metricsListener,
            AdaptiveController adaptiveController,
            AtomicInteger[] stageRemainingWorkers,
            int totalWorkers) {
        this.config = config;
        this.stages = stages;
        this.stageNames = stageNames;
        this.channels = channels;
        this.allChannels = allChannels;
        this.stagePermitPools = stagePermitPools;
        this.stageTelemetries = stageTelemetries;
        this.reorderBuffers = reorderBuffers;
        this.rateLimiters = rateLimiters;
        this.scheduler = scheduler;
        this.cpuPool = cpuPool;
        this.metricsListener = metricsListener;
        this.adaptiveController = adaptiveController;
        this.context = new ContextSupport(contextPropagator, launchContext);
        this.executionPolicy = new StageExecutionPolicy(config, cpuPool, metricsListener, errorsSkipped);
        this.lifecycle = new LifecycleController<>(this);
        this.stageRemainingWorkers = stageRemainingWorkers;
        this.totalRemainingWorkers = new AtomicInteger(totalWorkers);
    }

    static <R> PipeExecution<R> launch(
            PipeConfig config,
            StageDefinition.SourceStage<?> sourceStage,
            List<StageDefinition> intermediateStages,
            StageDefinition terminalStage) {
        return PipelineOrchestrator.launch(config, sourceStage, intermediateStages, terminalStage);
    }

    void requestDrain() {
        lifecycle.requestDrain();
    }

    void cancel() {
        lifecycle.cancel();
    }

    CompletableFuture<R> future() {
        return completionFuture.copy();
    }

    CompletableFuture<R> rawFuture() {
        return completionFuture;
    }

    boolean isDone() {
        return completionFuture.isDone();
    }

    boolean isCancelled() {
        return cancelled.get();
    }

    boolean isFailed() {
        return completionFuture.isCompletedExceptionally() && !cancelled.get();
    }

    PipeStats stats() {
        return new PipeStats(
                currentElapsed(),
                itemsProduced.get(),
                itemsConsumed.get(),
                errorsSkipped.get(),
                stages.size());
    }

    PipelineMetricsSnapshot metricsSnapshot() {
        long dropped = 0L;
        for (BoundedChannel<Object> channel : allChannels) {
            dropped += channel.droppedCount();
        }
        return new PipelineMetricsSnapshot(
                currentElapsed(),
                itemsProduced.get(),
                itemsConsumed.get(),
                errorsSkipped.get(),
                dropped,
                adaptiveController.currentStageSnapshots(),
                adaptiveController.recentDecisions());
    }

    private Duration currentElapsed() {
        long end = endNanos.get();
        long elapsedNanos = (end >= 0L ? end : System.nanoTime()) - startNanos;
        return Duration.ofNanos(Math.max(0L, elapsedNanos));
    }

    void awaitTerminationUninterruptibly() {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    allWorkersDone.await();
                    break;
                } catch (InterruptedException ex) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
