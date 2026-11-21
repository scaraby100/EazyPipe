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
package xyz.scarabya.eazypipe.adaptive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import xyz.scarabya.eazypipe.EazyPipe;
import xyz.scarabya.eazypipe.PipeConfig;
import xyz.scarabya.eazypipe.PipeExecution;
import xyz.scarabya.eazypipe.StageConfig;
import xyz.scarabya.eazypipe.StageKind;

class AdaptiveControllerTest {

    private static final class MutableClock extends Clock {
        private Instant current = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

        void advance(Duration delta) {
            current = current.plus(delta);
        }
    }

    @Test
    void respectsPersistenceTicksAndCooldownUsingFakeClock() {
        MutableClock clock = new MutableClock();
        StageTelemetry telemetry = new StageTelemetry();
        AtomicInteger target = new AtomicInteger(2);
        AtomicReference<Double> inFill = new AtomicReference<>(0.90);
        AtomicReference<Double> outFill = new AtomicReference<>(0.10);

        AdaptiveConfig config = AdaptiveConfig.builder()
                .clock(clock)
                .tickInterval(Duration.ofMillis(250))
                .emaAlpha(1.0)
                .persistenceTicks(2)
                .cooldown(Duration.ofSeconds(1))
                .build();

        AdaptiveController controller = new AdaptiveController(
                config,
                4,
                List.of(new AdaptiveController.ManagedStage(
                        "io-stage",
                        StageKind.IO_BOUND,
                        1,
                        10,
                        telemetry,
                        true,
                        true,
                        inFill::get,
                        outFill::get,
                        () -> 0L,
                        () -> 0L,
                        () -> 0L,
                        target::get,
                        target::set)),
                null);

        // Tick 1: BOTTLENECK observed once -> persistenceTicks=2 prevents scaling yet.
        assertTrue(controller.tick().isEmpty());
        assertEquals(2, target.get());

        // Tick 2: BOTTLENECK persists for 2 ticks -> scales up.
        clock.advance(Duration.ofMillis(250));
        List<ScalingDecision> decisions2 = controller.tick();
        assertEquals(1, decisions2.size());
        assertEquals(3, target.get());

        // Tick 3 (only 250ms later): cooldown=1s prevents immediate second change.
        clock.advance(Duration.ofMillis(250));
        assertTrue(controller.tick().isEmpty());
        assertEquals(3, target.get());

        // Tick 4 (after cooldown elapses): scales up again.
        clock.advance(Duration.ofSeconds(1));
        List<ScalingDecision> decisions4 = controller.tick();
        assertEquals(1, decisions4.size());
        assertEquals(4, target.get());
    }

    @Test
    void scenarioAShiftingBottleneckCpuBudgetAllocatorRebalancesDownstream() {
        int cpuBudget = 8;

        // Phase 1: stage1 is slow (10ms) BOTTLENECK, stage2 is fast (1ms) STARVED, stage3 fast (1ms) STARVED.
        int[] phase1 = CpuBudgetAllocator.allocate(cpuBudget, List.of(
                new CpuBudgetAllocator.CpuStageView(
                        "cpu-1", StageState.BOTTLENECK, 2, 1, 8, 10_000_000.0, true, true),
                new CpuBudgetAllocator.CpuStageView(
                        "cpu-2", StageState.STARVED, 2, 1, 8, 1_000_000.0, true, true),
                new CpuBudgetAllocator.CpuStageView(
                        "cpu-3", StageState.STARVED, 2, 1, 8, 1_000_000.0, true, true)));

        assertTrue(phase1[0] >= 5);
        assertEquals(1, phase1[1]);
        assertEquals(1, phase1[2]);
        assertTrue(Arrays.stream(phase1).sum() <= cpuBudget);

        // Phase 2: bottleneck shifts to cpu-3 (12ms) while cpu-1 becomes fast (1ms) and BACKPRESSURED.
        int[] phase2Tick1 = CpuBudgetAllocator.allocate(cpuBudget, List.of(
                new CpuBudgetAllocator.CpuStageView(
                        "cpu-1", StageState.BACKPRESSURED, phase1[0], 1, 8, 1_000_000.0, true, true),
                new CpuBudgetAllocator.CpuStageView(
                        "cpu-2", StageState.BALANCED, 1, 1, 8, 1_000_000.0, true, true),
                new CpuBudgetAllocator.CpuStageView(
                        "cpu-3", StageState.BOTTLENECK, 1, 1, 8, 12_000_000.0, true, true)));

        assertTrue(phase2Tick1[0] < phase1[0]);
        assertTrue(phase2Tick1[2] > 1);
        assertTrue(Arrays.stream(phase2Tick1).sum() <= cpuBudget);

        // Second rebalancing tick completes convergence as cpu-1 continues yielding unused permits.
        int[] phase2Tick2 = CpuBudgetAllocator.allocate(cpuBudget, List.of(
                new CpuBudgetAllocator.CpuStageView(
                        "cpu-1", StageState.BACKPRESSURED, phase2Tick1[0], 1, 8, 1_000_000.0, true, true),
                new CpuBudgetAllocator.CpuStageView(
                        "cpu-2", StageState.BALANCED, phase2Tick1[1], 1, 8, 1_000_000.0, true, true),
                new CpuBudgetAllocator.CpuStageView(
                        "cpu-3", StageState.BOTTLENECK, phase2Tick1[2], 1, 8, 12_000_000.0, true, true)));

        assertTrue(phase2Tick2[0] < phase2Tick1[0]);
        assertTrue(phase2Tick2[2] >= 4);
        assertTrue(Arrays.stream(phase2Tick2).sum() <= cpuBudget);
    }

