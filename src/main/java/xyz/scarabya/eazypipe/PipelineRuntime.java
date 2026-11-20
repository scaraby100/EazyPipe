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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Internal execution coordinator for a single pipeline run.
 */
final class PipelineRuntime<R> {

    private static final int STATE_PENDING = 0;
    private static final int STATE_RUNNING = 1;
    private static final int STATE_DONE = 2;

    private final PipeConfig config;
    private final List<StageDefinition> stages;
    private final List<BoundedChannel<Object>> channels;
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    private final AtomicInteger[] stageRemainingWorkers;
    private final AtomicInteger totalRemainingWorkers;
    private final List<WorkerTask> submittedTasks = new ArrayList<>();
    private final Object workersLock = new Object();
    private final Object completionLock = new Object();

    private final AtomicBoolean terminated = new AtomicBoolean(false);
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
            List<BoundedChannel<Object>> channels,
            ExecutorService executor,
            boolean ownsExecutor,
            AtomicInteger[] stageRemainingWorkers,
            int totalWorkers) {
        this.config = config;
        this.stages = stages;
        this.channels = channels;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
        this.stageRemainingWorkers = stageRemainingWorkers;
        this.totalRemainingWorkers = new AtomicInteger(totalWorkers);
    }

    static <R> PipeExecution<R> launch(
            PipeConfig config,
            StageDefinition.SourceStage<?> sourceStage,
            List<StageDefinition.ProcessorStage<?, ?>> processorStages,
            StageDefinition.SinkStage<?, R> sinkStage) {

        List<StageDefinition> stages = new ArrayList<>(2 + processorStages.size());
        stages.add(sourceStage);
        stages.addAll(processorStages);
        stages.add(sinkStage);

        List<BoundedChannel<Object>> channels = new ArrayList<>(stages.size() - 1);
        for (int i = 0; i < stages.size() - 1; i++) {
            StageConfig producerConfig = stages.get(i).config();
            StageConfig consumerConfig = stages.get(i + 1).config();
            int capacity = consumerConfig.bufferCapacity() > 0
                    ? consumerConfig.bufferCapacity()
                    : producerConfig.resolveBufferCapacity(config.defaultBufferCapacity());
            channels.add(new BoundedChannel<>(capacity));
        }

        ExecutorService executor;
        boolean ownsExecutor;
        switch (config.executorMode()) {
            case VIRTUAL_THREADS -> {
                executor = Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("eazypipe-vt-", 0).factory());
                ownsExecutor = true;
            }
            case PLATFORM_THREADS -> {
                executor = Executors.newThreadPerTaskExecutor(
                        Thread.ofPlatform().name("eazypipe-worker-", 0).daemon(true).factory());
                ownsExecutor = true;
            }
            case CUSTOM -> {
                executor = config.customExecutor();
                ownsExecutor = false;
            }
            default -> throw new IllegalStateException("Unsupported executorMode: " + config.executorMode());
        }

        AtomicInteger[] stageRemaining = new AtomicInteger[stages.size()];
        int totalWorkers = 0;
        for (int i = 0; i < stages.size(); i++) {
            int parallelism = stages.get(i).config().parallelism();
            stageRemaining[i] = new AtomicInteger(parallelism);
            totalWorkers += parallelism;
        }

        PipelineRuntime<R> runtime = new PipelineRuntime<>(
                config,
                List.copyOf(stages),
                List.copyOf(channels),
                executor,
                ownsExecutor,
                stageRemaining,
                totalWorkers);
        runtime.startAllWorkers();
        return new PipeExecution<>(runtime);
    }

    private void startAllWorkers() {
        List<WorkerTask> tasks = new ArrayList<>();
        for (int stageIndex = 0; stageIndex < stages.size(); stageIndex++) {
            StageDefinition stage = stages.get(stageIndex);
            String stageName = resolveStageName(stageIndex, stage);
            int parallelism = stage.config().parallelism();
            for (int w = 0; w < parallelism; w++) {
                tasks.add(new WorkerTask(stageIndex, stageName, stage));
            }
        }

        for (WorkerTask task : tasks) {
            if (terminated.get()) {
                if (task.state.compareAndSet(STATE_PENDING, STATE_DONE)) {
                    onWorkerEnded(task.stageIndex, task.stageName, task.stage);
                }
                continue;
            }
            try {
                Future<?> future = executor.submit(task);
                boolean shouldCancelNow;
                synchronized (workersLock) {
                    task.future = future;
                    submittedTasks.add(task);
                    shouldCancelNow = terminated.get();
                }
                if (shouldCancelNow) {
                    cancelTask(task);
                }
            } catch (RejectedExecutionException rex) {
                fail(task.stageName, rex);
                if (task.state.compareAndSet(STATE_PENDING, STATE_DONE)) {
                    onWorkerEnded(task.stageIndex, task.stageName, task.stage);
                }
            }
        }
    }

    private String resolveStageName(int stageIndex, StageDefinition stage) {
        String defaultName = switch (stage) {
            case StageDefinition.SourceStage<?> ignored -> "source";
            case StageDefinition.ProcessorStage<?, ?> ignored -> "stage-" + stageIndex;
            case StageDefinition.SinkStage<?, ?> ignored -> "sink";
        };
        return stage.config().resolveName(defaultName);
    }

    private void runStageWorker(int stageIndex, String stageName, StageDefinition stage)
            throws Exception {
        switch (stage) {
            case StageDefinition.SourceStage<?> sourceStage ->
                    runSourceWorker(sourceStage, channels.get(stageIndex));
            case StageDefinition.ProcessorStage<?, ?> processorStage ->
                    runProcessorWorker(
                            stageName,
                            processorStage,
                            channels.get(stageIndex - 1),
                            channels.get(stageIndex));
            case StageDefinition.SinkStage<?, ?> sinkStage ->
                    runSinkWorker(stageName, sinkStage, channels.get(stageIndex - 1));
        }
    }

    @SuppressWarnings("unchecked")
    private void runSourceWorker(
            StageDefinition.SourceStage<?> sourceStage,
            BoundedChannel<Object> outChannel) throws Exception {
        PipeSource<Object> source = (PipeSource<Object>) sourceStage.source();
        PipeEmitter<Object> emitter = item -> {
            outChannel.send(item);
            itemsProduced.incrementAndGet();
        };
        source.produce(emitter);
    }

    @SuppressWarnings("unchecked")
    private void runProcessorWorker(
            String stageName,
            StageDefinition.ProcessorStage<?, ?> processorStage,
            BoundedChannel<Object> inChannel,
            BoundedChannel<Object> outChannel) throws Exception {
        PipeProcessor<Object, Object> processor = (PipeProcessor<Object, Object>) processorStage.processor();
        PipeEmitter<Object> emitter = outChannel::send;
        while (!terminated.get() && !Thread.currentThread().isInterrupted()) {
            Object item = inChannel.receive();
            if (item == null) {
                break;
            }
            try {
                processor.process(item, emitter);
            } catch (PipeAbortedException | InterruptedException ex) {
                throw ex;
            } catch (Exception ex) {
                if (config.errorStrategy() == PipeConfig.ErrorStrategy.CONTINUE_ON_ITEM_ERROR) {
                    handleSkippedError(stageName, ex);
                } else {
                    throw ex;
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void runSinkWorker(
            String stageName,
            StageDefinition.SinkStage<?, ?> sinkStage,
            BoundedChannel<Object> inChannel) throws Exception {
        PipeSink<Object> sink = (PipeSink<Object>) sinkStage.sink();
        while (!terminated.get() && !Thread.currentThread().isInterrupted()) {
            Object item = inChannel.receive();
            if (item == null) {
                break;
            }
            try {
                sink.consume(item);
                itemsConsumed.incrementAndGet();
            } catch (PipeAbortedException | InterruptedException ex) {
                throw ex;
            } catch (Exception ex) {
                if (config.errorStrategy() == PipeConfig.ErrorStrategy.CONTINUE_ON_ITEM_ERROR) {
                    handleSkippedError(stageName, ex);
                } else {
                    throw ex;
                }
            }
        }
    }

    private void handleSkippedError(String stageName, Exception ex) {
        errorsSkipped.incrementAndGet();
        BiConsumer<String, Throwable> handler = config.errorHandler();
        if (handler != null) {
            handler.accept(stageName, ex);
        }
    }

    private void onWorkerEnded(int stageIndex, String stageName, StageDefinition stage) {
        if (stageRemainingWorkers[stageIndex].decrementAndGet() == 0) {
            finishStage(stageIndex, stageName, stage);
        }
        if (totalRemainingWorkers.decrementAndGet() == 0) {
            finalizeRuntime();
        }
    }

    @SuppressWarnings("unchecked")
    private void finishStage(int stageIndex, String stageName, StageDefinition stage) {
        try {
            if (!terminated.get()) {
                Runnable onComplete = stage.config().onComplete();
                if (onComplete != null) {
                    onComplete.run();
                }
                if (stage instanceof StageDefinition.SinkStage<?, ?> sinkStage) {
                    R result = ((StageDefinition.SinkStage<?, R>) sinkStage).resultSupplier().get();
                    resultRef.set(result);
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
        synchronized (completionLock) {
            endNanos.compareAndSet(-1L, System.nanoTime());
            Throwable failure = firstFailure.get();
            if (failure != null) {
                completionFuture.completeExceptionally(failure);
            } else {
                completionFuture.complete(resultRef.get());
            }
        }
        if (ownsExecutor) {
            executor.shutdown();
        }
        allWorkersDone.countDown();
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
            abortAllChannels(wrapped);
            cancelAllWorkers();
        } else {
            Throwable primary = firstFailure.get();
            if (primary != null
                    && primary != wrapped
                    && !(error instanceof PipeAbortedException)
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
            return new PipeException(stageName, "Stage '" + stageName + "' threw an Error: " + err.getMessage(), err);
        }
        String message = error.getMessage();
        String detail = (message == null || message.isBlank())
                ? "Stage '" + stageName + "' failed with " + error.getClass().getSimpleName()
                : "Stage '" + stageName + "' failed: " + message;
        return new PipeException(stageName, detail, error);
    }

    private void abortAllChannels(Throwable cause) {
        for (BoundedChannel<Object> channel : channels) {
            channel.abort(cause);
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
            onWorkerEnded(task.stageIndex, task.stageName, task.stage);
        } else if (task.state.get() == STATE_RUNNING) {
            Future<?> f = task.future;
            if (f != null) {
                f.cancel(true);
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
            abortAllChannels(cancelEx);
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
        long end = endNanos.get();
        long elapsedNanos = (end >= 0L ? end : System.nanoTime()) - startNanos;
        return new PipeStats(
                Duration.ofNanos(Math.max(0L, elapsedNanos)),
                itemsProduced.get(),
                itemsConsumed.get(),
                errorsSkipped.get(),
                stages.size());
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

    private final class WorkerTask implements Runnable {

        private final int stageIndex;
        private final String stageName;
        private final StageDefinition stage;
        private final AtomicInteger state = new AtomicInteger(STATE_PENDING);
        private volatile Future<?> future;

        private WorkerTask(int stageIndex, String stageName, StageDefinition stage) {
            this.stageIndex = stageIndex;
            this.stageName = stageName;
            this.stage = stage;
        }

        @Override
        public void run() {
            if (!state.compareAndSet(STATE_PENDING, STATE_RUNNING)) {
                return;
            }
            try {
                runStageWorker(stageIndex, stageName, stage);
            } catch (PipeAbortedException ignored) {
                // Channel aborted due to upstream/downstream failure or cancellation.
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                if (!terminated.get()) {
                    fail(stageName, ex);
                }
            } catch (Throwable t) {
                if (!terminated.get() || !(t instanceof CancellationException)) {
                    fail(stageName, t);
                }
            } finally {
                state.set(STATE_DONE);
                onWorkerEnded(stageIndex, stageName, stage);
                // Clear interrupt status before returning pooled custom threads.
                Thread.interrupted();
            }
        }
    }
}
