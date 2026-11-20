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

/**
 * Hook allowing callers to propagate thread-bound context (such as MDC, tracing spans, or tenant ids)
 * from the thread launching the pipeline into stage workers and across inter-stage channels.
 */
@FunctionalInterface
public interface ContextPropagator {

    /**
     * Captures the current thread's context into an immutable snapshot.
     *
     * @return captured snapshot (must not be {@code null})
     */
    ContextSnapshot capture();

    /**
     * Captured context snapshot ready to be attached to a worker thread.
     */
    @FunctionalInterface
    interface ContextSnapshot {

        /**
         * Installs this snapshot onto the current worker thread and returns a {@link Scope}
         * restoring the previous context on {@link Scope#close()}.
         *
         * @return scope guard
         */
        Scope attach();
    }

    /**
     * Restores the worker thread's previous context when closed.
     */
    @FunctionalInterface
    interface Scope extends AutoCloseable {

        @Override
        void close();
    }

    /**
     * Returns a no-op {@link ContextPropagator}.
     *
     * @return no-op propagator
     */
    static ContextPropagator noop() {
        final Scope emptyScope = () -> { };
        final ContextSnapshot emptySnapshot = () -> emptyScope;
        return () -> emptySnapshot;
    }
}