    @Test
    void duplicateStageNamesReceiveIndependentCpuTargetsByPosition() {
        int[] targets = CpuBudgetAllocator.allocate(6, List.of(
                new CpuBudgetAllocator.CpuStageView(
                        "dup", StageState.BOTTLENECK, 2, 1, 6, 10_000_000.0, true, true),
                new CpuBudgetAllocator.CpuStageView(
                        "dup", StageState.STARVED, 2, 1, 6, 1_000_000.0, true, true)));

        assertEquals(2, targets.length);
        assertEquals(5, targets[0], "BOTTLENECK dup stage should scale up to 5");
        assertEquals(1, targets[1], "STARVED dup stage should step down to 1");
        assertTrue(Arrays.stream(targets).sum() <= 6);

        MutableClock clock = new MutableClock();
        StageTelemetry t1 = new StageTelemetry();
        StageTelemetry t2 = new StageTelemetry();
        AtomicInteger target1 = new AtomicInteger(2);
        AtomicInteger target2 = new AtomicInteger(2);

        AdaptiveConfig config = AdaptiveConfig.builder()
                .clock(clock)
                .cpuBudget(6)
                .emaAlpha(1.0)
                .persistenceTicks(1)
                .cooldown(Duration.ZERO)
                .build();

        AdaptiveController controller = new AdaptiveController(
                config,
                6,
                List.of(
                        new AdaptiveController.ManagedStage(
                                "dup", StageKind.CPU_BOUND, 1, 6, t1,
                                true, true, () -> 0.90, () -> 0.10,
                                () -> 0L, () -> 0L, () -> 0L,
                                target1::get, target1::set),
                        new AdaptiveController.ManagedStage(
                                "dup", StageKind.CPU_BOUND, 1, 6, t2,
                                true, true, () -> 0.05, () -> 0.10,
                                () -> 100_000_000L, () -> 0L, () -> 0L,
                                target2::get, target2::set)),
                null);

        t1.onProcessingStart();
        t1.onProcessingSuccess(10_000_000L);
        t2.onProcessingStart();
        t2.onProcessingSuccess(1_000_000L);

        List<ScalingDecision> decisions = controller.tick();
        assertEquals(2, decisions.size());
        assertEquals(5, target1.get());
        assertEquals(1, target2.get());
    }

