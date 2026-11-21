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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded, thread-safe FIFO channel with configurable {@link OverflowPolicy}, clean EOF,
 * batch draining, monotonic receive sequencing, backpressure wait telemetry, and immediate abort/drain support.
 *
 * <p>Implemented on top of {@link ReentrantLock} and {@link Condition} rather than {@code synchronized}
 * to avoid pinning Java 21 virtual threads while waiting on a full or empty buffer.
 *
 * @param <T> element type carried by this channel
 */
final class BoundedChannel<T> {

    enum SendStatus {
        ENQUEUED,
        DROPPED_NEWEST,
        DROPPED_OLDEST
    }

    record SendResult(SendStatus status, long blockedNanos) { }

    record SequencedItem<T>(long sequence, T item) { }

    private final int capacity;
    private final OverflowPolicy overflowPolicy;
    private final ArrayDeque<T> buffer;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();

    private final AtomicInteger currentSize = new AtomicInteger();
    private final LongAdder droppedCount = new LongAdder();
    private final LongAdder sendWaitNanos = new LongAdder();
    private final LongAdder receiveWaitNanos = new LongAdder();

    private long nextReceiveSequence;
    private boolean closed;
    private boolean aborted;
    private boolean draining;
    private Throwable abortCause;

    BoundedChannel(int capacity) {
        this(capacity, OverflowPolicy.BLOCK);
    }

