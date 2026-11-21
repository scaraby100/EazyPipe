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
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import xyz.scarabya.eazypipe.adaptive.AdaptiveController;
import xyz.scarabya.eazypipe.adaptive.StageTelemetry;
import xyz.scarabya.eazypipe.metrics.PipelineMetricsListener;

/**
 * Topology builder and startup orchestrator for {@link PipelineRuntime}.
 */
final class PipelineOrchestrator {

    private PipelineOrchestrator() {
    }

    static <R> PipeExecution<R> launch(
            PipeConfig config,
            StageDefinition.SourceStage<?> sourceStage,
            List<StageDefinition> intermediateStages,
            StageDefinition terminalStage) {

        List<StageDefinition> stages = new ArrayList<>(2 + intermediateStages.size());
        stages.add(sourceStage);
        stages.addAll(intermediateStages);
        stages.add(terminalStage);

        List<String> stageNames = new ArrayList<>(stages.size());
        for (int i = 0; i < stages.size(); i++) {
            stageNames.add(resolveDefaultStageName(i, stages.get(i)));
        }

        List<BoundedChannel<Object>> mainChannels = new ArrayList<>(stages.size() - 1);
        List<BoundedChannel<Object>> allChannels = new ArrayList<>();
        for (int i = 0; i < stages.size() - 1; i++) {
            StageConfig producerConfig = stages.get(i).config();
            StageConfig consumerConfig = stages.get(i + 1).config();
            int capacity = consumerConfig.bufferCapacity() > 0
                    ? consumerConfig.bufferCapacity()
                    : producerConfig.resolveBufferCapacity(config.defaultBufferCapacity());
            BoundedChannel<Object> ch = new BoundedChannel<>(capacity, consumerConfig.overflowPolicy());
            mainChannels.add(ch);
            allChannels.add(ch);
        }

        List<DynamicPermitPool> permitPools = new ArrayList<>(stages.size());
        List<StageTelemetry> telemetries = new ArrayList<>(stages.size());
        for (int i = 0; i < stages.size(); i++) {
            StageConfig sc = stages.get(i).config();
            permitPools.add(new DynamicPermitPool(
                    sc.minConcurrency(), sc.initialConcurrency(), sc.maxConcurrency()));
            telemetries.add(new StageTelemetry());
        }

        List<AdaptiveController.ManagedStage> managedStages = buildMainManagedStages(
                config, stages, stageNames, mainChannels, permitPools, telemetries);

        CpuWorkerPool cpuPool = needsCpuPool(stages, config.executorMode())
                ? new CpuWorkerPool(config.cpuBudget())
                : null;
        ContextPropagator propagator = config.contextPropagator();
        ContextPropagator.ContextSnapshot launchCtx = propagator != null ? propagator.capture() : null;
        StageScheduler scheduler = new StageScheduler(config);

        List<OrderedReorderBuffer> reorderBuffers = new ArrayList<>();
        List<TokenBucketRateLimiter> rateLimiters = new ArrayList<>();
        AtomicInteger[] stageRemaining = new AtomicInteger[stages.size()];
        List<WorkerTask> plannedTasks = new ArrayList<>();
        List<AdaptiveController.ManagedStage> auxManagedStages = new ArrayList<>();

        for (int i = 0; i < stages.size(); i++) {
            StageTaskPlanner.planStage(
                    i,
                    stages.get(i),
                    stageNames.get(i),
                    config,
                    allChannels,
                    reorderBuffers,
                    rateLimiters,
                    stageRemaining,
                    plannedTasks,
                    auxManagedStages);
        }

        managedStages.addAll(auxManagedStages);
        PipelineMetricsListener listener = config.metricsListener() != null
                ? config.metricsListener()
                : PipelineMetricsListener.noop();
        AdaptiveController adaptiveController = new AdaptiveController(
                config.adaptiveConfig(), config.cpuBudget(), managedStages, listener);

        PipelineRuntime<R> runtime = new PipelineRuntime<>(
                config,
                List.copyOf(stages),
                List.copyOf(stageNames),
                List.copyOf(mainChannels),
                List.copyOf(allChannels),
                List.copyOf(permitPools),
                List.copyOf(telemetries),
                List.copyOf(reorderBuffers),
                List.copyOf(rateLimiters),
                scheduler,
                cpuPool,
                propagator,
                launchCtx,
                listener,
                adaptiveController,
                stageRemaining,
                plannedTasks.size());
        bindAndStart(runtime, plannedTasks);
        return new PipeExecution<>(runtime);
    }

