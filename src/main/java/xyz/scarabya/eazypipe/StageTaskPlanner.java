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
import java.util.concurrent.atomic.AtomicInteger;
import xyz.scarabya.eazypipe.adaptive.AdaptiveController;
import xyz.scarabya.eazypipe.adaptive.StageTelemetry;

/**
 * Per-stage worker planning helper extracting task creation and auxiliary branch descriptors.
 */
final class StageTaskPlanner {

    private StageTaskPlanner() {
    }

    static void planStage(
            int stageIdx,
            StageDefinition stage,
            String sName,
            PipeConfig config,
            List<BoundedChannel<Object>> allChannels,
            List<OrderedReorderBuffer> reorderBuffers,
            List<TokenBucketRateLimiter> rateLimiters,
            AtomicInteger[] stageRemaining,
            List<WorkerTask> plannedTasks,
            List<AdaptiveController.ManagedStage> auxManagedStages) {
        StageKind sKind = stage.config().kind();
        switch (stage) {
            case StageDefinition.SourceStage<?> srcStage -> {
                int sourceCount = srcStage.sources().size();
                int workers = sourceCount > 1 ? sourceCount : srcStage.config().initialConcurrency();
                stageRemaining[stageIdx] = new AtomicInteger(workers);
                for (int w = 0; w < workers; w++) {
                    int sourceIndex = sourceCount > 1 ? w : 0;
                    plannedTasks.add(new WorkerTask(
                            stageIdx, sName, sKind, true,
                            rt -> StageWorkerLoops.runSourceWorker(
                                    rt, stageIdx, srcStage.sources().get(sourceIndex))));
                }
            }
            case StageDefinition.ProcessorStage<?, ?> procStage -> {
                int maxWorkers = procStage.config().maxConcurrency();
                stageRemaining[stageIdx] = new AtomicInteger(maxWorkers);
                for (int w = 0; w < maxWorkers; w++) {
                    plannedTasks.add(new WorkerTask(
                            stageIdx, sName, sKind, false,
                            rt -> StageWorkerLoops.runProcessorWorker(rt, stageIdx, sName, procStage)));
                }
            }
            case StageDefinition.OrderedMapStage<?, ?> ordStage -> {
                int maxWorkers = ordStage.config().maxConcurrency();
                stageRemaining[stageIdx] = new AtomicInteger(maxWorkers);
                OrderedReorderBuffer reorderBuffer = new OrderedReorderBuffer(Math.max(64, maxWorkers * 4));
                reorderBuffers.add(reorderBuffer);
                for (int w = 0; w < maxWorkers; w++) {
                    plannedTasks.add(new WorkerTask(
                            stageIdx, sName, sKind, false,
                            rt -> StageWorkerLoops.runOrderedMapWorker(
                                    rt, stageIdx, sName, ordStage, reorderBuffer)));
                }
            }
            case StageDefinition.BatchStage<?> batchStage -> {
                int maxWorkers = batchStage.config().maxConcurrency();
                stageRemaining[stageIdx] = new AtomicInteger(maxWorkers);
                for (int w = 0; w < maxWorkers; w++) {
                    plannedTasks.add(new WorkerTask(
                            stageIdx, sName, sKind, false,
                            rt -> StageWorkerLoops.runBatchWorker(rt, stageIdx, batchStage)));
                }
            }
            case StageDefinition.RateLimitStage<?> rlStage -> {
                int maxWorkers = rlStage.config().maxConcurrency();
                stageRemaining[stageIdx] = new AtomicInteger(maxWorkers);
                TokenBucketRateLimiter limiter =
                        new TokenBucketRateLimiter(rlStage.permitsPerSecond(), rlStage.burst());
                rateLimiters.add(limiter);
                for (int w = 0; w < maxWorkers; w++) {
                    plannedTasks.add(new WorkerTask(
                            stageIdx, sName, sKind, false,
                            rt -> StageWorkerLoops.runRateLimitWorker(rt, stageIdx, limiter)));
                }
            }
            case StageDefinition.TeeStage<?> teeStage -> planTeeStage(
                    stageIdx, sName, sKind, teeStage, config,
                    allChannels, stageRemaining, plannedTasks, auxManagedStages);
            case StageDefinition.SinkStage<?, ?> sinkStage -> {
                int maxWorkers = sinkStage.config().maxConcurrency();
                stageRemaining[stageIdx] = new AtomicInteger(maxWorkers);
                for (int w = 0; w < maxWorkers; w++) {
                    plannedTasks.add(new WorkerTask(
                            stageIdx, sName, sKind, false,
                            rt -> TerminalWorkerLoops.runSinkWorker(rt, stageIdx, sName, sinkStage)));
                }
            }
            case StageDefinition.BroadcastSinkStage<?> broadcastStage -> planBroadcastStage(
                    stageIdx, sName, sKind, broadcastStage, config,
                    allChannels, stageRemaining, plannedTasks, auxManagedStages);
            case StageDefinition.RouteSinkStage<?, ?> routeStage -> {
                int maxWorkers = routeStage.config().maxConcurrency();
                stageRemaining[stageIdx] = new AtomicInteger(maxWorkers);
                for (int w = 0; w < maxWorkers; w++) {
                    plannedTasks.add(new WorkerTask(
                            stageIdx, sName, sKind, false,
                            rt -> TerminalWorkerLoops.runRouteSinkWorker(rt, stageIdx, sName, routeStage)));
                }
            }
        }
    }

