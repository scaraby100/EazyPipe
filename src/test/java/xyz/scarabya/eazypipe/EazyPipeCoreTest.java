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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class EazyPipeCoreTest {

    @Test
    void linearPipelineMapFilterFlatMapPeekCollectAndCount() {
        List<String> peeked = new ArrayList<>();

        List<String> result = EazyPipe.of("alpha", "be", "gamma", "delta")
                .filter(s -> s.length() > 2)
                .peek(peeked::add)
                .map(String::toUpperCase)
                .flatMap(s -> List.of(s, s + "!"))
                .toList();

        assertEquals(List.of("alpha", "gamma", "delta"), peeked);
        assertEquals(
                List.of("ALPHA", "ALPHA!", "GAMMA", "GAMMA!", "DELTA", "DELTA!"),
                result);

        String joined = EazyPipe.of(1, 2, 3, 4)
                .map(n -> Integer.toString(n * 10))
                .collect(Collectors.joining(","));
        assertEquals("10,20,30,40", joined);

        long count = EazyPipe.of("a", "b", "c", "d")
                .filter(s -> !s.equals("b"))
                .count();
        assertEquals(3L, count);
    }

    @Test
    void customStageSupportsZeroOneOrManyOutputs() {
        List<Integer> expanded = EazyPipe.of(0, 1, 2, 3)
                .<Integer>stage((n, emitter) -> {
                    for (int i = 0; i < n; i++) {
                        emitter.emit(n);
                    }
                })
                .toList();

        assertEquals(List.of(1, 2, 2, 3, 3, 3), expanded);
    }

    @Test
    void emptySourceProducesEmptyResultAndZeroCount() {
        Pipeline<String> empty = EazyPipe.from(emitter -> { });
        assertEquals(List.of(), empty.toList());
        assertEquals(0L, empty.count());
    }

    @Test
    void directSourceToSinkWithoutIntermediateStages() {
        List<String> received = new ArrayList<>();
        EazyPipe.fromIterable(List.of("x", "y", "z")).run(received::add);
        assertEquals(List.of("x", "y", "z"), received);
    }

    @Test
    void fromStreamClosesUnderlyingStreamOnSuccessAndFailure() {
        AtomicBoolean closedOnSuccess = new AtomicBoolean(false);
        List<Integer> doubled = EazyPipe.fromStream(() ->
                Stream.of(1, 2, 3).onClose(() -> closedOnSuccess.set(true)))
                .map(i -> i * 2)
                .toList();

        assertEquals(List.of(2, 4, 6), doubled);
        assertTrue(closedOnSuccess.get());

        AtomicBoolean closedOnFailure = new AtomicBoolean(false);
        Pipeline<Integer> failing = EazyPipe.fromStream(() ->
                Stream.of(1, 2, 3).onClose(() -> closedOnFailure.set(true)))
                .map(i -> {
                    if (i == 2) {
                        throw new IllegalStateException("stop");
                    }
                    return i;
                });

        assertThrows(PipeException.class, failing::toList);
        assertTrue(closedOnFailure.get());
    }

    @Test
    void immutablePipelineCanBeBranchedAndReusedSafely() {
        Pipeline<Integer> base = EazyPipe.of(1, 2, 3, 4).map(x -> x * 10);

        List<Integer> branchA = base.filter(x -> x > 20).toList();
        String branchB = base.map(Object::toString).collect(Collectors.joining("-"));

        assertEquals(List.of(30, 40), branchA);
        assertEquals("10-20-30-40", branchB);
    }

    @Test
    void capturesAccuratePipeStats() {
        PipeExecution<List<Integer>> execution = EazyPipe.of(1, 2, 3, 4, 5)
                .filter(n -> n % 2 == 1)
                .startToList();

        List<Integer> result = execution.join();
        PipeStats stats = execution.stats();

        assertEquals(List.of(1, 3, 5), result);
        assertEquals(5L, stats.itemsProduced());
        assertEquals(3L, stats.itemsConsumed());
        assertEquals(0L, stats.errorsSkipped());
        assertEquals(3, stats.stageCount());
        assertFalse(stats.elapsed().isNegative());
    }

    @Test
    void validatesNullAndInvalidConfigurations() {
        assertThrows(IllegalArgumentException.class, () -> StageConfig.parallel(0));
        assertThrows(IllegalArgumentException.class, () -> StageConfig.withBuffer(-1));
        assertThrows(IllegalArgumentException.class, () ->
                PipeConfig.builder().defaultBufferCapacity(0).build());
        assertThrows(IllegalArgumentException.class, () ->
                new PipeConfig(10, PipeConfig.ExecutorMode.CUSTOM, null,
                        PipeConfig.ErrorStrategy.FAIL_FAST, null));
        assertThrows(NullPointerException.class, () -> EazyPipe.of("a", null));

        PipeException ex = assertThrows(PipeException.class, () ->
                EazyPipe.of("a").map(s -> null).toList());
        assertTrue(ex.getCause() instanceof NullPointerException);
    }
}
