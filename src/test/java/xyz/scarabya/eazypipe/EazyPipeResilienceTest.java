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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;

class EazyPipeResilienceTest {

    @Test
    void hybridSchedulerRunsCpuBoundOnPlatformAndIoBoundOnVirtualThreads() {
        AtomicBoolean cpuWasVirtual = new AtomicBoolean(true);
        AtomicBoolean ioWasVirtual = new AtomicBoolean(false);

        List<String> out = EazyPipe.of("item-1", "item-2")
                .map(StageConfig.cpuBound("cpu-stage").parallelism(2).build(), s -> {
                    cpuWasVirtual.set(Thread.currentThread().isVirtual());
                    return s.toUpperCase();
                })
                .map(StageConfig.ioBound("io-stage").parallelism(2).build(), s -> {
                    ioWasVirtual.set(Thread.currentThread().isVirtual());
                    return s + "!";
                })
                .toList();

        assertEquals(Set.of("ITEM-1!", "ITEM-2!"), Set.copyOf(out));
        assertFalse(cpuWasVirtual.get());
        assertTrue(ioWasVirtual.get());
    }

    @Test
    void retrySpecRecoversFromTransientFailures() {
        AtomicInteger attemptsForTwo = new AtomicInteger();

        StageConfig retryCfg = StageConfig.ioBound("flaky")
                .retry(RetrySpec.fixed(2, Duration.ofMillis(5)))
                .build();

        List<Integer> out = EazyPipe.of(1, 2, 3)
                .map(retryCfg, n -> {
                    if (n == 2 && attemptsForTwo.incrementAndGet() <= 2) {
                        throw new IllegalStateException("transient-" + attemptsForTwo.get());
                    }
                    return n * 10;
                })
                .toList();

        assertEquals(List.of(10, 20, 30), out);
        assertEquals(3, attemptsForTwo.get());
    }

    @Test
    void retryExhaustionRoutesToTypedDeadLetterHandler() {
        List<String> deadLetters = new CopyOnWriteArrayList<>();

        StageConfig stageCfg = StageConfig.ioBound("embed")
                .retry(RetrySpec.exponential(2, Duration.ofMillis(2)))
                .deadLetter(String.class, (item, err, stage) ->
                        deadLetters.add(stage + ":" + item + ":" + err.getMessage()))
                .build();

        List<String> out = EazyPipe.of("ok-1", "bad", "ok-2")
                .map(stageCfg, s -> {
                    if (s.equals("bad")) {
                        throw new IllegalStateException("permanent");
                    }
                    return s.toUpperCase();
                })
                .toList();

        assertEquals(List.of("OK-1", "OK-2"), out);
        assertEquals(List.of("embed:bad:permanent"), deadLetters);
    }

    @Test
    void overflowPoliciesWorkEndToEndThroughPipeline() {
        // OverflowPolicy.FAIL must abort pipeline with ChannelOverflowException cause.
        CountDownLatch sinkHold = new CountDownLatch(1);
        CountDownLatch sinkEntered = new CountDownLatch(1);
        PipeExecution<Void> failExec = EazyPipe.<Integer>from(emitter -> {
                    for (int i = 0; i < 100; i++) {
                        emitter.emit(i);
                    }
                })
                .start(StageConfig.builder()
                                .name("fail-overflow-sink")
                                .bufferCapacity(2)
                                .overflowPolicy(OverflowPolicy.FAIL)
                                .build(),
                        item -> {
                            sinkEntered.countDown();
                            sinkHold.await();
                        });

        PipeException ex = assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                assertThrows(PipeException.class, () -> {
                    try (failExec) {
                        assertTrue(sinkEntered.await(2, TimeUnit.SECONDS));
                        failExec.join();
                    } finally {
                        sinkHold.countDown();
                    }
                }));
        assertTrue(ex.getCause() instanceof ChannelOverflowException
                || (ex.getSuppressed().length > 0 && ex.getSuppressed()[0] instanceof ChannelOverflowException));

