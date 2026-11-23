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

import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Planned worker task scheduled onto {@link StageScheduler} and tracked across its lifecycle.
 */
final class WorkerTask implements Runnable {

    static final int STATE_PENDING = 0;
    static final int STATE_RUNNING = 1;
    static final int STATE_DONE = 2;

    @FunctionalInterface
    interface RuntimeTaskBody {
        void run(PipelineRuntime<?> runtime) throws Exception;
    }

    final int stageIndex;
    final String stageName;
    final StageKind stageKind;
    final boolean isSource;
    final RuntimeTaskBody body;
    final Consumer<PipelineRuntime<?>> customStageEnd;
    final AtomicInteger state = new AtomicInteger(STATE_PENDING);
    volatile Future<?> future;
    private PipelineRuntime<?> runtime;

    WorkerTask(
            int stageIndex,
            String stageName,
            StageKind stageKind,
            boolean isSource,
            RuntimeTaskBody body) {
        this(stageIndex, stageName, stageKind, isSource, body, null);
    }

    WorkerTask(
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

    void bind(PipelineRuntime<?> runtime) {
        this.runtime = runtime;
    }

    void finishOnCancel() {
        runtime.lifecycle.onWorkerEnded(this);
    }

    @Override
    public void run() {
        if (!state.compareAndSet(STATE_PENDING, STATE_RUNNING)) {
            return;
        }
        ContextPropagator.Scope scope = runtime.context.attachLaunchScope();
        try {
            body.run(runtime);
        } catch (PipeAbortedException ignored) {
            // Channel/pool aborted due to concurrent failure or cancellation.
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (!runtime.terminated.get() && !(isSource && runtime.draining.get())) {
                runtime.lifecycle.fail(stageName, ex);
            }
        } catch (Throwable t) {
            if (!runtime.terminated.get() || !(t instanceof CancellationException)) {
                runtime.lifecycle.fail(stageName, t);
            }
        } finally {
            try {
                scope.close();
            } finally {
                state.set(STATE_DONE);
                runtime.lifecycle.onWorkerEnded(this);
                // Clear interrupt status before returning pooled custom executor threads.
                Thread.interrupted();
            }
        }
    }
}
