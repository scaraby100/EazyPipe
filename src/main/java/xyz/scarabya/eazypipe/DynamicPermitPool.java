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

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Elastic concurrency permit pool allowing live resizing of active stage workers
 * without creating or destroying threads on every scaling tick.
 *
 * <p>Uses {@link ReentrantLock} + {@link Condition} to avoid virtual-thread pinning.
 * Reductions are applied lazily: active workers finish their current item and simply
 * park on the next {@link #acquire()} until {@code inFlight < targetPermits}.
 */
final class DynamicPermitPool {

    private final int minPermits;
    private final int maxPermits;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition permitAvailable = lock.newCondition();

    private int targetPermits;
    private int inFlight;
    private boolean eof;
    private boolean aborted;
    private Throwable abortCause;

    DynamicPermitPool(int minPermits, int initialPermits, int maxPermits) {
        if (minPermits < 1 || initialPermits < minPermits || maxPermits < initialPermits) {
            throw new IllegalArgumentException(
                    "Invalid permit bounds: min=" + minPermits
                            + ", initial=" + initialPermits
                            + ", max=" + maxPermits);
        }
        this.minPermits = minPermits;
        this.maxPermits = maxPermits;
        this.targetPermits = initialPermits;
    }

    /**
     * Acquires one concurrency permit before pulling/processing an item.
     *
     * @return {@code true} if a permit was granted, or {@code false} if EOF was reached while waiting
     * @throws PipeAbortedException if the pool has been aborted
     * @throws InterruptedException if interrupted while parked waiting for a permit
     */
    boolean acquire() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (inFlight >= targetPermits && !eof && !aborted) {
                permitAvailable.await();
            }
            if (aborted) {
                throw new PipeAbortedException(abortCause);
            }
            if (eof) {
                return false;
            }
            inFlight++;
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Releases a previously acquired permit and signals a waiting worker if capacity is available.
     */
    void release() {
        lock.lock();
        try {
            if (inFlight > 0) {
                inFlight--;
            }
            if (!eof && inFlight < targetPermits) {
                permitAvailable.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Resizes the concurrency target clamped to {@code [minPermits, maxPermits]}.
     */
    void setTarget(int newTarget) {
        int clamped = Math.max(minPermits, Math.min(maxPermits, newTarget));
        lock.lock();
        try {
            if (clamped != targetPermits) {
                boolean grew = clamped > targetPermits;
                targetPermits = clamped;
                if (grew) {
                    permitAvailable.signalAll();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Marks upstream EOF so any workers parked waiting for permits wake up and exit cleanly.
     */
    void onEof() {
        lock.lock();
        try {
            if (!eof) {
                eof = true;
                permitAvailable.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Aborts the pool immediately and wakes all parked workers with {@link PipeAbortedException}.
     */
    void abort(Throwable cause) {
        lock.lock();
        try {
            if (!aborted) {
                aborted = true;
                abortCause = cause;
                permitAvailable.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    int targetPermits() {
        lock.lock();
        try {
            return targetPermits;
        } finally {
            lock.unlock();
        }
    }

    int inFlight() {
        lock.lock();
        try {
            return inFlight;
        } finally {
            lock.unlock();
        }
    }

    int minPermits() {
        return minPermits;
    }

    int maxPermits() {
        return maxPermits;
    }
}
