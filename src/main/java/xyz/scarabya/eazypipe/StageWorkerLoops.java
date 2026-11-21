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
import java.util.Objects;
import xyz.scarabya.eazypipe.internal.adaptive.StageTelemetry;

/**
 * Worker execution loops for source and intermediate pipeline stages.
 */
final class StageWorkerLoops {

    private StageWorkerLoops() {
    }

    @SuppressWarnings("unchecked")
    static void runSourceWorker(
            PipelineRuntime<?> rt,
            int stageIndex,
            PipeSource<?> rawSource) throws Exception {
        PipeSource<Object> source = (PipeSource<Object>) rawSource;
        BoundedChannel<Object> outChannel = rt.channels.get(stageIndex);
        StageTelemetry telemetry = rt.stageTelemetries.get(stageIndex);
        String downstreamName = rt.stageNames.get(stageIndex + 1);
        OverflowPolicy downstreamPolicy = rt.stages.get(stageIndex + 1).config().overflowPolicy();

        PipeEmitter<Object> emitter = item -> {
            Object wrapped = rt.context.wrapWithCurrentContext(item);
            long t0 = System.nanoTime();
            telemetry.onProcessingStart();
            try {
                BoundedChannel.SendResult res =
                        rt.executionPolicy.sendToChannel(outChannel, wrapped, downstreamName, downstreamPolicy);
                if (res.status() != BoundedChannel.SendStatus.DROPPED_NEWEST) {
                    rt.itemsProduced.incrementAndGet();
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
            if (rt.draining.get() && !rt.terminated.get()) {
                Thread.interrupted(); // Clear flag before worker exit.
                return;
            }
            throw ex;
        }
    }

    @SuppressWarnings("unchecked")
    static void runProcessorWorker(
            PipelineRuntime<?> rt,
            int stageIndex,
            String stageName,
            StageDefinition.ProcessorStage<?, ?> stage) throws Exception {
        BoundedChannel<Object> inChannel = rt.channels.get(stageIndex - 1);
        BoundedChannel<Object> outChannel = rt.channels.get(stageIndex);
        DynamicPermitPool pool = rt.stagePermitPools.get(stageIndex);
        StageTelemetry telemetry = rt.stageTelemetries.get(stageIndex);
        String downstreamName = rt.stageNames.get(stageIndex + 1);
        OverflowPolicy downstreamPolicy = rt.stages.get(stageIndex + 1).config().overflowPolicy();
        PipeProcessor<Object, Object> processor = (PipeProcessor<Object, Object>) stage.processor();
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
                long[] blockedNanos = new long[1];
                if (cpuRouted) {
                    List<Object> bufferedEmissions = new ArrayList<>();
                    PipeEmitter<Object> emitter = outItem ->
                            bufferedEmissions.add(rt.context.wrapWithCurrentContext(outItem));
                    StageExecutionPolicy.ItemOutcome outcome = rt.executionPolicy.executeWithTelemetry(
                            stageName,
                            stage.config(),
                            telemetry,
                            payload,
                            blockedNanos,
                            true,
                            () -> {
                                bufferedEmissions.clear();
                                rt.context.withContext(raw, () -> processor.process(payload, emitter));
                            });
                    if (outcome == StageExecutionPolicy.ItemOutcome.SUCCESS) {
                        for (Object wrappedOut : bufferedEmissions) {
                            rt.executionPolicy.sendToChannel(outChannel, wrappedOut, downstreamName, downstreamPolicy);
                        }
                    }
                } else {
                    PipeEmitter<Object> emitter = outItem -> {
                        Object wrappedOut = rt.context.wrapWithCurrentContext(outItem);
                        BoundedChannel.SendResult res = rt.executionPolicy.sendToChannel(
                                outChannel, wrappedOut, downstreamName, downstreamPolicy);
                        blockedNanos[0] += res.blockedNanos();
                    };
                    rt.executionPolicy.executeWithTelemetry(
                            stageName,
                            stage.config(),
                            telemetry,
                            payload,
                            blockedNanos,
                            false,
                            () -> rt.context.withContext(raw, () -> processor.process(payload, emitter)));
                }
            } finally {
                pool.release();
            }
        }
    }

