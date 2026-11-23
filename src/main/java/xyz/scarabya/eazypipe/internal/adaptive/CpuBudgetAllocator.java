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
package xyz.scarabya.eazypipe.internal.adaptive;

import java.util.ArrayList;
import java.util.List;
import xyz.scarabya.eazypipe.adaptive.StageState;

/**
 * Downstream-aware global permit allocator for {@code CPU_BOUND} stages sharing a finite CPU budget.
 *
 * <p>Aims to equalize drain throughput {@code c_i / s_i} across active stages while respecting
 * {@code [minConcurrency, maxConcurrency]}, never scaling up into {@link StageState#BACKPRESSURED}
 * or {@link StageState#STARVED} stages, and keeping total permits within the configured CPU budget.
 */
public final class CpuBudgetAllocator {

    /**
     * Immutable input view describing one {@code CPU_BOUND} stage during an allocation tick.
     * Stages are expected in pipeline order (upstream to downstream).
     */
    public record CpuStageView(
            String stageName,
            StageState state,
            int currentConcurrency,
            int minConcurrency,
            int maxConcurrency,
            double emaServiceNanos,
            boolean cooldownReady,
            boolean persistent,
            double relativeFlow,
            boolean braked) {
    }

    private CpuBudgetAllocator() {
    }

    /**
     * Computes target concurrencies for the given {@code CPU_BOUND} stages ordered upstream-to-downstream.
     *
     * @param cpuBudget global CPU permit budget
     * @param stages CPU-bound stage views in pipeline order
     * @return array of computed concurrency targets aligned with {@code stages}
     */
    public static int[] allocate(int cpuBudget, List<CpuStageView> stages) {
        if (stages.isEmpty()) {
            return new int[0];
        }

        int minSum = 0;
        for (CpuStageView stage : stages) {
            minSum += stage.minConcurrency();
        }
        int effectiveBudget = Math.max(cpuBudget, minSum);

        int[] allocated = new int[stages.size()];
        boolean[] adjustableActive = new boolean[stages.size()];
        double totalActiveService = 0.0;

        for (int i = 0; i < stages.size(); i++) {
            CpuStageView s = stages.get(i);
            boolean effectivePersistent = s.persistent() || s.braked();
            boolean canChange = s.cooldownReady() && effectivePersistent;
            if (s.braked() && s.state() == StageState.BOTTLENECK) {
                allocated[i] = clamp(s.currentConcurrency(), s.minConcurrency(), s.maxConcurrency());
            } else if (canChange && (s.braked()
                    || s.state() == StageState.BACKPRESSURED
                    || s.state() == StageState.STARVED)) {
                int excess = s.currentConcurrency() - s.minConcurrency();
                int stepDown = excess > 0 ? Math.max(1, excess / 2) : 0;
                allocated[i] = Math.max(s.minConcurrency(), s.currentConcurrency() - stepDown);
            } else if (canChange && s.state() == StageState.BOTTLENECK) {
                allocated[i] = s.minConcurrency();
                adjustableActive[i] = true;
                totalActiveService += stageWeight(s);
            } else {
                allocated[i] = clamp(s.currentConcurrency(), s.minConcurrency(), s.maxConcurrency());
                if (canChange && s.state() == StageState.BALANCED && s.emaServiceNanos() > 0.0) {
                    adjustableActive[i] = true;
                    allocated[i] = s.minConcurrency();
                    totalActiveService += stageWeight(s);
                }
            }
        }

        // Trim any non-bottleneck stages above minimum if total exceeds effective budget.
        int used = sum(allocated);
        for (int i = 0; i < stages.size() && used > effectiveBudget; i++) {
            CpuStageView s = stages.get(i);
            if (!adjustableActive[i] && allocated[i] > s.minConcurrency() && s.cooldownReady()) {
                int trim = Math.min(allocated[i] - s.minConcurrency(), used - effectiveBudget);
                allocated[i] -= trim;
                used -= trim;
            }
        }

        int remaining = Math.max(0, effectiveBudget - sum(allocated));
        if (remaining > 0 && totalActiveService > 0.0) {
            // Compute ideal proportional allocations and distribute remaining permits downstream-first.
            double[] ideal = new double[stages.size()];
            int totalPoolForActive = remaining;
            for (int i = 0; i < stages.size(); i++) {
                if (adjustableActive[i]) {
                    totalPoolForActive += allocated[i];
                }
            }
            for (int i = 0; i < stages.size(); i++) {
                if (adjustableActive[i]) {
                    CpuStageView s = stages.get(i);
                    double share = totalPoolForActive * (stageWeight(s) / totalActiveService);
                    ideal[i] = Math.min(s.maxConcurrency(), Math.max(s.minConcurrency(), share));
                    int floorTarget = Math.min(s.maxConcurrency(), (int) Math.floor(ideal[i]));
                    int gain = Math.max(0, Math.min(remaining, floorTarget - allocated[i]));
                    allocated[i] += gain;
                    remaining -= gain;
                }
            }

            // Assign residual permits prioritizing BOTTLENECK stages from downstream to upstream.
            List<Integer> candidates = new ArrayList<>();
            for (int i = stages.size() - 1; i >= 0; i--) {
                if (adjustableActive[i] && stages.get(i).state() == StageState.BOTTLENECK) {
                    candidates.add(i);
                }
            }
            for (int i = stages.size() - 1; i >= 0; i--) {
                if (adjustableActive[i] && stages.get(i).state() != StageState.BOTTLENECK) {
                    candidates.add(i);
                }
            }
            boolean progressed = true;
            while (remaining > 0 && progressed) {
                progressed = false;
                int bestIdx = -1;
                double bestDeficit = -Double.MAX_VALUE;
                for (int idx : candidates) {
                    CpuStageView s = stages.get(idx);
                    if (allocated[idx] < s.maxConcurrency()) {
                        double deficit = ideal[idx] - allocated[idx];
                        if (deficit > bestDeficit) {
                            bestDeficit = deficit;
                            bestIdx = idx;
                        }
                    }
                }
                if (bestIdx >= 0) {
                    allocated[bestIdx]++;
                    remaining--;
                    progressed = true;
                }
            }
        }

        int[] result = new int[stages.size()];
        for (int i = 0; i < stages.size(); i++) {
            CpuStageView s = stages.get(i);
            boolean effectivePersistent = s.persistent() || s.braked();
            // Respect cooldown/persistence strictly: frozen stages stay at currentConcurrency.
            result[i] = (s.cooldownReady() && effectivePersistent)
                    ? clamp(allocated[i], s.minConcurrency(), s.maxConcurrency())
                    : s.currentConcurrency();
        }
        return result;
    }

    private static double stageWeight(CpuStageView stage) {
        return Math.max(1e-3, stage.relativeFlow()) * effectiveWeight(stage.emaServiceNanos());
    }

    private static double effectiveWeight(double emaServiceNanos) {
        return emaServiceNanos > 0.0 ? emaServiceNanos : 1.0;
    }

    private static int sum(int[] values) {
        int total = 0;
        for (int v : values) {
            total += v;
        }
        return total;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
