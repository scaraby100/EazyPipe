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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import xyz.scarabya.eazypipe.adaptive.ScalingDecision;
import xyz.scarabya.eazypipe.metrics.PipelineMetricsListener;
import xyz.scarabya.eazypipe.metrics.PipelineMetricsSnapshot;
import xyz.scarabya.eazypipe.metrics.StageMetricsSnapshot;

class EazyPipeOperatorsTest {

    @Test
    void mapConcurrentOrderedPreservesStrictFifoOrderDespiteInvertedLatencies() {
        List<Integer> input = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        List<Integer> output = EazyPipe.fromIterable(input)
                .mapConcurrentOrdered(StageConfig.parallel(4), n -> {
                    // Delay early items much longer than later items to stress out-of-order completion.
                    Thread.sleep((9 - n) * 6L);
                    return n * 10;
                })
                .toList();

        assertEquals(List.of(0, 10, 20, 30, 40, 50, 60, 70, 80, 90), output);
    }

    @Test
    void mapConcurrentOrderedHandlesSkippedItemsWithTombstoneWithoutDeadlock() {
        StageConfig cfg = StageConfig.builder()
                .name("ordered-skip")
                .parallelism(4)
                .errorPolicy(ErrorPolicy.SKIP)
                .build();

        List<Integer> output = assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                EazyPipe.of(0, 1, 2, 3, 4, 5)
                        .mapConcurrentOrdered(cfg, n -> {
                            if (n == 2 || n == 4) {
                                throw new IllegalStateException("skip-" + n);
                            }
                            return n * 100;
                        })
                        .toList());

