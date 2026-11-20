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
 * Lock-based pacing rate limiter without virtual-thread pinning and with immediate abort wakeup.
 */
final class TokenBucketRateLimiter {

    private final long intervalNanos;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition slotAvailable = lock.newCondition();

    private long nextFreeNanos;
    private boolean aborted;
    private Throwable abortCause;

    TokenBucketRateLimiter(double permitsPerSecond) {
        if (Double.isNaN(permitsPerSecond) || Double.isInfinite(permitsPerSecond) || permitsPerSecond <= 0.0) {
            throw new IllegalArgumentException("permitsPerSecond must be positive and finite, got " + permitsPerSecond);
        }
        this.intervalNanos = Math.max(1L, Math.round(1_000_000_000.0 / permitsPerSecond));
        this.nextFreeNanos = System.nanoTime();
    }

    void acquire() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (!aborted) {
                long now = System.nanoTime();
                long waitNanos = nextFreeNanos - now;
                if (waitNanos <= 0L) {
                    nextFreeNanos = now + intervalNanos;
                    return;
                }
                slotAvailable.awaitNanos(waitNanos);
            }
            throw new PipeAbortedException(abortCause);
        } finally {
            lock.unlock();
        }
    }

    void abort(Throwable cause) {
        lock.lock();
        try {
            if (!aborted) {
                aborted = true;
                abortCause = cause;
                slotAvailable.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }
}
