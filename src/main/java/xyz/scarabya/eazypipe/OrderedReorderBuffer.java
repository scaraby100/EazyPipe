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

import java.util.TreeMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded sequence-ordered emission buffer for {@code mapConcurrentOrdered} stages.
 */
final class OrderedReorderBuffer {

    @FunctionalInterface
    interface EmitAction {
        void emit(Object wrappedItem) throws InterruptedException;
    }

    static final Object TOMBSTONE = new Object();

    private final int maxWindow;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition windowAvailable = lock.newCondition();
    private final TreeMap<Long, Object> pending = new TreeMap<>();

    private long nextEmitSequence;
    private boolean emitting;
    private boolean aborted;
    private Throwable abortCause;

    OrderedReorderBuffer(int maxWindow) {
        this.maxWindow = maxWindow;
    }

    void complete(long seq, Object valueOrTombstone, EmitAction emitter)
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

    void abort(Throwable cause) {
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