    @SuppressWarnings("unchecked")
    static void runOrderedMapWorker(
            PipelineRuntime<?> rt,
            int stageIndex,
            String stageName,
            StageDefinition.OrderedMapStage<?, ?> stage,
            OrderedReorderBuffer reorderBuffer) throws Exception {
        BoundedChannel<Object> inChannel = rt.channels.get(stageIndex - 1);
        BoundedChannel<Object> outChannel = rt.channels.get(stageIndex);
        DynamicPermitPool pool = rt.stagePermitPools.get(stageIndex);
        StageTelemetry telemetry = rt.stageTelemetries.get(stageIndex);
        String downstreamName = rt.stageNames.get(stageIndex + 1);
        OverflowPolicy downstreamPolicy = rt.stages.get(stageIndex + 1).config().overflowPolicy();
        PipeFunction<Object, Object> mapper = (PipeFunction<Object, Object>) stage.mapper();
        boolean cpuRouted = StageExecutionPolicy.isCpuRouted(stage.config(), rt.config.executorMode());

        while (!rt.terminated.get() && !Thread.currentThread().isInterrupted()) {
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
                Object payload = ContextSupport.unwrapPayload(raw);
                Object[] resultHolder = new Object[1];
                long[] zeroBlocked = new long[1];

                StageExecutionPolicy.ItemOutcome outcome = rt.executionPolicy.executeWithTelemetry(
                        stageName,
                        stage.config(),
                        telemetry,
                        payload,
                        zeroBlocked,
                        cpuRouted,
                        () -> rt.context.withContext(raw, () -> {
                            Object mapped = Objects.requireNonNull(
                                    mapper.apply(payload), "Mapper function must not return null");
                            resultHolder[0] = rt.context.wrapWithCurrentContext(mapped);
                        }));

                Object toSubmit = outcome == StageExecutionPolicy.ItemOutcome.SUCCESS
                        ? resultHolder[0]
                        : OrderedReorderBuffer.TOMBSTONE;
                reorderBuffer.complete(seqItem.sequence(), toSubmit, wrappedOut ->
                        rt.executionPolicy.sendToChannel(outChannel, wrappedOut, downstreamName, downstreamPolicy));
            } finally {
                pool.release();
            }
        }
    }

    static void runBatchWorker(
            PipelineRuntime<?> rt,
            int stageIndex,
            StageDefinition.BatchStage<?> stage) throws Exception {
        BoundedChannel<Object> inChannel = rt.channels.get(stageIndex - 1);
        BoundedChannel<Object> outChannel = rt.channels.get(stageIndex);
        DynamicPermitPool pool = rt.stagePermitPools.get(stageIndex);
        StageTelemetry telemetry = rt.stageTelemetries.get(stageIndex);
        String downstreamName = rt.stageNames.get(stageIndex + 1);
        OverflowPolicy downstreamPolicy = rt.stages.get(stageIndex + 1).config().overflowPolicy();
        long maxWaitNanos = stage.maxWait().toNanos();

        while (!rt.terminated.get() && !Thread.currentThread().isInterrupted()) {
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
                try {
                    List<Object> payloads = new ArrayList<>(rawBatch.size());
                    for (Object raw : rawBatch) {
                        payloads.add(ContextSupport.unwrapPayload(raw));
                    }
                    Object wrappedBatch = rt.context.wrapWithCurrentContext(List.copyOf(payloads));
                    BoundedChannel.SendResult res = rt.executionPolicy.sendToChannel(
                            outChannel, wrappedBatch, downstreamName, downstreamPolicy);
                    telemetry.onProcessingSuccess(Math.max(0L, (System.nanoTime() - t0) - res.blockedNanos()));
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

    static void runRateLimitWorker(
            PipelineRuntime<?> rt,
            int stageIndex,
            TokenBucketRateLimiter limiter) throws Exception {
        BoundedChannel<Object> inChannel = rt.channels.get(stageIndex - 1);
        BoundedChannel<Object> outChannel = rt.channels.get(stageIndex);
        DynamicPermitPool pool = rt.stagePermitPools.get(stageIndex);
        StageTelemetry telemetry = rt.stageTelemetries.get(stageIndex);
        String downstreamName = rt.stageNames.get(stageIndex + 1);
        OverflowPolicy downstreamPolicy = rt.stages.get(stageIndex + 1).config().overflowPolicy();

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
                telemetry.onProcessingStart();
                long t0 = System.nanoTime();
                try {
                    limiter.acquire();
                    BoundedChannel.SendResult res = rt.executionPolicy.sendToChannel(
                            outChannel, raw, downstreamName, downstreamPolicy);
                    telemetry.onProcessingSuccess(Math.max(0L, (System.nanoTime() - t0) - res.blockedNanos()));
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

    static void runTeeMainWorker(
            PipelineRuntime<?> rt,
            int stageIndex,
            String stageName,
            StageDefinition.TeeStage<?> stage,
            BoundedChannel<Object> sideChannel) throws Exception {
        BoundedChannel<Object> inChannel = rt.channels.get(stageIndex - 1);
        BoundedChannel<Object> outChannel = rt.channels.get(stageIndex);
        DynamicPermitPool pool = rt.stagePermitPools.get(stageIndex);
        StageTelemetry telemetry = rt.stageTelemetries.get(stageIndex);
        String downstreamName = rt.stageNames.get(stageIndex + 1);
        OverflowPolicy downstreamPolicy = rt.stages.get(stageIndex + 1).config().overflowPolicy();
        String sideName = stage.sideConfig().resolveName(stageName + "-tee");
        OverflowPolicy sidePolicy = stage.sideConfig().overflowPolicy();

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
                telemetry.onProcessingStart();
                long t0 = System.nanoTime();
                try {
                    BoundedChannel.SendResult sideRes =
                            rt.executionPolicy.sendToChannel(sideChannel, raw, sideName, sidePolicy);
                    BoundedChannel.SendResult mainRes =
                            rt.executionPolicy.sendToChannel(outChannel, raw, downstreamName, downstreamPolicy);
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
}