    @Test
    void scenarioBDownstreamStallAntiShiftingNeverScalesIntoFullWall() {
        MutableClock clock = new MutableClock();
        StageTelemetry upstreamTelemetry = new StageTelemetry();
        StageTelemetry downstreamTelemetry = new StageTelemetry();

        AtomicInteger upstreamTarget = new AtomicInteger(6);
        AtomicInteger downstreamTarget = new AtomicInteger(2);

        AdaptiveConfig config = AdaptiveConfig.builder()
                .clock(clock)
                .tickInterval(Duration.ofMillis(250))
                .emaAlpha(1.0)
                .persistenceTicks(2)
                .cooldown(Duration.ofMillis(250))
                .build();

        // Upstream input is full (0.95) BUT its output into downstream is also full (0.92) -> BACKPRESSURED!
        // Downstream is stalled/backpressured or at external sink saturation (input=0.92, output=0.95).
        AdaptiveController controller = new AdaptiveController(
                config,
                6,
                List.of(
                        new AdaptiveController.ManagedStage(
                                "upstream-io", StageKind.IO_BOUND, 1, 16, upstreamTelemetry,
                                true, true, () -> 0.95, () -> 0.92,
                                () -> 0L, () -> 0L, () -> 0L,
                                upstreamTarget::get, upstreamTarget::set),
                        new AdaptiveController.ManagedStage(
                                "downstream-io", StageKind.IO_BOUND, 1, 16, downstreamTelemetry,
                                true, true, () -> 0.92, () -> 0.95,
                                () -> 0L, () -> 0L, () -> 0L,
                                downstreamTarget::get, downstreamTarget::set)),
                null);

        controller.tick();
        clock.advance(Duration.ofMillis(250));
        controller.tick();

        // Neither stage may scale up into a saturated downstream channel; upstream steps down.
        assertTrue(upstreamTarget.get() < 6);
        assertTrue(downstreamTarget.get() < 2);
    }

    @Test
    void scenarioCExternalIoSaturationConvergesAroundKneeViaAimdVegas() {
        AdaptiveConfig config = AdaptiveConfig.defaults();

        // Below knee K: healthy latency within 1.25x baseline + BOTTLENECK -> additive increase.
        int grown = IoConcurrencyLimiter.computeTarget(
                new IoConcurrencyLimiter.IoStageView(
                        "remote-api", StageState.BOTTLENECK, 4, 1, 32,
                        11_000_000.0, 10_000_000.0, 0.0),
                config);
        assertEquals(5, grown);

        // Above knee K: latency exceeds 1.6x baseline (e.g., 25ms vs 10ms) -> multiplicative backoff (* 0.75).
        int backedOffLatency = IoConcurrencyLimiter.computeTarget(
                new IoConcurrencyLimiter.IoStageView(
                        "remote-api", StageState.BOTTLENECK, 12, 1, 32,
                        25_000_000.0, 10_000_000.0, 0.0),
                config);
        assertEquals(9, backedOffLatency);

        // Error spike (> 5% HTTP 429/503) -> multiplicative backoff (* 0.75).
        int backedOffErrors = IoConcurrencyLimiter.computeTarget(
                new IoConcurrencyLimiter.IoStageView(
                        "remote-api", StageState.BOTTLENECK, 12, 1, 32,
                        10_500_000.0, 10_000_000.0, 0.12),
                config);
        assertEquals(9, backedOffErrors);
    }

    @Test
    void liveAdaptivePipelineScalesBottleneckStageAndShutsDownCleanly() {
        List<ScalingDecision> decisions = new CopyOnWriteArrayList<>();

        PipeConfig pipeConfig = PipeConfig.builder()
                .adaptive(AdaptiveConfig.builder()
                        .tickInterval(Duration.ofMillis(40))
                        .persistenceTicks(2)
                        .cooldown(Duration.ofMillis(40))
                        .onDecision(decisions::add)
                        .build())
                .build();

        StageConfig slowIo = StageConfig.ioBound("slow-io")
                .concurrency(1, 1, 8)
                .bufferCapacity(32)
                .build();

        try (PipeExecution<List<Integer>> exec = EazyPipe.<Integer>from(emitter -> {
                    for (int i = 1; i <= 120; i++) {
                        emitter.emit(i);
                    }
                })
                .configure(pipeConfig)
                .map(slowIo, n -> {
                    Thread.sleep(6);
                    return n * 2;
                })
                .startToList()) {

            List<Integer> out = exec.join();
            assertEquals(120, out.size());
            assertFalse(decisions.isEmpty(), "Expected adaptive controller to scale up bottleneck IO stage");
            assertTrue(decisions.stream().anyMatch(d ->
                    d.stageName().equals("slow-io") && d.newConcurrency() > d.previousConcurrency()));
            assertFalse(exec.metricsSnapshot().stages().isEmpty());
        }
    }
}
