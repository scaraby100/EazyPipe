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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import xyz.scarabya.eazypipe.adaptive.StageTelemetry;
import xyz.scarabya.eazypipe.metrics.PipelineMetricsListener;

/**
 * Per-item stage execution policy handling CPU routing, retry, skip, dead-letter, and channel send overflow metrics.
 */
final class StageExecutionPolicy {

    enum ItemOutcome {
        SUCCESS,
        SKIPPED
    }

    private final PipeConfig config;
    private final CpuWorkerPool cpuPool;
    private final PipelineMetricsListener metricsListener;
    private final AtomicLong errorsSkipped;

    StageExecutionPolicy(
            PipeConfig config,
            CpuWorkerPool cpuPool,
            PipelineMetricsListener metricsListener,
            AtomicLong errorsSkipped) {
        this.config = config;
        this.cpuPool = cpuPool;
        this.metricsListener = metricsListener;
        this.errorsSkipped = errorsSkipped;
    }

    static boolean isCpuRouted(StageConfig stageConfig, PipeConfig.ExecutorMode mode) {
        return stageConfig.resolveEffectiveKind(mode) == StageKind.CPU_BOUND
                && !(mode == PipeConfig.ExecutorMode.CUSTOM && stageConfig.kind() == StageKind.INHERIT);
    }

    ItemOutcome executeWithTelemetry(
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

    BoundedChannel.SendResult sendToChannel(
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

    BoundedChannel.SendResult trySendToChannel(
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
}
