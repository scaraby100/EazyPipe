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

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded, thread-safe FIFO channel with backpressure, clean EOF, and immediate abort support.
 *
 * <p>Implemented on top of {@link ReentrantLock} and {@link Condition} rather than {@code synchronized}
 * to avoid pinning Java 21 virtual threads while waiting on a full or empty buffer.
 *
 * @param <T> element type carried by this channel
 */
final class BoundedChannel<T> {

    private final int capacity;
    private final ArrayDeque<T> buffer;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();

    private boolean closed;
    private boolean aborted;
    private Throwable abortCause;

    BoundedChannel(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
        }
        this.capacity = capacity;
        this.buffer = new ArrayDeque<>(Math.min(capacity, 1024));
    }

    /**
     * Enqueues a non-null item, blocking if the buffer is at capacity.
     *
     * @throws PipeAbortedException if the channel has been aborted before or while waiting
     * @throws IllegalStateException if the channel was already closed
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    void send(T item) throws InterruptedException {
        Objects.requireNonNull(item, "Null items are not allowed in pipeline channels");
        lock.lockInterruptibly();
        try {
            while (buffer.size() == capacity && !aborted && !closed) {
                notFull.await();
            }
            if (aborted) {
                throw new PipeAbortedException(abortCause);
            }
            if (closed) {
                throw new IllegalStateException("Cannot send to a closed channel");
            }
            buffer.addLast(item);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Dequeues the next item, blocking while the buffer is empty and open.
     *
     * @return the next item, or {@code null} when the channel is closed and fully drained
     * @throws PipeAbortedException if the channel has been aborted before or while waiting
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    T receive() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (buffer.isEmpty() && !closed && !aborted) {
                notEmpty.await();
            }
            if (aborted) {
                throw new PipeAbortedException(abortCause);
            }
            if (buffer.isEmpty()) {
                return null;
            }
            T item = buffer.removeFirst();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Marks the channel as closed so pending items can still be drained, then wakes all waiters.
     */
    void close() {
        lock.lock();
        try {
            if (!closed) {
                closed = true;
                notEmpty.signalAll();
                notFull.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Immediately aborts the channel, discards buffered items, and wakes all blocked senders and receivers.
     */
    void abort(Throwable cause) {
        lock.lock();
        try {
            if (!aborted) {
                aborted = true;
                abortCause = cause;
                buffer.clear();
                notEmpty.signalAll();
                notFull.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    int capacity() {
        return capacity;
    }
}
