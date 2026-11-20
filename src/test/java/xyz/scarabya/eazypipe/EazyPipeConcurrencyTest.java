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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class EazyPipeConcurrencyTest {

    @Test
    void highThroughputMultiStageParallelismPreservesAllItems() {
        int totalItems = 10_000;
        long expectedEvenCount = totalItems / 2L;
        long expectedSum = 0L;
        for (int i = 0; i < totalItems; i++) {
            if (i % 2 == 0) {
                expectedSum += i * 3L;
            }
        }

        LongAdder actualSum = new LongAdder();
        LongAdder actualCount = new LongAdder();

        EazyPipe.<Integer>from(emitter -> {
                    for (int i = 0; i < totalItems; i++) {
                        emitter.emit(i);
                    }
                })
                .filter(StageConfig.parallel(4), n -> n % 2 == 0)
                .map(StageConfig.parallel(4), n -> n * 3L)
                .<Long>stage(StageConfig.parallel(2), (n, emitter) -> emitter.emit(n))
                .run(StageConfig.parallel(4), n -> {
                    actualSum.add(n);
                    actualCount.increment();
                });

        assertEquals(expectedEvenCount, actualCount.sum());
        assertEquals(expectedSum, actualSum.sum());
    }

    @Test
    void onCompleteRunsExactlyOnceStrictlyAfterAllStageWorkersFinish() {
        AtomicInteger sourceItems = new AtomicInteger();
        AtomicInteger sourceOnCompleteCalls = new AtomicInteger();
        AtomicInteger sourceCountAtCallback = new AtomicInteger();

        AtomicInteger mapItems = new AtomicInteger();
        AtomicInteger mapOnCompleteCalls = new AtomicInteger();
        AtomicInteger mapCountAtCallback = new AtomicInteger();

        AtomicInteger sinkItems = new AtomicInteger();
        AtomicInteger sinkOnCompleteCalls = new AtomicInteger();
        AtomicInteger sinkCountAtCallback = new AtomicInteger();

        StageConfig sourceConfig = StageConfig.builder()
                .name("source")
                .parallelism(3)
                .onComplete(() -> {
                    sourceOnCompleteCalls.incrementAndGet();
                    sourceCountAtCallback.set(sourceItems.get());
                })
                .build();

        StageConfig mapConfig = StageConfig.builder()
                .name("mapper")
                .parallelism(4)
                .onComplete(() -> {
                    mapOnCompleteCalls.incrementAndGet();
                    mapCountAtCallback.set(mapItems.get());
                })
                .build();

        StageConfig sinkConfig = StageConfig.builder()
                .name("sink")
                .parallelism(4)
                .onComplete(() -> {
                    sinkOnCompleteCalls.incrementAndGet();
                    sinkCountAtCallback.set(sinkItems.get());
                })
                .build();

        EazyPipe.<Integer>from(sourceConfig, emitter -> {
                    for (int i = 0; i < 50; i++) {
                        sourceItems.incrementAndGet();
                        emitter.emit(i);
                    }
                })
                .map(mapConfig, n -> {
                    mapItems.incrementAndGet();
                    return n + 1;
                })
                .run(sinkConfig, n -> sinkItems.incrementAndGet());

        assertEquals(1, sourceOnCompleteCalls.get());
        assertEquals(150, sourceCountAtCallback.get());
        assertEquals(1, mapOnCompleteCalls.get());
        assertEquals(150, mapCountAtCallback.get());
        assertEquals(1, sinkOnCompleteCalls.get());
        assertEquals(150, sinkCountAtCallback.get());
    }

    @Test
    void backpressureBlocksFastProducerUntilSlowSinkConsumes() throws Exception {
        AtomicInteger produced = new AtomicInteger();
        CountDownLatch firstItemReachedSink = new CountDownLatch(1);
        CountDownLatch allowSinkToContinue = new CountDownLatch(1);
        AtomicInteger consumed = new AtomicInteger();

        PipeExecution<Void> execution = EazyPipe.<Integer>from(emitter -> {
                    for (int i = 1; i <= 10; i++) {
                        emitter.emit(i);
                        produced.incrementAndGet();
                    }
                })
                .start(StageConfig.withBuffer(2), item -> {
                    if (item == 1) {
                        firstItemReachedSink.countDown();
                        allowSinkToContinue.await();
                    }
                    consumed.incrementAndGet();
                });

        try (execution) {
            assertTrue(firstItemReachedSink.await(2, TimeUnit.SECONDS));
            Thread.sleep(100);
            // At most 1 item held inside the sink + 2 items buffered in the bounded channel.
            assertTrue(produced.get() <= 3,
                    "Expected backpressure to cap emitted items at <= 3, got " + produced.get());

            allowSinkToContinue.countDown();
            execution.join();
            assertEquals(10, produced.get());
            assertEquals(10, consumed.get());
        }
    }

    @Test
    void supportsVirtualPlatformAndCustomExecutors() {
        AtomicBoolean sawVirtual = new AtomicBoolean(false);
        EazyPipe.of("v")
                .configure(PipeConfig.virtualThreads())
                .run(s -> sawVirtual.set(Thread.currentThread().isVirtual()));
        assertTrue(sawVirtual.get());

        AtomicBoolean sawPlatform = new AtomicBoolean(false);
        AtomicBoolean sawDaemon = new AtomicBoolean(false);
        EazyPipe.of("p")
                .configure(PipeConfig.platformThreads())
                .run(s -> {
                    Thread t = Thread.currentThread();
                    sawPlatform.set(!t.isVirtual());
                    sawDaemon.set(t.isDaemon());
                });
        assertTrue(sawPlatform.get());
        assertTrue(sawDaemon.get());

        ExecutorService customPool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "custom-pool-worker");
            t.setDaemon(true);
            return t;
        });
        try {
            AtomicBoolean ranOnCustomThread = new AtomicBoolean(false);
            List<String> result = EazyPipe.of("a", "b")
                    .configure(PipeConfig.withExecutor(customPool))
                    .peek(s -> {
                        if (Thread.currentThread().getName().equals("custom-pool-worker")) {
                            ranOnCustomThread.set(true);
                        }
                    })
                    .toList();

            assertEquals(List.of("a", "b"), result);
            assertTrue(ranOnCustomThread.get());
            assertFalse(customPool.isShutdown());
        } finally {
            customPool.shutdownNow();
        }
    }

    @Test
    void asyncPipeExecutionSupportsFutureTimeoutCancelAndAutoCloseable() throws Exception {
        PipeExecution<List<Integer>> asyncExec = EazyPipe.of(1, 2, 3)
                .map(n -> n * 10)
                .startToList();
        assertEquals(List.of(10, 20, 30), asyncExec.future().get(2, TimeUnit.SECONDS));
        assertTrue(asyncExec.isDone());
        assertFalse(asyncExec.isFailed());
        assertFalse(asyncExec.isCancelled());

        CountDownLatch infiniteExited = new CountDownLatch(1);
        PipeExecution<Void> infinite = EazyPipe.<Integer>from(emitter -> {
                    try {
                        int i = 0;
                        while (!Thread.currentThread().isInterrupted()) {
                            emitter.emit(i++);
                        }
                    } finally {
                        infiniteExited.countDown();
                    }
                })
                .start(StageConfig.withBuffer(4), item -> Thread.sleep(20));

        assertThrows(TimeoutException.class, () -> infinite.await(Duration.ofMillis(40)));
        infinite.cancel();
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            assertTrue(infiniteExited.await(2, TimeUnit.SECONDS));
            infinite.close();
        });
        assertTrue(infinite.isDone());
        assertTrue(infinite.isCancelled());
        assertFalse(infinite.isFailed());

        CountDownLatch closedStarted = new CountDownLatch(1);
        CountDownLatch closedExited = new CountDownLatch(1);
        try (PipeExecution<Void> scoped = EazyPipe.<Integer>from(emitter -> {
                    try {
                        int i = 0;
                        while (!Thread.currentThread().isInterrupted()) {
                            emitter.emit(i++);
                        }
                    } finally {
                        closedExited.countDown();
                    }
                })
                .start(StageConfig.withBuffer(2), item -> {
                    closedStarted.countDown();
                    Thread.sleep(20);
                })) {
            assertTrue(closedStarted.await(2, TimeUnit.SECONDS));
            assertFalse(scoped.isDone());
        }
        assertTrue(closedExited.await(2, TimeUnit.SECONDS));
    }

    @Test
    void collectWorksSafelyWithParallelUpstreamStages() {
        String joined = EazyPipe.of(1, 2, 3, 4, 5)
                .map(StageConfig.parallel(4), n -> n * 2)
                .collect(Collectors.summingInt(Integer::intValue))
                .toString();
        assertEquals("30", joined);
    }
}
