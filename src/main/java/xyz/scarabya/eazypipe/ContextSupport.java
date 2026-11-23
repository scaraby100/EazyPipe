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

import java.util.Objects;

/**
 * Thread-context capture and attachment utilities across pipeline channels and worker boundaries.
 */
final class ContextSupport {

    static final ContextPropagator.Scope NOOP_SCOPE = () -> { };

    private record ContextEnvelope(Object payload, ContextPropagator.ContextSnapshot snapshot) { }

    private final ContextPropagator contextPropagator;
    private final ContextPropagator.ContextSnapshot launchContext;

    ContextSupport(ContextPropagator contextPropagator, ContextPropagator.ContextSnapshot launchContext) {
        this.contextPropagator = contextPropagator;
        this.launchContext = launchContext;
    }

    Object wrapWithCurrentContext(Object payload) {
        if (contextPropagator == null) {
            return payload;
        }
        ContextPropagator.ContextSnapshot snap = Objects.requireNonNull(
                contextPropagator.capture(), "ContextPropagator.capture() must not return null");
        return new ContextEnvelope(payload, snap);
    }

    static Object unwrapPayload(Object raw) {
        return raw instanceof ContextEnvelope env ? env.payload() : raw;
    }

    ContextPropagator.Scope attachLaunchScope() {
        return launchContext != null ? launchContext.attach() : NOOP_SCOPE;
    }

    ContextPropagator.Scope attachForRaw(Object raw) {
        if (raw instanceof ContextEnvelope env) {
            return env.snapshot().attach();
        }
        return attachLaunchScope();
    }

    void withContext(Object raw, ThrowingRunnable action) throws Exception {
        ContextPropagator.Scope scope = attachForRaw(raw);
        try {
            action.run();
        } finally {
            scope.close();
        }
    }

    void withLaunchContext(Runnable action) {
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
}