        // OverflowPolicy.DROP_NEWEST keeps pipeline succeeding without blocking fast producer.
        CountDownLatch slowSinkLatch = new CountDownLatch(1);
        CountDownLatch firstConsumed = new CountDownLatch(1);
        List<Integer> accepted = new CopyOnWriteArrayList<>();

        PipeExecution<Void> dropExec = EazyPipe.<Integer>from(emitter -> {
                    for (int i = 1; i <= 50; i++) {
                        emitter.emit(i);
                    }
                })
                .start(StageConfig.builder()
                                .name("drop-newest-sink")
                                .bufferCapacity(2)
                                .overflowPolicy(OverflowPolicy.DROP_NEWEST)
                                .build(),
                        item -> {
                            if (item == 1) {
                                firstConsumed.countDown();
                                slowSinkLatch.await();
                            }
                            accepted.add(item);
                        });

        try (dropExec) {
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                assertTrue(firstConsumed.await(2, TimeUnit.SECONDS));
                Thread.sleep(50);
                slowSinkLatch.countDown();
                dropExec.join();
            });
            assertTrue(accepted.size() < 50);
            assertTrue(dropExec.metricsSnapshot().itemsDropped() > 0L);
        }
    }

    @Test
    void gracefulDrainStopsInfiniteSourceAndDrainsAllInFlightItems() throws Exception {
        CountDownLatch firstEmitted = new CountDownLatch(10);
        AtomicInteger producedCount = new AtomicInteger();

        PipeExecution<List<Integer>> exec = EazyPipe.<Integer>from(emitter -> {
                    int i = 1;
                    while (!Thread.currentThread().isInterrupted()) {
                        emitter.emit(i);
                        producedCount.incrementAndGet();
                        firstEmitted.countDown();
                        i++;
                        Thread.sleep(1);
                    }
                })
                .map(n -> {
                    Thread.sleep(5);
                    return n * 2;
                })
                .startToList();

        try (exec) {
            assertTrue(firstEmitted.await(3, TimeUnit.SECONDS));
            List<Integer> drained = assertTimeoutPreemptively(Duration.ofSeconds(5), exec::drain);
            assertFalse(exec.isCancelled());
            assertFalse(exec.isFailed());
            assertTrue(drained.size() >= 10);
            assertEquals(producedCount.get(), drained.size());
        }
    }

    @Test
    void highVolumeBackpressureStressWithTinyBufferPreservesEveryItem() {
        int total = 250_000;
        long expectedSum = ((long) total * (total - 1)) / 2L;
        LongAdder sum = new LongAdder();
        LongAdder count = new LongAdder();

        EazyPipe.<Integer>from(emitter -> {
                    for (int i = 0; i < total; i++) {
                        emitter.emit(i);
                    }
                })
                .map(StageConfig.builder().name("tiny-map").parallelism(2).bufferCapacity(8).build(), n -> (long) n)
                .run(StageConfig.builder().name("tiny-sink").parallelism(2).bufferCapacity(8).build(), n -> {
                    sum.add(n);
                    count.increment();
                });

        assertEquals(total, count.sum());
        assertEquals(expectedSum, sum.sum());
        assertEquals(Optional.empty(), new PipeException("msg", null).stageName());
    }

    @Test
    void retryPredicateStopsImmediatelyForNonRetryableExceptions() {
        AtomicInteger attempts = new AtomicInteger();
        StageConfig cfg = StageConfig.ioBound("selective-retry")
                .retry(RetrySpec.builder()
                        .maxRetries(5)
                        .initialDelay(Duration.ofMillis(5))
                        .retryOn(t -> t instanceof IllegalStateException)
                        .build())
                .build();

        PipeException ex = assertThrows(PipeException.class, () ->
                EazyPipe.of(1)
                        .map(cfg, n -> {
                            attempts.incrementAndGet();
                            throw new IllegalArgumentException("fatal-input");
                        })
                        .toList());

        assertEquals(1, attempts.get());
        assertTrue(ex.getCause() instanceof IllegalArgumentException);
    }
}
