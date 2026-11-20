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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import xyz.scarabya.eazypipe.StageKind;
import xyz.scarabya.eazypipe.metrics.PipelineMetricsListener;
import xyz.scarabya.eazypipe.metrics.StageMetricsSnapshot;

/**
 * Pipeline-wide downstream-aware adaptive controller periodically sampling {@link StageTelemetry}
 * and rebalancing {@code CPU_BOUND} and {@code IO_BOUND} stage concurrencies to prevent bottleneck shifting.
 */
public final class AdaptiveController {

    private static final int MAX_DECISION_HISTORY = 256;

    /**
     * Public registration descriptor connecting a stage's telemetry and channel metrics to its permit target applier.
     */
    public record ManagedStage(
            String stageName,
            StageKind stageKind,
            int minConcurrency,
            int maxConcurrency,
            StageTelemetry telemetry,
            boolean hasInput,
            boolean hasOutput,
            DoubleSupplier inputFillSupplier,
            DoubleSupplier outputFillSupplier,
            LongSupplier inputWaitNanosSupplier,
            LongSupplier outputWaitNanosSupplier,
            LongSupplier droppedCountSupplier,
            IntSupplier currentTargetSupplier,
            IntConsumer targetApplier) {

        public ManagedStage {
            Objects.requireNonNull(stageName, "stageName must not be null");
            Objects.requireNonNull(stageKind, "stageKind must not be null");
            Objects.requireNonNull(telemetry, "telemetry must not be null");
            Objects.requireNonNull(inputFillSupplier, "inputFillSupplier must not be null");
            Objects.requireNonNull(outputFillSupplier, "outputFillSupplier must not be null");
            Objects.requireNonNull(inputWaitNanosSupplier, "inputWaitNanosSupplier must not be null");
            Objects.requireNonNull(outputWaitNanosSupplier, "outputWaitNanosSupplier must not be null");
            Objects.requireNonNull(droppedCountSupplier, "droppedCountSupplier must not be null");
            Objects.requireNonNull(currentTargetSupplier, "currentTargetSupplier must not be null");
            Objects.requireNonNull(targetApplier, "targetApplier must not be null");
        }
    }

    private static final class StageRuntimeState {
        private final ManagedStage descriptor;
        private StageState lastState = StageState.BALANCED;
        private int consecutiveTicks;
        private Instant lastScaledAt;
        private StageTelemetry.Snapshot lastSnapshot;

        private StageRuntimeState(ManagedStage descriptor) {
            this.descriptor = descriptor;
            this.lastSnapshot = descriptor.telemetry().latestSnapshot();
        }
    }

    private final AdaptiveConfig config;
    private final List<StageRuntimeState> stages;
    private final PipelineMetricsListener metricsListener;
    private final Object tickLock = new Object();
    private final ArrayDeque<ScalingDecision> decisionsHistory = new ArrayDeque<>();

    private Instant lastTickAt;
    private ScheduledExecutorService scheduler;