    BoundedChannel(int capacity, OverflowPolicy overflowPolicy) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
        }
        this.capacity = capacity;
        this.overflowPolicy = Objects.requireNonNull(overflowPolicy, "overflowPolicy must not be null");
        this.buffer = new ArrayDeque<>(Math.min(capacity, 1024));
    }

    /**
     * Enqueues a non-null item applying this channel's {@link OverflowPolicy}.
     *
     * @return {@code true} if the item was placed in the buffer, {@code false} if dropped via {@link OverflowPolicy#DROP_NEWEST}
     */
    boolean send(T item) throws InterruptedException {
        return sendTimed(item).status() != SendStatus.DROPPED_NEWEST;
    }

    /**
     * Enqueues a non-null item while reporting both the overflow outcome and time spent blocked on backpressure.
     */
    SendResult sendTimed(T item) throws InterruptedException {
        return sendInternal(item, true);
    }

    /**
     * Attempts to enqueue a non-null item without blocking under {@link OverflowPolicy#BLOCK}.
     *
     * @return the {@link SendResult}, or {@code null} if {@link OverflowPolicy#BLOCK} is active and the buffer is full
     */
    SendResult trySend(T item) throws InterruptedException {
        return sendInternal(item, false);
    }

    private SendResult sendInternal(T item, boolean allowBlock) throws InterruptedException {
        Objects.requireNonNull(item, "Null items are not allowed in pipeline channels");
        long blockedNanos = 0L;
        lock.lockInterruptibly();
        try {
            checkSendState();

            if (buffer.size() == capacity) {
                switch (overflowPolicy) {
                    case BLOCK -> {
                        if (!allowBlock) {
                            return null;
                        }
                        while (buffer.size() == capacity && !aborted && !closed && !draining) {
                            long waitStart = System.nanoTime();
                            notFull.await();
                            long delta = Math.max(0L, System.nanoTime() - waitStart);
                            blockedNanos += delta;
                            sendWaitNanos.add(delta);
                        }
                        checkSendState();
                    }
                    case DROP_OLDEST -> {
                        buffer.removeFirst();
                        buffer.addLast(item);
                        droppedCount.increment();
                        notEmpty.signal();
                        return new SendResult(SendStatus.DROPPED_OLDEST, 0L);
                    }
                    case DROP_NEWEST -> {
                        droppedCount.increment();
                        return new SendResult(SendStatus.DROPPED_NEWEST, 0L);
                    }
                    case FAIL -> {
                        droppedCount.increment();
                        throw new ChannelOverflowException(
                                "Channel overflowed at capacity " + capacity + " (OverflowPolicy.FAIL)");
                    }
                }
            }

            buffer.addLast(item);
            currentSize.incrementAndGet();
            notEmpty.signal();
            return new SendResult(SendStatus.ENQUEUED, blockedNanos);
        } finally {
            lock.unlock();
        }
    }

    private void checkSendState() {
        if (aborted) {
            throw new PipeAbortedException(abortCause);
        }
        if (draining) {
            throw new PipeDrainingException();
        }
        if (closed) {
            throw new IllegalStateException("Cannot send to a closed channel");
        }
    }

    /**
     * Dequeues the next item, blocking while the buffer is empty and open.
     *
     * @return the next item, or {@code null} when the channel is closed and fully drained
     */
    T receive() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (buffer.isEmpty() && !closed && !aborted) {
                long waitStart = System.nanoTime();
                notEmpty.await();
                receiveWaitNanos.add(Math.max(0L, System.nanoTime() - waitStart));
            }
            if (aborted) {
                throw new PipeAbortedException(abortCause);
            }
            if (buffer.isEmpty()) {
                return null;
            }
            T item = buffer.removeFirst();
            currentSize.decrementAndGet();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Dequeues the next item paired with a strictly monotonic FIFO sequence number.
     *
     * @return the sequenced item, or {@code null} on clean EOF
     */
    SequencedItem<T> receiveSequenced() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (buffer.isEmpty() && !closed && !aborted) {
                long waitStart = System.nanoTime();
                notEmpty.await();
                receiveWaitNanos.add(Math.max(0L, System.nanoTime() - waitStart));
            }
            if (aborted) {
                throw new PipeAbortedException(abortCause);
            }
            if (buffer.isEmpty()) {
                return null;
            }
            T item = buffer.removeFirst();
            currentSize.decrementAndGet();
            long seq = nextReceiveSequence++;
            notFull.signal();
            return new SequencedItem<>(seq, item);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Receives a batch of up to {@code maxSize} items into {@code target}, waiting up to {@code maxWaitNanos}
     * after the first item arrives before flushing a partial batch.
     *
     * @return {@code true} if at least one item was placed in {@code target}, {@code false} on clean EOF
     */
    boolean receiveBatch(List<T> target, int maxSize, long maxWaitNanos) throws InterruptedException {
        Objects.requireNonNull(target, "target must not be null");
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be >= 1, got " + maxSize);
        }
        lock.lockInterruptibly();
        try {
            while (buffer.isEmpty() && !closed && !aborted) {
                long waitStart = System.nanoTime();
                notEmpty.await();
                receiveWaitNanos.add(Math.max(0L, System.nanoTime() - waitStart));
            }
            if (aborted) {
                throw new PipeAbortedException(abortCause);
            }
            if (buffer.isEmpty()) {
                return false;
            }

            if (drainInto(target, maxSize) > 0) {
                notFull.signalAll();
            }
            if (target.size() < maxSize && !closed && !aborted && maxWaitNanos > 0L) {
                long remaining = maxWaitNanos;
                while (target.size() < maxSize && remaining > 0L && !closed && !aborted) {
                    if (!buffer.isEmpty()) {
                        if (drainInto(target, maxSize) > 0) {
                            notFull.signalAll();
                        }
                        continue;
                    }
                    remaining = notEmpty.awaitNanos(remaining);
                }
                if (!buffer.isEmpty() && target.size() < maxSize && !aborted) {
                    if (drainInto(target, maxSize) > 0) {
                        notFull.signalAll();
                    }
                }
            }
            if (aborted) {
                throw new PipeAbortedException(abortCause);
            }
            return !target.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    private int drainInto(List<T> target, int maxSize) {
        int count = 0;
        while (!buffer.isEmpty() && target.size() < maxSize) {
            target.add(buffer.removeFirst());
            currentSize.decrementAndGet();
            count++;
        }
        return count;
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
     * Signals upstream senders on this channel that graceful draining has started,
     * causing blocked {@link #send} calls to exit with {@link PipeDrainingException}.
     */
    void beginDrain() {
        lock.lock();
        try {
            if (!draining) {
                draining = true;
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
                currentSize.set(0);
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

    int size() {
        return currentSize.get();
    }

    double fillRatio() {
        return Math.min(1.0, (double) currentSize.get() / (double) capacity);
    }

    long droppedCount() {
        return droppedCount.sum();
    }

    long sendWaitNanos() {
        return sendWaitNanos.sum();
    }

    long receiveWaitNanos() {
        return receiveWaitNanos.sum();
    }

    boolean isClosed() {
        lock.lock();
        try {
            return closed;
        } finally {
            lock.unlock();
        }
    }

    boolean isAborted() {
        lock.lock();
        try {
            return aborted;
        } finally {
            lock.unlock();
        }
    }
}