    private static void planTeeStage(
            int stageIdx,
            String sName,
            StageKind sKind,
            StageDefinition.TeeStage<?> teeStage,
            PipeConfig config,
            List<BoundedChannel<Object>> allChannels,
            AtomicInteger[] stageRemaining,
            List<WorkerTask> plannedTasks,
            List<AdaptiveController.ManagedStage> auxManagedStages) {
        int mainWorkers = teeStage.config().maxConcurrency();
        stageRemaining[stageIdx] = new AtomicInteger(mainWorkers);
        StageConfig sideCfg = teeStage.sideConfig();
        BoundedChannel<Object> sideChannel = new BoundedChannel<>(
                sideCfg.resolveBufferCapacity(config.defaultBufferCapacity()),
                sideCfg.overflowPolicy());
        allChannels.add(sideChannel);

        int sideWorkers = sideCfg.maxConcurrency();
        AtomicInteger sideRemaining = new AtomicInteger(sideWorkers);
        String sideName = sideCfg.resolveName(sName + "-tee");
        StageTelemetry sideTelemetry = new StageTelemetry();
        auxManagedStages.add(new AdaptiveController.ManagedStage(
                -1, false, sideName, sideCfg.resolveEffectiveKind(config.executorMode()),
                sideWorkers, sideWorkers, sideTelemetry, true, false,
                sideChannel::fillRatio, () -> 0.0, sideChannel::receiveWaitNanos,
                () -> 0L, sideChannel::droppedCount, () -> sideWorkers, ignored -> { }));

        for (int w = 0; w < mainWorkers; w++) {
            plannedTasks.add(new WorkerTask(
                    stageIdx, sName, sKind, false,
                    rt -> StageWorkerLoops.runTeeMainWorker(rt, stageIdx, sName, teeStage, sideChannel),
                    rt -> rt.lifecycle.closeSideOnMainEnd(stageIdx, sideChannel)));
        }
        for (int sw = 0; sw < sideWorkers; sw++) {
            plannedTasks.add(new WorkerTask(
                    -1, sideName, sideCfg.kind(), false,
                    rt -> TerminalWorkerLoops.runAuxSinkWorker(
                            rt, sideName, sideCfg, teeStage.sideSink(), sideChannel, sideTelemetry),
                    rt -> rt.lifecycle.finishAuxStage(sideName, sideCfg, sideRemaining)));
        }
    }

    private static void planBroadcastStage(
            int stageIdx,
            String sName,
            StageKind sKind,
            StageDefinition.BroadcastSinkStage<?> broadcastStage,
            PipeConfig config,
            List<BoundedChannel<Object>> allChannels,
            AtomicInteger[] stageRemaining,
            List<WorkerTask> plannedTasks,
            List<AdaptiveController.ManagedStage> auxManagedStages) {
        stageRemaining[stageIdx] = new AtomicInteger(1);
        int sinkCount = broadcastStage.sinks().size();
        int branchCap = broadcastStage.config().resolveBufferCapacity(config.defaultBufferCapacity());
        int branchWorkers = broadcastStage.config().maxConcurrency();
        AtomicInteger totalBranchWorkers = new AtomicInteger(sinkCount * branchWorkers);
        StageKind branchEffectiveKind = broadcastStage.config().resolveEffectiveKind(config.executorMode());

        List<BoundedChannel<Object>> branchChannels = new ArrayList<>(sinkCount);
        List<String> branchNames = new ArrayList<>(sinkCount);
        List<StageTelemetry> branchTelemetries = new ArrayList<>(sinkCount);
        for (int b = 0; b < sinkCount; b++) {
            BoundedChannel<Object> bCh = new BoundedChannel<>(branchCap, broadcastStage.config().overflowPolicy());
            branchChannels.add(bCh);
            allChannels.add(bCh);
            String branchName = sName + "-branch-" + b;
            branchNames.add(branchName);
            StageTelemetry branchTelemetry = new StageTelemetry();
            branchTelemetries.add(branchTelemetry);
            auxManagedStages.add(new AdaptiveController.ManagedStage(
                    -1, false, branchName, branchEffectiveKind, branchWorkers, branchWorkers,
                    branchTelemetry, true, false, bCh::fillRatio, () -> 0.0,
                    bCh::receiveWaitNanos, () -> 0L, bCh::droppedCount, () -> branchWorkers, ignored -> { }));
        }

        plannedTasks.add(new WorkerTask(
                stageIdx, sName, sKind, false,
                rt -> TerminalWorkerLoops.runBroadcastDispatcher(
                        rt, stageIdx, broadcastStage, branchChannels, branchNames),
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
            for (int bw = 0; bw < branchWorkers; bw++) {
                plannedTasks.add(new WorkerTask(
                        -1, branchName, sKind, false,
                        rt -> TerminalWorkerLoops.runAuxSinkWorker(
                                rt, branchName, broadcastStage.config(), branchSink, bCh, branchTelemetry),
                        rt -> rt.lifecycle.finishAuxStage(
                                branchName, broadcastStage.config(), totalBranchWorkers)));
            }
        }
    }
}