    private static List<AdaptiveController.ManagedStage> buildMainManagedStages(
            PipeConfig config,
            List<StageDefinition> stages,
            List<String> stageNames,
            List<BoundedChannel<Object>> mainChannels,
            List<DynamicPermitPool> permitPools,
            List<StageTelemetry> telemetries) {
        List<AdaptiveController.ManagedStage> managedStages = new ArrayList<>(stages.size());
        for (int i = 0; i < stages.size(); i++) {
            StageConfig sc = stages.get(i).config();
            boolean hasInput = i > 0;
            boolean hasOutput = i < mainChannels.size();
            BoundedChannel<Object> inCh = hasInput ? mainChannels.get(i - 1) : null;
            BoundedChannel<Object> outCh = hasOutput ? mainChannels.get(i) : null;
            DynamicPermitPool pool = permitPools.get(i);
            StageKind effectiveKind = sc.resolveEffectiveKind(config.executorMode());

            managedStages.add(new AdaptiveController.ManagedStage(
                    i,
                    true,
                    stageNames.get(i),
                    effectiveKind,
                    sc.minConcurrency(),
                    sc.maxConcurrency(),
                    telemetries.get(i),
                    hasInput,
                    hasOutput,
                    hasInput ? inCh::fillRatio : () -> 0.0,
                    hasOutput ? outCh::fillRatio : () -> 0.0,
                    hasInput ? inCh::receiveWaitNanos : () -> 0L,
                    hasOutput ? outCh::sendWaitNanos : () -> 0L,
                    hasInput ? inCh::droppedCount : () -> 0L,
                    pool::targetPermits,
                    hasInput ? pool::setTarget : ignored -> { }));
        }
        return managedStages;
    }

    private static boolean needsCpuPool(List<StageDefinition> stages, PipeConfig.ExecutorMode mode) {
        for (int i = 1; i < stages.size(); i++) {
            StageDefinition st = stages.get(i);
            if (StageExecutionPolicy.isCpuRouted(st.config(), mode)) {
                return true;
            }
            if (st instanceof StageDefinition.TeeStage<?> tee
                    && StageExecutionPolicy.isCpuRouted(tee.sideConfig(), mode)) {
                return true;
            }
        }
        return false;
    }

    private static void bindAndStart(PipelineRuntime<?> runtime, List<WorkerTask> tasks) {
        warnIfCustomPoolUndersized(runtime.config, tasks);
        if (runtime.config.adaptiveConfig().enabled()) {
            runtime.adaptiveController.start();
        }
        for (WorkerTask task : tasks) {
            task.bind(runtime);
            if (task.isSource) {
                synchronized (runtime.workersLock) {
                    runtime.sourceTasks.add(task);
                }
            }
        }
        for (WorkerTask task : tasks) {
            if (runtime.terminated.get()) {
                if (task.state.compareAndSet(WorkerTask.STATE_PENDING, WorkerTask.STATE_DONE)) {
                    task.finishOnCancel();
                }
                continue;
            }
            try {
                Future<?> future = runtime.scheduler.submit(task.stageKind, task.isSource, task);
                boolean shouldCancelNow;
                synchronized (runtime.workersLock) {
                    task.future = future;
                    runtime.submittedTasks.add(task);
                    shouldCancelNow = runtime.terminated.get()
                            || (task.isSource && runtime.draining.get());
                }
                if (shouldCancelNow) {
                    if (task.state.compareAndSet(WorkerTask.STATE_PENDING, WorkerTask.STATE_DONE)) {
                        future.cancel(true);
                        task.finishOnCancel();
                    } else if (task.state.get() == WorkerTask.STATE_RUNNING) {
                        future.cancel(true);
                    }
                }
            } catch (RejectedExecutionException rex) {
                runtime.lifecycle.fail(task.stageName, rex);
                if (task.state.compareAndSet(WorkerTask.STATE_PENDING, WorkerTask.STATE_DONE)) {
                    task.finishOnCancel();
                }
            }
        }
    }

    private static void warnIfCustomPoolUndersized(PipeConfig config, List<WorkerTask> tasks) {
        if (config.executorMode() != PipeConfig.ExecutorMode.CUSTOM
                || !(config.customExecutor() instanceof ThreadPoolExecutor tpe)) {
            return;
        }
        int customTasks = 0;
        for (WorkerTask task : tasks) {
            if (task.stageKind == StageKind.INHERIT) {
                customTasks++;
            }
        }
        if (tpe.getMaximumPoolSize() < customTasks) {
            int required = customTasks;
            int maxPool = tpe.getMaximumPoolSize();
            System.getLogger("xyz.scarabya.eazypipe").log(
                    System.Logger.Level.WARNING,
                    () -> "Custom ThreadPoolExecutor maximumPoolSize (" + maxPool
                            + ") is smaller than the " + required
                            + " concurrent INHERIT worker tasks; at least " + required
                            + " threads are required to avoid starvation deadlock.");
        }
    }

    private static String resolveDefaultStageName(int stageIndex, StageDefinition stage) {
        String defaultName = switch (stage) {
            case StageDefinition.SourceStage<?> ignored -> "source";
            case StageDefinition.ProcessorStage<?, ?> ignored -> "stage-" + stageIndex;
            case StageDefinition.OrderedMapStage<?, ?> ignored -> "ordered-map-" + stageIndex;
            case StageDefinition.BatchStage<?> ignored -> "batch-" + stageIndex;
            case StageDefinition.RateLimitStage<?> ignored -> "rate-limit-" + stageIndex;
            case StageDefinition.TeeStage<?> ignored -> "tee-" + stageIndex;
            case StageDefinition.SinkStage<?, ?> ignored -> "sink";
            case StageDefinition.BroadcastSinkStage<?> ignored -> "broadcast-sink";
            case StageDefinition.RouteSinkStage<?, ?> ignored -> "route-sink";
        };
        return stage.config().resolveName(defaultName);
    }
}
