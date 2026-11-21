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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EazyPipeErrorHandlingTest {

    @Test
    void failFastReportsStageNameAndCauseAcrossSourceProcessorAndSink() {
        PipeException sourceEx = assertThrows(PipeException.class, () ->
                EazyPipe.from(StageConfig.named("my-source"), emitter -> {
                    throw new IllegalStateException("source boom");
                }).toList());
        assertEquals(Optional.of("my-source"), sourceEx.stageName());
        assertTrue(sourceEx.getCause() instanceof IllegalStateException);

        PipeException procEx = assertThrows(PipeException.class, () ->
                EazyPipe.of(1, 2, 3)
                        .map(StageConfig.named("my-proc"), n -> {
                            if (n == 2) {
                                throw new IllegalArgumentException("proc boom");
                            }
                            return n;
                        })
                        .toList());
        assertEquals(Optional.of("my-proc"), procEx.stageName());
        assertTrue(procEx.getCause() instanceof IllegalArgumentException);

        PipeException sinkEx = assertThrows(PipeException.class, () ->
                EazyPipe.of(1, 2, 3)
                        .run(StageConfig.named("my-sink"), n -> {
                            if (n == 2) {
                                throw new IllegalStateException("sink boom");
                            }
                        }));
        assertEquals(Optional.of("my-sink"), sinkEx.stageName());
        assertTrue(sinkEx.getCause() instanceof IllegalStateException);
    }

    @Test
    void failFastAbortsInfiniteBlockedProducerWithoutDeadlock() throws Exception {
        CountDownLatch producerExited = new CountDownLatch(1);
        AtomicInteger sinkSeen = new AtomicInteger();

        PipeException thrown = assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                assertThrows(PipeException.class, () ->
                        EazyPipe.<Integer>from(emitter -> {
                                    try {
                                        int i = 1;
                                        while (true) {
                                            emitter.emit(i++);
                                        }
                                    } finally {
                                        producerExited.countDown();
                                    }
                                })
                                .run(StageConfig.builder()
                                                .name("failing-sink")
                                                .bufferCapacity(2)
                                                .build(),
                                        item -> {
                                            if (sinkSeen.incrementAndGet() == 2) {
                                                throw new RuntimeException("sink crashed");
                                            }
                                        })));

        assertEquals(Optional.of("failing-sink"), thrown.stageName());
        assertTrue(producerExited.await(2, TimeUnit.SECONDS));
    }

    @Test
    void continueOnItemErrorSkipsBadItemsRecordsStatsAndNotifiesHandler() {
        List<String> handledErrors = new CopyOnWriteArrayList<>();
        List<Integer> accepted = new CopyOnWriteArrayList<>();

        PipeConfig resilientConfig = PipeConfig.builder()
                .errorStrategy(PipeConfig.ErrorStrategy.CONTINUE_ON_ITEM_ERROR)
                .errorHandler((stage, err) -> handledErrors.add(stage + ":" + err.getMessage()))
                .build();

        PipeExecution<Void> exec = EazyPipe.of("1", "bad", "2", "oops", "3")
                .configure(resilientConfig)
                .map(StageConfig.named("parser"), Integer::parseInt)
                .start(StageConfig.named("sink"), n -> {
                    if (n == 2) {
                        throw new IllegalStateException("skip-two");
                    }
                    accepted.add(n);
                });

        try (exec) {
            exec.join();
            PipeStats stats = exec.stats();
            assertEquals(List.of(1, 3), accepted);
            assertEquals(5L, stats.itemsProduced());
            assertEquals(2L, stats.itemsConsumed());
            assertEquals(3L, stats.errorsSkipped());
            assertEquals(3, handledErrors.size());
            assertEquals(2, handledErrors.stream().filter(s -> s.startsWith("parser:")).count());
            assertEquals(1, handledErrors.stream().filter(s -> s.startsWith("sink:skip-two")).count());
        }
    }

    @Test
    void seriousErrorsStillFailFastEvenWithContinueOnItemError() {
        PipeConfig resilientConfig = PipeConfig.builder()
                .errorStrategy(PipeConfig.ErrorStrategy.CONTINUE_ON_ITEM_ERROR)
                .build();

        AssertionError err = assertThrows(AssertionError.class, () ->
                EazyPipe.of(1, 2)
                        .configure(resilientConfig)
                        .map(StageConfig.named("oom-sim"), n -> {
                            throw new AssertionError("fatal error");
                        })
                        .toList());

        assertEquals("fatal error", err.getMessage());
        assertTrue(Arrays.stream(err.getSuppressed())
                .anyMatch(t -> t instanceof PipeException pe
                        && Optional.of("oom-sim").equals(pe.stageName())));
    }

    @Test
    void rejectedExecutionFromShutdownCustomExecutorTerminatesCleanlyWithoutDeadlock() {
        ExecutorService closedExecutor = Executors.newFixedThreadPool(2);
        closedExecutor.shutdownNow();

        PipeException ex = assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                assertThrows(PipeException.class, () ->
                        EazyPipe.of(1, 2, 3)
                                .configure(PipeConfig.withExecutor(closedExecutor))
                                .toList()));

        assertTrue(ex.getCause() instanceof RejectedExecutionException);
    }
}