    public AdaptiveController(
            AdaptiveConfig config,
            List<ManagedStage> stages,
            PipelineMetricsListener metricsListener) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(stages, "stages must not be null");
        this.metricsListener = metricsListener != null ? metricsListener : PipelineMetricsListener.noop();
        List<StageRuntimeState> list = new ArrayList<>(stages.size());
        for (ManagedStage stage : stages) {
            list.add(new StageRuntimeState(stage));
        }
        this.stages = List.copyOf(list);
    }

    /**
     * Starts periodic background evaluation if {@link AdaptiveConfig#enabled()} is {@code true}.
     */
    public synchronized void start() {
        if (!config.enabled() || scheduler != null) {
            return;
        }
        lastTickAt = config.clock().instant();
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("eazypipe-adaptive", 0).daemon(true).factory());
        this.scheduler = exec;
        long periodNanos = config.tickInterval().toNanos();
        exec.scheduleAtFixedRate(this::safeTick, periodNanos, periodNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Stops periodic background evaluation cleanly. Safe to call multiple times.
     */
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void safeTick() {
        try {
            tick();
        } catch (Throwable ignored) {
            // Keep adaptive loop resilient against transient listener exceptions.
        }
    }

    /**
     * Executes a single telemetry sampling and concurrency control tick.
     * Exposed publicly for deterministic testing with an injected {@link java.time.Clock}.
     *
     * @return list of {@link ScalingDecision}s applied during this tick
     */
    public List<ScalingDecision> tick() {
        synchronized (tickLock) {
            Instant now = config.clock().instant();
            long windowNanos = lastTickAt == null
                    ? config.tickInterval().toNanos()
                    : Duration.between(lastTickAt, now).toNanos();
            if (windowNanos <= 0L) {
                windowNanos = config.tickInterval().toNanos();
            }
            lastTickAt = now;

            StageState[] states = new StageState[stages.size()];
            boolean[] persistent = new boolean[stages.size()];
            boolean[] cooldownReady = new boolean[stages.size()];

            // 1. Sample telemetry for every stage and classify state.
            for (int i = 0; i < stages.size(); i++) {
                StageRuntimeState rs = stages.get(i);
                ManagedStage d = rs.descriptor;
                StageTelemetry.Snapshot snap = d.telemetry().sample(
                        windowNanos,
                        config.emaAlpha(),
                        d.inputFillSupplier().getAsDouble(),
                        d.outputFillSupplier().getAsDouble(),
                        d.inputWaitNanosSupplier().getAsLong(),
                        d.outputWaitNanosSupplier().getAsLong(),
                        d.droppedCountSupplier().getAsLong());
                rs.lastSnapshot = snap;
                StageState classified = d.telemetry().classify(snap, config, d.hasInput(), d.hasOutput());
                states[i] = classified;

                if (classified == rs.lastState) {
                    rs.consecutiveTicks++;
                } else {
                    rs.lastState = classified;
                    rs.consecutiveTicks = 1;
                }

                persistent[i] = rs.consecutiveTicks >= config.persistenceTicks();
                cooldownReady[i] = rs.lastScaledAt == null
                        || Duration.between(rs.lastScaledAt, now).compareTo(config.cooldown()) >= 0;

                metricsListener.onStageMetrics(toMetricsSnapshot(rs, classified));
            }

            if (!config.enabled()) {
                return List.of();
            }

            List<ScalingDecision> emitted = new ArrayList<>();

            // 2. Inspect IO_BOUND stages strictly from downstream to upstream.
            for (int i = stages.size() - 1; i >= 0; i--) {
                StageRuntimeState rs = stages.get(i);
                ManagedStage d = rs.descriptor;
                if (!d.hasInput() || d.stageKind() != StageKind.IO_BOUND || d.minConcurrency() == d.maxConcurrency()) {
                    continue;
                }
                if (!persistent[i] || !cooldownReady[i]) {
                    continue;
                }

                int current = d.currentTargetSupplier().getAsInt();
                StageTelemetry.Snapshot snap = rs.lastSnapshot;
                IoConcurrencyLimiter.IoStageView view = new IoConcurrencyLimiter.IoStageView(
                        d.stageName(),
                        states[i],
                        current,
                        d.minConcurrency(),
                        d.maxConcurrency(),
                        snap.emaServiceNanos(),
                        snap.baselineServiceNanos(),
                        snap.emaErrorRate());
                int next = IoConcurrencyLimiter.computeTarget(view, config);
                if (next != current) {
                    emitted.add(applyChange(rs, states[i], current, next, now, "IO AIMD/Vegas: " + states[i]));
                }
            }

            // 3. Globally allocate CPU_BOUND budget across all CPU stages.
            List<CpuBudgetAllocator.CpuStageView> cpuViews = new ArrayList<>();
            List<StageRuntimeState> cpuStages = new ArrayList<>();
            List<StageState> cpuStates = new ArrayList<>();
            for (int i = 0; i < stages.size(); i++) {
                StageRuntimeState rs = stages.get(i);
                ManagedStage d = rs.descriptor;
                if (d.stageKind() == StageKind.CPU_BOUND && d.hasInput()) {
                    cpuStages.add(rs);
                    cpuStates.add(states[i]);
                    cpuViews.add(new CpuBudgetAllocator.CpuStageView(
                            d.stageName(),
                            states[i],
                            d.currentTargetSupplier().getAsInt(),
                            d.minConcurrency(),
                            d.maxConcurrency(),
                            rs.lastSnapshot.emaServiceNanos(),
                            cooldownReady[i],
                            persistent[i]));
                }
            }

            if (!cpuViews.isEmpty()) {
                Map<String, Integer> targets = CpuBudgetAllocator.allocate(config.cpuBudget(), cpuViews);
                for (int i = cpuStages.size() - 1; i >= 0; i--) {
                    StageRuntimeState rs = cpuStages.get(i);
                    ManagedStage d = rs.descriptor;
                    int current = d.currentTargetSupplier().getAsInt();
                    int next = targets.getOrDefault(d.stageName(), current);
                    if (next != current && d.minConcurrency() < d.maxConcurrency()) {
                        emitted.add(applyChange(
                                rs,
                                cpuStates.get(i),
                                current,
                                next,
                                now,
                                "CPU budget rebalance: " + cpuStates.get(i)));
                    }
                }
            }

            return List.copyOf(emitted);
        }
    }

    private ScalingDecision applyChange(
            StageRuntimeState rs,
            StageState state,
            int previous,
            int next,
            Instant now,
            String reason) {
        ManagedStage d = rs.descriptor;
        d.targetApplier().accept(next);
        rs.lastScaledAt = now;
        StageTelemetry.Snapshot snap = rs.lastSnapshot;
        ScalingDecision decision = new ScalingDecision(
                now,
                d.stageName(),
                d.stageKind(),
                state,
                previous,
                next,
                reason,
                snap.emaInputFill(),
                snap.emaOutputFill(),
                snap.emaServiceMillis());
        if (decisionsHistory.size() >= MAX_DECISION_HISTORY) {
            decisionsHistory.removeFirst();
        }
        decisionsHistory.addLast(decision);
        if (config.onDecision() != null) {
            config.onDecision().accept(decision);
        }
        metricsListener.onScalingDecision(decision);
        return decision;
    }

    public List<StageMetricsSnapshot> currentStageSnapshots() {
        synchronized (tickLock) {
            List<StageMetricsSnapshot> snapshots = new ArrayList<>(stages.size());
            for (StageRuntimeState rs : stages) {
                snapshots.add(toMetricsSnapshot(rs, rs.lastState));
            }
            return List.copyOf(snapshots);
        }
    }

    public List<ScalingDecision> recentDecisions() {
        synchronized (tickLock) {
            return List.copyOf(decisionsHistory);
        }
    }

    private static StageMetricsSnapshot toMetricsSnapshot(StageRuntimeState rs, StageState state) {
        ManagedStage d = rs.descriptor;
        StageTelemetry.Snapshot snap = rs.lastSnapshot != null
                ? rs.lastSnapshot
                : d.telemetry().latestSnapshot();
        return new StageMetricsSnapshot(
                d.stageName(),
                d.stageKind(),
                state,
                d.currentTargetSupplier().getAsInt(),
                d.telemetry().activeWorkers(),
                d.telemetry().completedItems(),
                d.telemetry().errorItems(),
                d.droppedCountSupplier().getAsLong(),
                snap.emaServiceMillis(),
                snap.emaThroughputPerSec(),
                snap.emaInputFill(),
                snap.emaOutputFill());
    }
}
