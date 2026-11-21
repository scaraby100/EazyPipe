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
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import xyz.scarabya.eazypipe.adaptive.AdaptiveController;
import xyz.scarabya.eazypipe.adaptive.StageTelemetry;
import xyz.scarabya.eazypipe.metrics.PipelineMetricsListener;
import xyz.scarabya.eazypipe.metrics.PipelineMetricsSnapshot;

/**
 * Internal execution coordinator managing hybrid stage scheduling, dynamic concurrency permits,
 * adaptive throttling, retry/dead-letter handling, context propagation, graceful draining, and shutdown.
 */
final class PipelineRuntime<R> {

    private static final int STATE_PENDING = 0;
    private static final int STATE_RUNNING = 1;
    private static final int STATE_DONE = 2;

    private static final ContextPropagator.Scope NOOP_SCOPE = () -> { };

    private record ContextEnvelope(Object payload, ContextPropagator.ContextSnapshot snapshot) { }

    @FunctionalInterface
    private interface RuntimeTaskBody {
        void run(PipelineRuntime<?> runtime) throws Exception;
    }

    @FunctionalInterface
    private interface EmitAction {
        void emit(Object wrappedItem) throws InterruptedException;
    }

    private enum ItemOutcome {
        SUCCESS,
        SKIPPED
    }

    private final PipeConfig config;
    private final List<StageDefinition> stages;
    private final List<String> stageNames;
    private final List<BoundedChannel<Object>> channels;
    private final List<BoundedChannel<Object>> allChannels;
    private final List<DynamicPermitPool> stagePermitPools;
    private final List<StageTelemetry> stageTelemetries;
    private final List<OrderedReorderBuffer> reorderBuffers;
    private final List<TokenBucketRateLimiter> rateLimiters;
    private final StageScheduler scheduler;
    private final CpuWorkerPool cpuPool;
    private final ContextPropagator contextPropagator;
    private final ContextPropagator.ContextSnapshot launchContext;
    private final PipelineMetricsListener metricsListener;
    private final AdaptiveController adaptiveController;

    private final AtomicInteger[] stageRemainingWorkers;
    private final AtomicInteger totalRemainingWorkers;
    private final List<WorkerTask> submittedTasks = new ArrayList<>();
    private final List<WorkerTask> sourceTasks = new ArrayList<>();
    private final Object workersLock = new Object();
    private final Object completionLock = new Object();

    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<Throwable> firstFailure = new AtomicReference<>();
    private final AtomicReference<R> resultRef = new AtomicReference<>();
    private final CompletableFuture<R> completionFuture = new CompletableFuture<>();
    private final CountDownLatch allWorkersDone = new CountDownLatch(1);

    private final AtomicLong itemsProduced = new AtomicLong();
    private final AtomicLong itemsConsumed = new AtomicLong();
    private final AtomicLong errorsSkipped = new AtomicLong();
    private final long startNanos = System.nanoTime();
    private final AtomicLong endNanos = new AtomicLong(-1L);