        assertEquals(List.of(0, 100, 300, 500), output);
    }

    @Test
    void batchGroupsByMaxSizeMaxWaitAndFlushesTailOnEof() {
        List<List<Integer>> batches = EazyPipe.of(1, 2, 3, 4, 5, 6, 7)
                .batch("batcher", 3, Duration.ofMillis(200))
                .toList();

        assertEquals(List.of(List.of(1, 2, 3), List.of(4, 5, 6), List.of(7)), batches);

        // Verify time-based flush when upstream pauses longer than maxWait.
        List<List<Integer>> timedBatches = EazyPipe.<Integer>from(emitter -> {
                    emitter.emit(1);
                    Thread.sleep(150);
                    emitter.emit(2);
                })
                .batch(10, Duration.ofMillis(30))
                .toList();

        assertEquals(List.of(List.of(1), List.of(2)), timedBatches);
    }

    @Test
    void rateLimitPacesItemsAndAbortsImmediatelyOnCancel() throws Exception {
        long startNanos = System.nanoTime();
        List<Integer> items = EazyPipe.of(1, 2, 3, 4)
                .rateLimit(40.0) // ~25ms per item -> >= 75ms across 4 items
                .toList();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertEquals(List.of(1, 2, 3, 4), items);
        assertTrue(elapsedMs >= 50, "Expected rate-limited run to take >= 50ms, took " + elapsedMs + "ms");

        CountDownLatch started = new CountDownLatch(1);
        PipeExecution<List<Integer>> cancelledRun = EazyPipe.of(1, 2, 3, 4, 5)
                .peek(n -> started.countDown())
                .rateLimit(0.5) // 1 item per 2s; cancel must abort immediately without waiting 2s
                .startToList();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            cancelledRun.cancel();
            cancelledRun.close();
        });
        assertTrue(cancelledRun.isCancelled());
    }

    @Test
    void teeForksToSideBranchAndWaitsForSideCompletion() {
        List<String> mainItems = new CopyOnWriteArrayList<>();
        List<String> sideItems = new CopyOnWriteArrayList<>();
        AtomicBoolean sideCompleted = new AtomicBoolean(false);

        StageConfig sideCfg = StageConfig.builder()
                .name("audit-tee")
                .parallelism(2)
                .onComplete(() -> sideCompleted.set(true))
                .build();

        EazyPipe.of("a", "b", "c")
                .tee(sideCfg, item -> {
                    Thread.sleep(20);
                    sideItems.add(item.toUpperCase());
                })
                .run(mainItems::add);

        assertEquals(List.of("a", "b", "c"), mainItems);
        assertTrue(sideCompleted.get());
        assertEquals(Set.of("A", "B", "C"), Set.copyOf(sideItems));
    }

    @Test
    void broadcastDeliversEveryItemToAllConcurrentSinks() {
        Set<Integer> sinkA = ConcurrentHashMap.newKeySet();
        Set<Integer> sinkB = ConcurrentHashMap.newKeySet();
        Set<Integer> sinkC = ConcurrentHashMap.newKeySet();

        EazyPipe.of(1, 2, 3, 4, 5).runBroadcast(
                sinkA::add,
                n -> {
                    Thread.sleep(10);
                    sinkB.add(n * 10);
                },
                sinkC::add);

        assertEquals(Set.of(1, 2, 3, 4, 5), sinkA);
        assertEquals(Set.of(10, 20, 30, 40, 50), sinkB);
        assertEquals(Set.of(1, 2, 3, 4, 5), sinkC);
    }

    @Test
    void routeDispatchesByKeySupportsDefaultRouteAndFailsOnUnmappedKey() {
        List<String> evens = new CopyOnWriteArrayList<>();
        List<String> fallback = new CopyOnWriteArrayList<>();

        EazyPipe.of(1, 2, 3, 4)
                .runRoute(
                        StageConfig.parallel(2),
                        n -> (n % 2 == 0) ? "even" : "odd",
                        Map.of("even", n -> evens.add("E" + n)),
                        n -> fallback.add("F" + n));

        assertEquals(Set.of("E2", "E4"), Set.copyOf(evens));
        assertEquals(Set.of("F1", "F3"), Set.copyOf(fallback));

        assertThrows(PipeException.class, () ->
                EazyPipe.of(1, 2).runRoute(n -> "unknown", Map.of()));
    }

    @Test
    void mergeCombinesMultipleConcurrentSources() {
        List<String> merged = EazyPipe.<String>merge(
                        emitter -> {
                            emitter.emit("s1-a");
                            Thread.sleep(10);
                            emitter.emit("s1-b");
                        },
                        emitter -> {
                            emitter.emit("s2-a");
                            emitter.emit("s2-b");
                        })
                .toList();

        assertEquals(4, merged.size());
        assertEquals(Set.of("s1-a", "s1-b", "s2-a", "s2-b"), Set.copyOf(merged));
    }

    @Test
    void contextPropagatorPreservesCallerAndPerItemContext() {
        ThreadLocal<String> traceContext = new ThreadLocal<>();
        ContextPropagator propagator = () -> {
            String captured = traceContext.get();
            return () -> {
                String previous = traceContext.get();
                traceContext.set(captured);
                return () -> {
                    if (previous == null) {
                        traceContext.remove();
                    } else {
                        traceContext.set(previous);
                    }
                };
            };
        };

        List<String> observed = new CopyOnWriteArrayList<>();
        traceContext.set("root-trace");
        try {
            PipeConfig cfg = PipeConfig.builder()
                    .contextPropagator(propagator)
                    .build();

            EazyPipe.<String>from(cfg, emitter -> {
                        String root = traceContext.get();
                        traceContext.set(root + ":item-1");
                        emitter.emit("item-1");
                        traceContext.set(root + ":item-2");
                        emitter.emit("item-2");
                    })
                    .map(StageConfig.parallel(2), s -> s + "@" + traceContext.get())
                    .run(observed::add);
        } finally {
            traceContext.remove();
        }

        assertEquals(
                Set.of("item-1@root-trace:item-1", "item-2@root-trace:item-2"),
                Set.copyOf(observed));
    }

    @Test
    void metricsListenerObservesEventsAndCompletionSnapshot() {
        AtomicInteger itemErrors = new AtomicInteger();
        AtomicInteger deadLetters = new AtomicInteger();
        AtomicInteger stageMetrics = new AtomicInteger();
        AtomicBoolean completedCalled = new AtomicBoolean(false);
        CopyOnWriteArrayList<ScalingDecision> seenDecisions = new CopyOnWriteArrayList<>();

        PipelineMetricsListener listener = new PipelineMetricsListener() {
            @Override
            public void onStageMetrics(StageMetricsSnapshot snapshot) {
                stageMetrics.incrementAndGet();
            }

            @Override
            public void onScalingDecision(ScalingDecision decision) {
                seenDecisions.add(decision);
            }

            @Override
            public void onItemError(String stageName, Throwable error) {
                itemErrors.incrementAndGet();
            }

            @Override
            public void onDeadLetter(String stageName, Throwable error) {
                deadLetters.incrementAndGet();
            }

            @Override
            public void onPipelineCompleted(PipelineMetricsSnapshot snapshot) {
                completedCalled.set(snapshot.itemsConsumed() == 2 && snapshot.errorsSkipped() == 1);
            }
        };

        PipeConfig cfg = PipeConfig.builder().metricsListener(listener).build();
        StageConfig dlqStage = StageConfig.builder()
                .name("parse")
                .deadLetter((item, err, name) -> { })
                .build();

        List<Integer> parsed = EazyPipe.of("10", "bad", "20")
                .configure(cfg)
                .map(dlqStage, Integer::parseInt)
                .toList();

        assertEquals(List.of(10, 20), parsed);
        assertEquals(1, itemErrors.get());
        assertEquals(1, deadLetters.get());
        assertTrue(stageMetrics.get() >= 1);
        assertTrue(completedCalled.get());
        assertTrue(seenDecisions.isEmpty());
    }

    @Test
    void teeSideFailureFailsFastWithoutDeadlock() {
        PipeException ex = assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                assertThrows(PipeException.class, () ->
                        EazyPipe.of(1, 2, 3, 4, 5)
                                .tee(StageConfig.named("audit"), item -> {
                                    if (item == 2) {
                                        throw new IllegalStateException("audit-down");
                                    }
                                })
                                .toList()));

        assertTrue(ex.getCause() instanceof IllegalStateException);
    }
}
