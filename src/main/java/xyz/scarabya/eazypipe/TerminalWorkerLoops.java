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

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import xyz.scarabya.eazypipe.adaptive.StageTelemetry;

/**
 * Worker execution loops for terminal sink, broadcast, routing, and auxiliary branch stages.
 */
final class TerminalWorkerLoops {

    private TerminalWorkerLoops() {
    }

    @SuppressWarnings("unchecked")
    static void runSinkWorker(
            PipelineRuntime<?> rt,
            int stageIndex,
            String stageName,
            StageDefinition.SinkStage<?, ?> stage) throws Exception {
        BoundedChannel<Object> inChannel = rt.channels.get(stageIndex - 1);
        DynamicPermitPool pool = rt.stagePermitPools.get(stageIndex);
        StageTelemetry telemetry = rt.stageTelemetries.get(stageIndex);
        PipeSink<Object> sink = (PipeSink<Object>) stage.sink();
        long[] zeroBlocked = new long[1];
        boolean cpuRouted = StageExecutionPolicy.isCpuRouted(stage.config(), rt.config.executorMode());

        while (!rt.terminated.get() && !Thread.currentThread().isInterrupted()) {
            if (!pool.acquire()) {
                break;
            }
            try {
                Object raw = inChannel.receive();
                if (raw == null) {
                    pool.onEof();
                    break;
                }
                Object payload = ContextSupport.unwrapPayload(raw);
                StageExecutionPolicy.ItemOutcome outcome = rt.executionPolicy.executeWithTelemetry(
                        stageName,
                        stage.config(),
                        telemetry,
                        payload,
                        zeroBlocked,
                        cpuRouted,
                        () -> rt.context.withContext(raw, () -> sink.consume(payload)));
                if (outcome == StageExecutionPolicy.ItemOutcome.SUCCESS) {
                    rt.itemsConsumed.incrementAndGet();
                }
            } finally {
                pool.release();
            }
        }
    }

    static void runBroadcastDispatcher(
            PipelineRuntime<?> rt,
            int stageIndex,
            StageDefinition.BroadcastSinkStage<?> stage,
            List<BoundedChannel<Object>> branchChannels,
            List<String> branchNames) throws Exception {
        BoundedChannel<Object> inChannel = rt.channels.get(stageIndex - 1);
        StageTelemetry telemetry = rt.stageTelemetries.get(stageIndex);
        OverflowPolicy policy = stage.config().overflowPolicy();
        int branchCount = branchChannels.size();

        while (!rt.terminated.get() && !Thread.currentThread().isInterrupted()) {
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
                    BoundedChannel.SendResult res = rt.executionPolicy.trySendToChannel(
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
                        BoundedChannel.SendResult res = rt.executionPolicy.sendToChannel(
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
    static void runRouteSinkWorker(
            PipelineRuntime<?> rt,
            int stageIndex,
            String stageName,
            StageDefinition.RouteSinkStage<?, ?> stage) throws Exception {
        BoundedChannel<Object> inChannel = rt.channels.get(stageIndex - 1);
        DynamicPermitPool pool = rt.stagePermitPools.get(stageIndex);
        StageTelemetry telemetry = rt.stageTelemetries.get(stageIndex);
        PipeFunction<Object, Object> classifier = (PipeFunction<Object, Object>) stage.classifier();
        Map<?, ?> rawRoutes = stage.routes();
        PipeSink<Object> defaultSink = (PipeSink<Object>) stage.defaultSink();
        long[] zeroBlocked = new long[1];
        boolean cpuRouted = StageExecutionPolicy.isCpuRouted(stage.config(), rt.config.executorMode());

        while (!rt.terminated.get() && !Thread.currentThread().isInterrupted()) {
            if (!pool.acquire()) {
                break;
            }
            try {
                Object raw = inChannel.receive();
                if (raw == null) {
                    pool.onEof();
                    break;
                }
                Object payload = ContextSupport.unwrapPayload(raw);
                StageExecutionPolicy.ItemOutcome outcome = rt.executionPolicy.executeWithTelemetry(
                        stageName,
                        stage.config(),
                        telemetry,
                        payload,
                        zeroBlocked,
                        cpuRouted,
                        () -> rt.context.withContext(raw, () -> {
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
                if (outcome == StageExecutionPolicy.ItemOutcome.SUCCESS) {
                    rt.itemsConsumed.incrementAndGet();
                }
            } finally {
                pool.release();
            }
        }
    }

    @SuppressWarnings("unchecked")
    static void runAuxSinkWorker(
            PipelineRuntime<?> rt,
            String auxName,
            StageConfig auxConfig,
            PipeSink<?> rawSink,
            BoundedChannel<Object> auxChannel,
            StageTelemetry telemetry) throws Exception {
        PipeSink<Object> sink = (PipeSink<Object>) rawSink;
        long[] zeroBlocked = new long[1];
        boolean cpuRouted = StageExecutionPolicy.isCpuRouted(auxConfig, rt.config.executorMode());

        while (!rt.terminated.get() && !Thread.currentThread().isInterrupted()) {
            Object received = auxChannel.receive();
            if (received == null) {
                break;
            }
            BroadcastItem broadcastItem = received instanceof BroadcastItem bi ? bi : null;
            Object raw = broadcastItem != null ? broadcastItem.raw() : received;
            Object payload = ContextSupport.unwrapPayload(raw);
            StageExecutionPolicy.ItemOutcome outcome = rt.executionPolicy.executeWithTelemetry(
                    auxName,
                    auxConfig,
                    telemetry,
                    payload,
                    zeroBlocked,
                    cpuRouted,
                    () -> rt.context.withContext(raw, () -> sink.consume(payload)));
            if (broadcastItem != null) {
                if (outcome == StageExecutionPolicy.ItemOutcome.SKIPPED) {
                    broadcastItem.failed().set(true);
                }
                if (broadcastItem.remaining().decrementAndGet() == 0 && !broadcastItem.failed().get()) {
                    rt.itemsConsumed.incrementAndGet();
                }
            }
        }
    }
}