    private PipelineRuntime(
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
        this.contextPropagator = contextPropagator;
        this.launchContext = launchContext;
        this.metricsListener = metricsListener;
        this.adaptiveController = adaptiveController;
        this.stageRemainingWorkers = stageRemainingWorkers;
        this.totalRemainingWorkers = new AtomicInteger(totalWorkers);
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
        List<OrderedReorderBuffer> reorderBuffers = new ArrayList<>();
        List<TokenBucketRateLimiter> rateLimiters = new ArrayList<>();

        for (int i = 0; i < stages.size(); i++) {
            StageConfig sc = stages.get(i).config();
            permitPools.add(new DynamicPermitPool(
                    sc.minConcurrency(),
                    sc.initialConcurrency(),
                    sc.maxConcurrency()));
            telemetries.add(new StageTelemetry());
        }

        // Prepare adaptive stage descriptors for intermediate + terminal stages (and source telemetry).
        List<AdaptiveController.ManagedStage> managedStages = new ArrayList<>(stages.size());
        for (int i = 0; i < stages.size(); i++) {
            StageDefinition stage = stages.get(i);
            StageConfig sc = stage.config();
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

        PipelineMetricsListener listener = config.metricsListener() != null
                ? config.metricsListener()
                : PipelineMetricsListener.noop();

        boolean needsCpuPool = false;
        for (int i = 1; i < stages.size(); i++) {
            StageDefinition st = stages.get(i);
            if (isCpuRouted(st.config(), config.executorMode())) {
                needsCpuPool = true;
                break;
            }
            if (st instanceof StageDefinition.TeeStage<?> tee
                    && isCpuRouted(tee.sideConfig(), config.executorMode())) {
                needsCpuPool = true;
                break;
            }
        }
        CpuWorkerPool cpuPool = needsCpuPool ? new CpuWorkerPool(config.cpuBudget()) : null;

        ContextPropagator propagator = config.contextPropagator();
        ContextPropagator.ContextSnapshot launchCtx = propagator != null ? propagator.capture() : null;
        StageScheduler scheduler = new StageScheduler(config);

        AtomicInteger[] stageRemaining = new AtomicInteger[stages.size()];
        List<WorkerTask> plannedTasks = new ArrayList<>();
        List<AdaptiveController.ManagedStage> auxManagedStages = new ArrayList<>();

        // Build all worker tasks before submitting so totalRemainingWorkers is exact upfront.
        for (int i = 0; i < stages.size(); i++) {
            StageDefinition stage = stages.get(i);
            String sName = stageNames.get(i);
            StageKind sKind = stage.config().kind();

            switch (stage) {
                case StageDefinition.SourceStage<?> srcStage -> {
                    int sourceCount = srcStage.sources().size();
                    int workers = sourceCount > 1 ? sourceCount : srcStage.config().initialConcurrency();
                    stageRemaining[i] = new AtomicInteger(workers);
                    for (int w = 0; w < workers; w++) {
                        int sourceIndex = sourceCount > 1 ? w : 0;
                        int stageIdx = i;
                        plannedTasks.add(new WorkerTask(
                                stageIdx,
                                sName,
                                sKind,
                                true,
                                rt -> rt.runSourceWorker(
                                        stageIdx,
                                        sName,
                                        srcStage.sources().get(sourceIndex))));
                    }
                }
                case StageDefinition.ProcessorStage<?, ?> procStage -> {
                    int maxWorkers = procStage.config().maxConcurrency();
                    stageRemaining[i] = new AtomicInteger(maxWorkers);
                    int stageIdx = i;
                    for (int w = 0; w < maxWorkers; w++) {
                        plannedTasks.add(new WorkerTask(
                                stageIdx,
                                sName,
                                sKind,
                                false,
                                rt -> rt.runProcessorWorker(stageIdx, sName, procStage)));
                    }
                }
                case StageDefinition.OrderedMapStage<?, ?> ordStage -> {
                    int maxWorkers = ordStage.config().maxConcurrency();
                    stageRemaining[i] = new AtomicInteger(maxWorkers);
                    OrderedReorderBuffer reorderBuffer =
                            new OrderedReorderBuffer(Math.max(64, maxWorkers * 4));
                    reorderBuffers.add(reorderBuffer);
                    int stageIdx = i;
                    for (int w = 0; w < maxWorkers; w++) {
                        plannedTasks.add(new WorkerTask(
                                stageIdx,
                                sName,
                                sKind,
                                false,
                                rt -> rt.runOrderedMapWorker(stageIdx, sName, ordStage, reorderBuffer)));
                    }
                }
                case StageDefinition.BatchStage<?> batchStage -> {
                    int maxWorkers = batchStage.config().maxConcurrency();
                    stageRemaining[i] = new AtomicInteger(maxWorkers);
                    int stageIdx = i;
                    for (int w = 0; w < maxWorkers; w++) {
                        plannedTasks.add(new WorkerTask(
                                stageIdx,
                                sName,
                                sKind,
                                false,
                                rt -> rt.runBatchWorker(stageIdx, sName, batchStage)));
                    }
                }
                case StageDefinition.RateLimitStage<?> rlStage -> {
                    int maxWorkers = rlStage.config().maxConcurrency();
                    stageRemaining[i] = new AtomicInteger(maxWorkers);
                    TokenBucketRateLimiter limiter =
                            new TokenBucketRateLimiter(rlStage.permitsPerSecond(), rlStage.burst());
                    rateLimiters.add(limiter);
                    int stageIdx = i;
                    for (int w = 0; w < maxWorkers; w++) {
                        plannedTasks.add(new WorkerTask(
                                stageIdx,
                                sName,
                                sKind,
                                false,
                                rt -> rt.runRateLimitWorker(stageIdx, sName, rlStage, limiter)));
                    }
                }
                case StageDefinition.TeeStage<?> teeStage -> {
                    int mainWorkers = teeStage.config().maxConcurrency();
                    stageRemaining[i] = new AtomicInteger(mainWorkers);
                    StageConfig sideCfg = teeStage.sideConfig();
                    int sideCap = sideCfg.resolveBufferCapacity(config.defaultBufferCapacity());
                    BoundedChannel<Object> sideChannel =
                            new BoundedChannel<>(sideCap, sideCfg.overflowPolicy());
                    allChannels.add(sideChannel);

                    int sideWorkers = sideCfg.maxConcurrency();
                    AtomicInteger sideRemaining = new AtomicInteger(sideWorkers);
                    String sideName = sideCfg.resolveName(sName + "-tee");
                    StageTelemetry sideTelemetry = new StageTelemetry();
                    auxManagedStages.add(new AdaptiveController.ManagedStage(
                            -1,
                            false,
                            sideName,
                            sideCfg.resolveEffectiveKind(config.executorMode()),
                            sideWorkers,
                            sideWorkers,
                            sideTelemetry,
                            true,
                            false,
                            sideChannel::fillRatio,
                            () -> 0.0,
                            sideChannel::receiveWaitNanos,
                            () -> 0L,
                            sideChannel::droppedCount,
                            () -> sideWorkers,
                            ignored -> { }));
                    int stageIdx = i;

                    for (int w = 0; w < mainWorkers; w++) {
                        plannedTasks.add(new WorkerTask(
                                stageIdx,
                                sName,
                                sKind,
                                false,
                                rt -> rt.runTeeMainWorker(stageIdx, sName, teeStage, sideChannel),
                                rt -> rt.closeSideOnMainEnd(stageIdx, sideChannel)));
                    }
                    for (int sw = 0; sw < sideWorkers; sw++) {
                        plannedTasks.add(new WorkerTask(
                                -1,
                                sideName,
                                sideCfg.kind(),
                                false,
                                rt -> rt.runAuxSinkWorker(
                                        sideName, sideCfg, teeStage.sideSink(), sideChannel, sideTelemetry),
                                rt -> rt.finishAuxStage(sideName, sideCfg, sideRemaining)));
                    }
                }
                case StageDefinition.SinkStage<?, ?> sinkStage -> {
                    int maxWorkers = sinkStage.config().maxConcurrency();
                    stageRemaining[i] = new AtomicInteger(maxWorkers);
                    int stageIdx = i;
                    for (int w = 0; w < maxWorkers; w++) {
                        plannedTasks.add(new WorkerTask(
                                stageIdx,
                                sName,
                                sKind,
                                false,
                                rt -> rt.runSinkWorker(stageIdx, sName, sinkStage)));
                    }
                }
                case StageDefinition.BroadcastSinkStage<?> broadcastStage -> {
                    int dispatchers = 1;
                    stageRemaining[i] = new AtomicInteger(dispatchers);
                    int sinkCount = broadcastStage.sinks().size();
                    List<BoundedChannel<Object>> branchChannels = new ArrayList<>(sinkCount);
                    int branchCap = broadcastStage.config().resolveBufferCapacity(config.defaultBufferCapacity());
                    for (int b = 0; b < sinkCount; b++) {
                        BoundedChannel<Object> bCh = new BoundedChannel<>(
                                branchCap, broadcastStage.config().overflowPolicy());
                        branchChannels.add(bCh);
                        allChannels.add(bCh);
                    }
                    int branchWorkersPerSink = broadcastStage.config().maxConcurrency();
                    AtomicInteger totalBranchWorkers = new AtomicInteger(sinkCount * branchWorkersPerSink);
                    List<String> branchNames = new ArrayList<>(sinkCount);
                    List<StageTelemetry> branchTelemetries = new ArrayList<>(sinkCount);
                    StageKind branchEffectiveKind = broadcastStage.config().resolveEffectiveKind(config.executorMode());
                    for (int b = 0; b < sinkCount; b++) {
                        String branchName = sName + "-branch-" + b;
                        branchNames.add(branchName);
                        StageTelemetry branchTelemetry = new StageTelemetry();
                        branchTelemetries.add(branchTelemetry);
                        BoundedChannel<Object> bCh = branchChannels.get(b);
                        auxManagedStages.add(new AdaptiveController.ManagedStage(
                                -1,
                                false,
                                branchName,
                                branchEffectiveKind,
                                branchWorkersPerSink,
                                branchWorkersPerSink,
                                branchTelemetry,
                                true,
                                false,
                                bCh::fillRatio,
                                () -> 0.0,
                                bCh::receiveWaitNanos,
                                () -> 0L,
                                bCh::droppedCount,
                                () -> branchWorkersPerSink,
                                ignored -> { }));
                    }
                    int stageIdx = i;

                    plannedTasks.add(new WorkerTask(
                            stageIdx,
                            sName,
                            sKind,
                            false,
                            rt -> rt.runBroadcastDispatcher(
                                    stageIdx, broadcastStage, branchChannels, branchNames),
                            rt -> {
                                for (BoundedChannel<Object> bCh : branchChannels) {
                                    bCh.close();
                                }
                            }));

                    for (int b = 0; b < sinkCount; b++) {
                        BoundedChannel<Object> bCh = branchChannels.get(b);
                        PipeSink<?> branchSink = broadcastStage.sinks().get(b);
                        String branchName = branchNames.get(b);
                        StageTelemetry branchTelemetry = branchTelemetries.get(b);
                        for (int bw = 0; bw < branchWorkersPerSink; bw++) {
                            plannedTasks.add(new WorkerTask(
                                    -1,
                                    branchName,
                                    sKind,
                                    false,
                                    rt -> rt.runAuxSinkWorker(
                                            branchName, broadcastStage.config(), branchSink, bCh, branchTelemetry),
                                    rt -> rt.finishAuxStage(branchName, broadcastStage.config(), totalBranchWorkers)));
                        }
                    }
                }
                case StageDefinition.RouteSinkStage<?, ?> routeStage -> {
                    int maxWorkers = routeStage.config().maxConcurrency();
                    stageRemaining[i] = new AtomicInteger(maxWorkers);
                    int stageIdx = i;
                    for (int w = 0; w < maxWorkers; w++) {
                        plannedTasks.add(new WorkerTask(
                                stageIdx,
                                sName,
                                sKind,
                                false,
                                rt -> rt.runRouteSinkWorker(stageIdx, sName, routeStage)));
                    }
                }
            }
        }

        managedStages.addAll(auxManagedStages);
        AdaptiveController adaptiveController = new AdaptiveController(
                config.adaptiveConfig(),
                config.cpuBudget(),
                managedStages,
                listener);

        // Rebind WorkerTask runnables now that runtime instance exists.
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
        runtime.bindAndStart(plannedTasks);
        return new PipeExecution<>(runtime);
    }

    private void bindAndStart(List<WorkerTask> tasks) {
        if (config.executorMode() == PipeConfig.ExecutorMode.CUSTOM
                && config.customExecutor() instanceof ThreadPoolExecutor tpe) {
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
        if (config.adaptiveConfig().enabled()) {
            adaptiveController.start();
        }
        for (WorkerTask task : tasks) {
            task.bind(this);
            if (task.isSource) {
                synchronized (workersLock) {
                    sourceTasks.add(task);
                }
            }
        }
        for (WorkerTask task : tasks) {
            if (terminated.get()) {
                if (task.state.compareAndSet(STATE_PENDING, STATE_DONE)) {
                    task.finishOnCancel();
                }
                continue;
            }
            try {
                Future<?> future = scheduler.submit(task.stageKind, task.isSource, task);
                boolean shouldCancelNow;
                synchronized (workersLock) {
                    task.future = future;
                    submittedTasks.add(task);
                    shouldCancelNow = terminated.get()
                            || (task.isSource && draining.get());
                }
                if (shouldCancelNow) {
                    cancelTask(task);
                }
            } catch (RejectedExecutionException rex) {
                fail(task.stageName, rex);
                if (task.state.compareAndSet(STATE_PENDING, STATE_DONE)) {
                    task.finishOnCancel();
                }
            }
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

    // -------------------------------------------------------------------------
    // Stage Worker Loops
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void runSourceWorker(int stageIndex, String stageName, PipeSource<?> rawSource) throws Exception {
        PipeSource<Object> source = (PipeSource<Object>) rawSource;
        BoundedChannel<Object> outChannel = channels.get(stageIndex);
        StageTelemetry telemetry = stageTelemetries.get(stageIndex);
        String downstreamName = stageNames.get(stageIndex + 1);
        OverflowPolicy downstreamPolicy = stages.get(stageIndex + 1).config().overflowPolicy();

        PipeEmitter<Object> emitter = item -> {
            Object wrapped = wrapWithCurrentContext(item);
            long t0 = System.nanoTime();
            telemetry.onProcessingStart();
            try {
                BoundedChannel.SendResult res = sendToChannel(outChannel, wrapped, downstreamName, downstreamPolicy);
                if (res.status() != BoundedChannel.SendStatus.DROPPED_NEWEST) {
                    itemsProduced.incrementAndGet();
                }
                long pureNanos = Math.max(0L, (System.nanoTime() - t0) - res.blockedNanos());
                telemetry.onProcessingSuccess(pureNanos);
            } catch (PipeAbortedException | PipeDrainingException | InterruptedException ex) {
                telemetry.onProcessingAborted();
                throw ex;
            } catch (Throwable t) {
                telemetry.onProcessingError(System.nanoTime() - t0);
                throw t;
            }
        };

        try {
            source.produce(emitter);
        } catch (PipeDrainingException ignored) {
            // Graceful drain requested: stop emitting cleanly without failing pipeline.
        } catch (InterruptedException ex) {
            if (draining.get() && !terminated.get()) {
                Thread.interrupted(); // Clear flag before worker exit.
                return;
            }
            throw ex;
        }
    }

    @SuppressWarnings("unchecked")
    private void runProcessorWorker(
            int stageIndex,
            String stageName,
            StageDefinition.ProcessorStage<?, ?> stage) throws Exception {
        BoundedChannel<Object> inChannel = channels.get(stageIndex - 1);
        BoundedChannel<Object> outChannel = channels.get(stageIndex);
        DynamicPermitPool pool = stagePermitPools.get(stageIndex);
        StageTelemetry telemetry = stageTelemetries.get(stageIndex);
        String downstreamName = stageNames.get(stageIndex + 1);
        OverflowPolicy downstreamPolicy = stages.get(stageIndex + 1).config().overflowPolicy();
        PipeProcessor<Object, Object> processor = (PipeProcessor<Object, Object>) stage.processor();

        while (!terminated.get() && !Thread.currentThread().isInterrupted()) {
            if (!pool.acquire()) {
                break;
            }
            try {
                Object raw = inChannel.receive();
                if (raw == null) {
                    pool.onEof();
                    break;
                }
                Object payload = unwrapPayload(raw);
                long[] blockedNanos = new long[1];
                boolean cpuRouted = isCpuRouted(stage.config(), config.executorMode());
                if (cpuRouted) {
                    List<Object> bufferedEmissions = new ArrayList<>();
                    PipeEmitter<Object> emitter = outItem -> bufferedEmissions.add(wrapWithCurrentContext(outItem));
                    ItemOutcome outcome = executeWithTelemetry(
                            stageName,
                            stage.config(),
                            telemetry,
                            payload,
                            blockedNanos,
                            true,
                            () -> {
                                bufferedEmissions.clear();
                                withContext(raw, () -> processor.process(payload, emitter));
                            });
                    if (outcome == ItemOutcome.SUCCESS) {
                        for (Object wrappedOut : bufferedEmissions) {
                            sendToChannel(outChannel, wrappedOut, downstreamName, downstreamPolicy);
                        }
                    }
                } else {
                    PipeEmitter<Object> emitter = outItem -> {
                        Object wrappedOut = wrapWithCurrentContext(outItem);
                        BoundedChannel.SendResult res =
                                sendToChannel(outChannel, wrappedOut, downstreamName, downstreamPolicy);
                        blockedNanos[0] += res.blockedNanos();
                    };
                    executeWithTelemetry(
                            stageName,
                            stage.config(),
                            telemetry,
                            payload,
                            blockedNanos,
                            false,
                            () -> withContext(raw, () -> processor.process(payload, emitter)));
                }
            } finally {
                pool.release();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void runOrderedMapWorker(
            int stageIndex,
            String stageName,
            StageDefinition.OrderedMapStage<?, ?> stage,
            OrderedReorderBuffer reorderBuffer) throws Exception {
        BoundedChannel<Object> inChannel = channels.get(stageIndex - 1);
        BoundedChannel<Object> outChannel = channels.get(stageIndex);
        DynamicPermitPool pool = stagePermitPools.get(stageIndex);
        StageTelemetry telemetry = stageTelemetries.get(stageIndex);
        String downstreamName = stageNames.get(stageIndex + 1);
        OverflowPolicy downstreamPolicy = stages.get(stageIndex + 1).config().overflowPolicy();
        PipeFunction<Object, Object> mapper = (PipeFunction<Object, Object>) stage.mapper();

        while (!terminated.get() && !Thread.currentThread().isInterrupted()) {
            if (!pool.acquire()) {
                break;
            }
            try {
                BoundedChannel.SequencedItem<Object> seqItem = inChannel.receiveSequenced();
                if (seqItem == null) {
                    pool.onEof();
                    break;
                }
                Object raw = seqItem.item();
                Object payload = unwrapPayload(raw);
                Object[] resultHolder = new Object[1];
                long[] zeroBlocked = new long[1];

                ItemOutcome outcome = executeWithTelemetry(
                        stageName,
                        stage.config(),
                        telemetry,
                        payload,
                        zeroBlocked,
                        isCpuRouted(stage.config(), config.executorMode()),
                        () -> withContext(raw, () -> {
                            Object mapped = Objects.requireNonNull(
                                    mapper.apply(payload), "Mapper function must not return null");
                            resultHolder[0] = wrapWithCurrentContext(mapped);
                        }));

                Object toSubmit = outcome == ItemOutcome.SUCCESS
                        ? resultHolder[0]
                        : OrderedReorderBuffer.TOMBSTONE;
                reorderBuffer.complete(seqItem.sequence(), toSubmit, wrappedOut ->
                        sendToChannel(outChannel, wrappedOut, downstreamName, downstreamPolicy));
            } finally {
                pool.release();
            }
        }
    }

    private void runBatchWorker(
            int stageIndex,
            String stageName,
            StageDefinition.BatchStage<?> stage) throws Exception {
        BoundedChannel<Object> inChannel = channels.get(stageIndex - 1);
        BoundedChannel<Object> outChannel = channels.get(stageIndex);
        DynamicPermitPool pool = stagePermitPools.get(stageIndex);
        StageTelemetry telemetry = stageTelemetries.get(stageIndex);
        String downstreamName = stageNames.get(stageIndex + 1);
        OverflowPolicy downstreamPolicy = stages.get(stageIndex + 1).config().overflowPolicy();
        long maxWaitNanos = stage.maxWait().toNanos();

        while (!terminated.get() && !Thread.currentThread().isInterrupted()) {
            if (!pool.acquire()) {
                break;
            }
            try {
                List<Object> rawBatch = new ArrayList<>(Math.min(stage.maxSize(), 256));
                if (!inChannel.receiveBatch(rawBatch, stage.maxSize(), maxWaitNanos)) {
                    pool.onEof();
                    break;
                }
                telemetry.onProcessingStart();
                long t0 = System.nanoTime();
                long blocked = 0L;
                try {
                    List<Object> payloads = new ArrayList<>(rawBatch.size());
                    for (Object raw : rawBatch) {
                        payloads.add(unwrapPayload(raw));
                    }
                    List<Object> immutableBatch = List.copyOf(payloads);
                    Object wrappedBatch = wrapWithCurrentContext(immutableBatch);
                    BoundedChannel.SendResult res =
                            sendToChannel(outChannel, wrappedBatch, downstreamName, downstreamPolicy);
                    blocked = res.blockedNanos();
                    telemetry.onProcessingSuccess(Math.max(0L, (System.nanoTime() - t0) - blocked));
                } catch (PipeAbortedException | InterruptedException ex) {
                    telemetry.onProcessingAborted();
                    throw ex;
                } catch (Throwable t) {
                    telemetry.onProcessingError(System.nanoTime() - t0);
                    throw t;
                }
            } finally {
                pool.release();
            }
        }
    }

    private void runRateLimitWorker(
            int stageIndex,
            String stageName,
            StageDefinition.RateLimitStage<?> stage,
            TokenBucketRateLimiter limiter) throws Exception {
        BoundedChannel<Object> inChannel = channels.get(stageIndex - 1);
        BoundedChannel<Object> outChannel = channels.get(stageIndex);
        DynamicPermitPool pool = stagePermitPools.get(stageIndex);
        StageTelemetry telemetry = stageTelemetries.get(stageIndex);
        String downstreamName = stageNames.get(stageIndex + 1);
        OverflowPolicy downstreamPolicy = stages.get(stageIndex + 1).config().overflowPolicy();

        while (!terminated.get() && !Thread.currentThread().isInterrupted()) {
            if (!pool.acquire()) {
                break;
            }
            try {
                Object raw = inChannel.receive();
                if (raw == null) {
                    pool.onEof();
                    break;
                }
                telemetry.onProcessingStart();
                long t0 = System.nanoTime();
                long blocked = 0L;
                try {
                    limiter.acquire();
                    BoundedChannel.SendResult res =
                            sendToChannel(outChannel, raw, downstreamName, downstreamPolicy);
                    blocked = res.blockedNanos();
                    telemetry.onProcessingSuccess(Math.max(0L, (System.nanoTime() - t0) - blocked));
                } catch (PipeAbortedException | InterruptedException ex) {
                    telemetry.onProcessingAborted();
                    throw ex;
                } catch (Throwable t) {
                    telemetry.onProcessingError(System.nanoTime() - t0);
                    throw t;
                }
            } finally {
                pool.release();
            }
        }
    }

    private void runTeeMainWorker(
            int stageIndex,
            String stageName,
            StageDefinition.TeeStage<?> stage,
            BoundedChannel<Object> sideChannel) throws Exception {
        BoundedChannel<Object> inChannel = channels.get(stageIndex - 1);
        BoundedChannel<Object> outChannel = channels.get(stageIndex);
        DynamicPermitPool pool = stagePermitPools.get(stageIndex);
        StageTelemetry telemetry = stageTelemetries.get(stageIndex);
        String downstreamName = stageNames.get(stageIndex + 1);
        OverflowPolicy downstreamPolicy = stages.get(stageIndex + 1).config().overflowPolicy();
        String sideName = stage.sideConfig().resolveName(stageName + "-tee");
        OverflowPolicy sidePolicy = stage.sideConfig().overflowPolicy();

        while (!terminated.get() && !Thread.currentThread().isInterrupted()) {
            if (!pool.acquire()) {
                break;
            }
            try {
                Object raw = inChannel.receive();
                if (raw == null) {
                    pool.onEof();
                    break;
                }
                telemetry.onProcessingStart();
                long t0 = System.nanoTime();
                try {
                    BoundedChannel.SendResult sideRes =
                            sendToChannel(sideChannel, raw, sideName, sidePolicy);
                    BoundedChannel.SendResult mainRes =
                            sendToChannel(outChannel, raw, downstreamName, downstreamPolicy);
                    long blocked = sideRes.blockedNanos() + mainRes.blockedNanos();
                    telemetry.onProcessingSuccess(Math.max(0L, (System.nanoTime() - t0) - blocked));
                } catch (PipeAbortedException | InterruptedException ex) {
                    telemetry.onProcessingAborted();
                    throw ex;
                } catch (Throwable t) {
                    telemetry.onProcessingError(System.nanoTime() - t0);
                    throw t;
                }
            } finally {
                pool.release();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void runSinkWorker(
            int stageIndex,
            String stageName,
            StageDefinition.SinkStage<?, ?> stage) throws Exception {
        BoundedChannel<Object> inChannel = channels.get(stageIndex - 1);
        DynamicPermitPool pool = stagePermitPools.get(stageIndex);
        StageTelemetry telemetry = stageTelemetries.get(stageIndex);
        PipeSink<Object> sink = (PipeSink<Object>) stage.sink();
        long[] zeroBlocked = new long[1];

        while (!terminated.get() && !Thread.currentThread().isInterrupted()) {
            if (!pool.acquire()) {
                break;
            }
            try {
                Object raw = inChannel.receive();
                if (raw == null) {
                    pool.onEof();
                    break;
                }
                Object payload = unwrapPayload(raw);
                ItemOutcome outcome = executeWithTelemetry(
                        stageName,
                        stage.config(),
                        telemetry,
                        payload,
                        zeroBlocked,
                        isCpuRouted(stage.config(), config.executorMode()),
                        () -> withContext(raw, () -> sink.consume(payload)));
                if (outcome == ItemOutcome.SUCCESS) {
                    itemsConsumed.incrementAndGet();
                }
            } finally {
                pool.release();
            }
        }
    }

    private void runBroadcastDispatcher(
            int stageIndex,
            StageDefinition.BroadcastSinkStage<?> stage,
            List<BoundedChannel<Object>> branchChannels,
            List<String> branchNames) throws Exception {
        BoundedChannel<Object> inChannel = channels.get(stageIndex - 1);
        StageTelemetry telemetry = stageTelemetries.get(stageIndex);
        OverflowPolicy policy = stage.config().overflowPolicy();
        int branchCount = branchChannels.size();

        while (!terminated.get() && !Thread.currentThread().isInterrupted()) {
            Object raw = inChannel.receive();
            if (raw == null) {
                break;
            }
            telemetry.onProcessingStart();
            long t0 = System.nanoTime();
            long blocked = 0L;
            try {
                BroadcastItem item = new BroadcastItem(
                        raw, new AtomicInteger(branchCount), new AtomicBoolean(false));
                boolean[] sent = new boolean[branchCount];
                for (int b = 0; b < branchCount; b++) {
                    BoundedChannel.SendResult res = trySendToChannel(
                            branchChannels.get(b), item, branchNames.get(b), policy);
                    if (res != null) {
                        sent[b] = true;
                        if (res.status() == BoundedChannel.SendStatus.DROPPED_NEWEST) {
                            markBroadcastBranchDropped(item);
                        }
                    }
                }
                for (int b = 0; b < branchCount; b++) {
                    if (!sent[b]) {
                        BoundedChannel.SendResult res = sendToChannel(
                                branchChannels.get(b), item, branchNames.get(b), policy);
                        blocked += res.blockedNanos();
                        if (res.status() == BoundedChannel.SendStatus.DROPPED_NEWEST) {
                            markBroadcastBranchDropped(item);
                        }
                    }
                }
                telemetry.onProcessingSuccess(Math.max(0L, (System.nanoTime() - t0) - blocked));
            } catch (PipeAbortedException | InterruptedException ex) {
                telemetry.onProcessingAborted();
                throw ex;
            } catch (Throwable t) {
                telemetry.onProcessingError(System.nanoTime() - t0);
                throw t;
            }
        }
    }

    private static void markBroadcastBranchDropped(BroadcastItem item) {
        item.failed().set(true);
        item.remaining().decrementAndGet();
    }

    @SuppressWarnings("unchecked")
    private void runRouteSinkWorker(
            int stageIndex,
            String stageName,
            StageDefinition.RouteSinkStage<?, ?> stage) throws Exception {
        BoundedChannel<Object> inChannel = channels.get(stageIndex - 1);
        DynamicPermitPool pool = stagePermitPools.get(stageIndex);
        StageTelemetry telemetry = stageTelemetries.get(stageIndex);
        PipeFunction<Object, Object> classifier = (PipeFunction<Object, Object>) stage.classifier();
        java.util.Map<?, ?> rawRoutes = stage.routes();
        PipeSink<Object> defaultSink = (PipeSink<Object>) stage.defaultSink();
        long[] zeroBlocked = new long[1];

        while (!terminated.get() && !Thread.currentThread().isInterrupted()) {
            if (!pool.acquire()) {
                break;
            }
            try {
                Object raw = inChannel.receive();
                if (raw == null) {
                    pool.onEof();
                    break;
                }
                Object payload = unwrapPayload(raw);
                ItemOutcome outcome = executeWithTelemetry(
                        stageName,
                        stage.config(),
                        telemetry,
                        payload,
                        zeroBlocked,
                        isCpuRouted(stage.config(), config.executorMode()),
                        () -> withContext(raw, () -> {
                            Object key = classifier.apply(payload);
                            Object routed = rawRoutes.get(key);
                            PipeSink<Object> targetSink = routed != null
                                    ? (PipeSink<Object>) routed
                                    : defaultSink;
                            if (targetSink == null) {
                                throw new IllegalStateException("No route configured for key: " + key);
                            }
                            targetSink.consume(payload);
                        }));
                if (outcome == ItemOutcome.SUCCESS) {
                    itemsConsumed.incrementAndGet();
                }
            } finally {
                pool.release();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void runAuxSinkWorker(
            String auxName,
            StageConfig auxConfig,
            PipeSink<?> rawSink,
            BoundedChannel<Object> auxChannel,
            StageTelemetry telemetry) throws Exception {
        PipeSink<Object> sink = (PipeSink<Object>) rawSink;
        long[] zeroBlocked = new long[1];

        while (!terminated.get() && !Thread.currentThread().isInterrupted()) {
            Object received = auxChannel.receive();
            if (received == null) {
                break;
            }
            BroadcastItem broadcastItem = received instanceof BroadcastItem bi ? bi : null;
            Object raw = broadcastItem != null ? broadcastItem.raw() : received;
            Object payload = unwrapPayload(raw);
            ItemOutcome outcome = executeWithTelemetry(
                    auxName,
                    auxConfig,
                    telemetry,
                    payload,
                    zeroBlocked,
                    isCpuRouted(auxConfig, config.executorMode()),
                    () -> withContext(raw, () -> sink.consume(payload)));
            if (broadcastItem != null) {
                if (outcome == ItemOutcome.SKIPPED) {
                    broadcastItem.failed().set(true);
                }
                if (broadcastItem.remaining().decrementAndGet() == 0 && !broadcastItem.failed().get()) {
                    itemsConsumed.incrementAndGet();
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Shared Channel & Policy Execution Helpers
    // -------------------------------------------------------------------------

    private BoundedChannel.SendResult sendToChannel(
            BoundedChannel<Object> channel,
            Object item,
            String downstreamName,
            OverflowPolicy policy) throws InterruptedException {
        try {
            BoundedChannel.SendResult res = channel.sendTimed(item);
            if (res.status() != BoundedChannel.SendStatus.ENQUEUED) {
                metricsListener.onChannelOverflow(downstreamName, policy);
            }
            return res;
        } catch (ChannelOverflowException ex) {
            metricsListener.onChannelOverflow(downstreamName, OverflowPolicy.FAIL);
            throw ex;
        }
    }

    private BoundedChannel.SendResult trySendToChannel(
            BoundedChannel<Object> channel,
            Object item,
            String downstreamName,
            OverflowPolicy policy) throws InterruptedException {
        try {
            BoundedChannel.SendResult res = channel.trySend(item);
            if (res != null && res.status() != BoundedChannel.SendStatus.ENQUEUED) {
                metricsListener.onChannelOverflow(downstreamName, policy);
            }
            return res;
        } catch (ChannelOverflowException ex) {
            metricsListener.onChannelOverflow(downstreamName, OverflowPolicy.FAIL);
            throw ex;
        }
    }

    private static boolean isCpuRouted(StageConfig stageConfig, PipeConfig.ExecutorMode mode) {
        return stageConfig.resolveEffectiveKind(mode) == StageKind.CPU_BOUND
                && !(mode == PipeConfig.ExecutorMode.CUSTOM && stageConfig.kind() == StageKind.INHERIT);
    }

    private ItemOutcome executeWithTelemetry(
            String stageName,
            StageConfig stageConfig,
            StageTelemetry telemetry,
            Object payload,
            long[] blockedNanosHolder,
            boolean cpuRouted,
            ThrowingRunnable action) throws Exception {

        ErrorPolicy policy = resolveErrorPolicy(stageConfig);
        RetrySpec retrySpec = stageConfig.retrySpec();

        telemetry.onProcessingStart();
        long t0 = System.nanoTime();
        int attempt = 0;

        while (true) {
            try {
                if (cpuRouted) {
                    long queueWaitNanos = cpuPool.execute(action);
                    blockedNanosHolder[0] += queueWaitNanos;
                } else {
                    action.run();
                }
                long pureNanos = Math.max(0L, (System.nanoTime() - t0) - blockedNanosHolder[0]);
                telemetry.onProcessingSuccess(pureNanos);
                return ItemOutcome.SUCCESS;
            } catch (PipeAbortedException | PipeDrainingException | InterruptedException ex) {
                telemetry.onProcessingAborted();
                throw ex;
            } catch (ChannelOverflowException ex) {
                telemetry.onProcessingError(Math.max(0L, (System.nanoTime() - t0) - blockedNanosHolder[0]));
                throw ex;
            } catch (Exception ex) {
                metricsListener.onItemError(stageName, ex);
                if (policy == ErrorPolicy.RETRY) {
                    attempt++;
                    if (retrySpec.shouldRetry(attempt, ex)) {
                        Duration delay = retrySpec.delayForAttempt(attempt);
                        if (!delay.isZero() && !delay.isNegative()) {
                            try {
                                Thread.sleep(delay);
                            } catch (InterruptedException iex) {
                                telemetry.onProcessingAborted();
                                throw iex;
                            }
                        }
                        continue;
                    }
                    // Retries exhausted.
                    telemetry.onProcessingError(Math.max(0L, (System.nanoTime() - t0) - blockedNanosHolder[0]));
                    if (stageConfig.deadLetterHandler() != null) {
                        handleDeadLetter(stageName, stageConfig.deadLetterHandler(), payload, ex);
                        return ItemOutcome.SKIPPED;
                    }
                    throw ex;
                } else if (policy == ErrorPolicy.DEAD_LETTER) {
                    telemetry.onProcessingError(Math.max(0L, (System.nanoTime() - t0) - blockedNanosHolder[0]));
                    if (stageConfig.deadLetterHandler() != null) {
                        handleDeadLetter(stageName, stageConfig.deadLetterHandler(), payload, ex);
                    } else {
                        handleSkippedError(stageName, ex);
                    }
                    return ItemOutcome.SKIPPED;
                } else if (policy == ErrorPolicy.SKIP) {
                    telemetry.onProcessingError(Math.max(0L, (System.nanoTime() - t0) - blockedNanosHolder[0]));
                    handleSkippedError(stageName, ex);
                    return ItemOutcome.SKIPPED;
                } else {
                    telemetry.onProcessingError(Math.max(0L, (System.nanoTime() - t0) - blockedNanosHolder[0]));
                    throw ex;
                }
            } catch (Throwable t) {
                telemetry.onProcessingError(Math.max(0L, (System.nanoTime() - t0) - blockedNanosHolder[0]));
                throw t;
            }
        }
    }

    private ErrorPolicy resolveErrorPolicy(StageConfig stageConfig) {
        if (stageConfig.errorPolicy() != ErrorPolicy.INHERIT) {
            return stageConfig.errorPolicy();
        }
        return config.errorStrategy() == PipeConfig.ErrorStrategy.CONTINUE_ON_ITEM_ERROR
                ? ErrorPolicy.SKIP
                : ErrorPolicy.FAIL_FAST;
    }

    private void handleSkippedError(String stageName, Exception ex) {
        errorsSkipped.incrementAndGet();
        BiConsumer<String, Throwable> handler = config.errorHandler();
        if (handler != null) {
            handler.accept(stageName, ex);
        }
    }

    private void handleDeadLetter(
            String stageName,
            DeadLetterHandler<Object> handler,
            Object payload,
            Exception ex) {
        errorsSkipped.incrementAndGet();
        handler.handle(payload, ex, stageName);
        metricsListener.onDeadLetter(stageName, ex);
    }

    // -------------------------------------------------------------------------
    // Context Helpers
    // -------------------------------------------------------------------------

    private Object wrapWithCurrentContext(Object payload) {
        if (contextPropagator == null) {
            return payload;
        }
        ContextPropagator.ContextSnapshot snap = Objects.requireNonNull(
                contextPropagator.capture(), "ContextPropagator.capture() must not return null");
        return new ContextEnvelope(payload, snap);
    }

    private static Object unwrapPayload(Object raw) {
        return raw instanceof ContextEnvelope env ? env.payload() : raw;
    }

    private ContextPropagator.Scope attachForRaw(Object raw) {
        if (raw instanceof ContextEnvelope env) {
            return env.snapshot().attach();
        }
        if (launchContext != null) {
            return launchContext.attach();
        }
        return NOOP_SCOPE;
    }

    private void withContext(Object raw, ThrowingRunnable action) throws Exception {
        ContextPropagator.Scope scope = attachForRaw(raw);
        try {
            action.run();
        } finally {
            scope.close();
        }
    }

    private void withLaunchContext(Runnable action) {
        if (launchContext == null) {
            action.run();
            return;
        }
        ContextPropagator.Scope scope = launchContext.attach();
        try {
            action.run();
        } finally {
            scope.close();
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle, Completion, Drain, Cancel, Fail
    // -------------------------------------------------------------------------

    private void closeSideOnMainEnd(int mainStageIndex, BoundedChannel<Object> sideChannel) {
        if (stageRemainingWorkers[mainStageIndex].decrementAndGet() == 0) {
            try {
                finishStage(mainStageIndex);
            } finally {
                sideChannel.close();
            }
        }
    }

    private void finishAuxStage(String auxName, StageConfig auxConfig, AtomicInteger remaining) {
        if (remaining.decrementAndGet() == 0) {
            try {
                if (!terminated.get() && auxConfig.onComplete() != null) {
                    withLaunchContext(auxConfig.onComplete());
                }
            } catch (Throwable t) {
                fail(auxName, t);
            }
        }
    }

    private void onWorkerEnded(WorkerTask task) {
        if (task.customStageEnd != null) {
            task.customStageEnd.accept(this);
        } else if (task.stageIndex >= 0) {
            if (stageRemainingWorkers[task.stageIndex].decrementAndGet() == 0) {
                finishStage(task.stageIndex);
            }
        }
        if (totalRemainingWorkers.decrementAndGet() == 0) {
            finalizeRuntime();
        }
    }

    @SuppressWarnings("unchecked")
    private void finishStage(int stageIndex) {
        StageDefinition stage = stages.get(stageIndex);
        String stageName = stageNames.get(stageIndex);
        try {
            if (!terminated.get()) {
                Runnable onComplete = stage.config().onComplete();
                if (onComplete != null) {
                    withLaunchContext(onComplete);
                }
                if (stage instanceof StageDefinition.SinkStage<?, ?> sinkStage) {
                    withLaunchContext(() -> {
                        R result = ((StageDefinition.SinkStage<?, R>) sinkStage).resultSupplier().get();
                        resultRef.set(result);
                    });
                }
            }
        } catch (Throwable t) {
            fail(stageName, t);
        } finally {
            if (stageIndex < channels.size()) {
                channels.get(stageIndex).close();
            }
        }
    }

    private void finalizeRuntime() {
        try {
            adaptiveController.stop();
            // Sample final stage snapshots once before completing.
            try {
                adaptiveController.tick();
            } catch (Throwable ignored) {
                // Best-effort final metrics refresh.
            }

            synchronized (completionLock) {
                endNanos.compareAndSet(-1L, System.nanoTime());
                Throwable failure = firstFailure.get();
                if (failure != null) {
                    completionFuture.completeExceptionally(failure);
                } else {
                    completionFuture.complete(resultRef.get());
                }
            }
            scheduler.shutdown();
            if (cpuPool != null) {
                cpuPool.shutdown();
            }
            try {
                metricsListener.onPipelineCompleted(metricsSnapshot());
            } catch (Throwable ignored) {
                // Listener exceptions must never block worker termination signaling.
            }
        } finally {
            allWorkersDone.countDown();
        }
    }

    private void fail(String stageName, Throwable error) {
        Throwable wrapped = wrapFailure(stageName, error);
        boolean primarySet;
        synchronized (completionLock) {
            if (completionFuture.isDone()) {
                return;
            }
            primarySet = firstFailure.compareAndSet(null, wrapped);
            if (primarySet) {
                terminated.set(true);
            }
        }
        if (primarySet) {
            abortAllStructures(wrapped);
            cancelAllWorkers();
        } else {
            Throwable primary = firstFailure.get();
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
        for (BoundedChannel<Object> channel : allChannels) {
            channel.abort(cause);
        }
        for (DynamicPermitPool pool : stagePermitPools) {
            pool.abort(cause);
        }
        for (OrderedReorderBuffer buffer : reorderBuffers) {
            buffer.abort(cause);
        }
        for (TokenBucketRateLimiter limiter : rateLimiters) {
            limiter.abort(cause);
        }
    }

    private void cancelAllWorkers() {
        List<WorkerTask> snapshot;
        synchronized (workersLock) {
            snapshot = new ArrayList<>(submittedTasks);
        }
        for (WorkerTask task : snapshot) {
            cancelTask(task);
        }
    }

    private void cancelTask(WorkerTask task) {
        if (task.state.compareAndSet(STATE_PENDING, STATE_DONE)) {
            Future<?> f = task.future;
            if (f != null) {
                f.cancel(true);
            }
            task.finishOnCancel();
        } else if (task.state.get() == STATE_RUNNING) {
            Future<?> f = task.future;
            if (f != null) {
                f.cancel(true);
            }
        }
    }

    /**
     * Initiates a graceful drain: interrupts/stops source stage(s) cleanly and lets all
     * already-emitted downstream items finish processing through the pipeline.
     */
    void requestDrain() {
        if (completionFuture.isDone() || terminated.get()) {
            return;
        }
        if (draining.compareAndSet(false, true)) {
            if (!channels.isEmpty()) {
                channels.get(0).beginDrain();
            }
            List<WorkerTask> sourceSnapshot;
            synchronized (workersLock) {
                sourceSnapshot = new ArrayList<>(sourceTasks);
            }
            for (WorkerTask srcTask : sourceSnapshot) {
                cancelTask(srcTask);
            }
        }
    }

    void cancel() {
        CancellationException cancelEx;
        boolean newlyCancelled;
        synchronized (completionLock) {
            if (completionFuture.isDone()) {
                return;
            }
            newlyCancelled = terminated.compareAndSet(false, true);
            if (newlyCancelled) {
                cancelled.set(true);
                cancelEx = new CancellationException("Pipeline execution was cancelled");
                firstFailure.compareAndSet(null, cancelEx);
            } else {
                cancelEx = null;
            }
        }
        if (newlyCancelled) {
            abortAllStructures(cancelEx);
        }
        cancelAllWorkers();
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

    // -------------------------------------------------------------------------
    // Reorder Buffer & WorkerTask
    // -------------------------------------------------------------------------

    private static final class OrderedReorderBuffer {

        private static final Object TOMBSTONE = new Object();

        private final int maxWindow;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition windowAvailable = lock.newCondition();
        private final TreeMap<Long, Object> pending = new TreeMap<>();

        private long nextEmitSequence;
        private boolean emitting;
        private boolean aborted;
        private Throwable abortCause;

        private OrderedReorderBuffer(int maxWindow) {
            this.maxWindow = maxWindow;
        }

        private void complete(long seq, Object valueOrTombstone, EmitAction emitter)
                throws InterruptedException {
            lock.lockInterruptibly();
            try {
                while (!aborted && (seq - nextEmitSequence) >= maxWindow) {
                    windowAvailable.await();
                }
                if (aborted) {
                    throw new PipeAbortedException(abortCause);
                }
                pending.put(seq, valueOrTombstone);
                if (emitting || seq != nextEmitSequence) {
                    return;
                }
                emitting = true;
            } finally {
                lock.unlock();
            }

            try {
                while (true) {
                    Object nextVal;
                    lock.lockInterruptibly();
                    try {
                        if (aborted) {
                            throw new PipeAbortedException(abortCause);
                        }
                        if (!pending.containsKey(nextEmitSequence)) {
                            emitting = false;
                            return;
                        }
                        nextVal = pending.remove(nextEmitSequence);
                        nextEmitSequence++;
                        windowAvailable.signalAll();
                    } finally {
                        lock.unlock();
                    }
                    if (nextVal != TOMBSTONE) {
                        emitter.emit(nextVal);
                    }
                }
            } catch (Throwable t) {
                lock.lock();
                try {
                    emitting = false;
                    windowAvailable.signalAll();
                } finally {
                    lock.unlock();
                }
                throw t;
            }
        }

        private void abort(Throwable cause) {
            lock.lock();
            try {
                if (!aborted) {
                    aborted = true;
                    abortCause = cause;
                    pending.clear();
                    windowAvailable.signalAll();
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private static final class WorkerTask implements Runnable {

        private final int stageIndex;
        private final String stageName;
        private final StageKind stageKind;
        private final boolean isSource;
        private final RuntimeTaskBody body;
        private final Consumer<PipelineRuntime<?>> customStageEnd;
        private final AtomicInteger state = new AtomicInteger(STATE_PENDING);
        private volatile Future<?> future;
        private PipelineRuntime<?> runtime;

        private WorkerTask(
                int stageIndex,
                String stageName,
                StageKind stageKind,
                boolean isSource,
                RuntimeTaskBody body) {
            this(stageIndex, stageName, stageKind, isSource, body, null);
        }

        private WorkerTask(
                int stageIndex,
                String stageName,
                StageKind stageKind,
                boolean isSource,
                RuntimeTaskBody body,
                Consumer<PipelineRuntime<?>> customStageEnd) {
            this.stageIndex = stageIndex;
            this.stageName = stageName;
            this.stageKind = stageKind;
            this.isSource = isSource;
            this.body = body;
            this.customStageEnd = customStageEnd;
        }

        private void bind(PipelineRuntime<?> runtime) {
            this.runtime = runtime;
        }

        private void finishOnCancel() {
            runtime.onWorkerEnded(this);
        }

        @Override
        public void run() {
            if (!state.compareAndSet(STATE_PENDING, STATE_RUNNING)) {
                return;
            }
            ContextPropagator.Scope scope =
                    runtime.launchContext != null ? runtime.launchContext.attach() : NOOP_SCOPE;
            try {
                body.run(runtime);
            } catch (PipeAbortedException ignored) {
                // Channel/pool aborted due to concurrent failure or cancellation.
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                if (!runtime.terminated.get() && !(isSource && runtime.draining.get())) {
                    runtime.fail(stageName, ex);
                }
            } catch (Throwable t) {
                if (!runtime.terminated.get() || !(t instanceof CancellationException)) {
                    runtime.fail(stageName, t);
                }
            } finally {
                try {
                    scope.close();
                } finally {
                    state.set(STATE_DONE);
                    runtime.onWorkerEnded(this);
                    // Clear interrupt status before returning pooled custom executor threads.
                    Thread.interrupted();
                }
            }
        }
    }
}
