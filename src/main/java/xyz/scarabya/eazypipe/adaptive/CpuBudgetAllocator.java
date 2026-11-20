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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            boolean persistent) {
    }

    private CpuBudgetAllocator() {
    }

    /**
     * Computes target concurrencies for the given {@code CPU_BOUND} stages ordered upstream-to-downstream.
     *
     * @param cpuBudget global CPU permit budget
     * @param stages CPU-bound stage views in pipeline order
     * @return map of stage name to computed concurrency target
     */
    public static Map<String, Integer> allocate(int cpuBudget, List<CpuStageView> stages) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (stages.isEmpty()) {
            return result;
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
            boolean canChange = s.cooldownReady() && s.persistent();
            if (canChange && (s.state() == StageState.BACKPRESSURED || s.state() == StageState.STARVED)) {
                int excess = s.currentConcurrency() - s.minConcurrency();
                int stepDown = excess > 0 ? Math.max(1, excess / 2) : 0;
                allocated[i] = Math.max(s.minConcurrency(), s.currentConcurrency() - stepDown);
            } else if (canChange && s.state() == StageState.BOTTLENECK) {
                allocated[i] = s.minConcurrency();
                adjustableActive[i] = true;
                totalActiveService += effectiveWeight(s.emaServiceNanos());
            } else {
                allocated[i] = clamp(s.currentConcurrency(), s.minConcurrency(), s.maxConcurrency());
                if (canChange && s.state() == StageState.BALANCED && s.emaServiceNanos() > 0.0) {
                    adjustableActive[i] = true;
                    allocated[i] = s.minConcurrency();
                    totalActiveService += effectiveWeight(s.emaServiceNanos());
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
                    double share = totalPoolForActive * (effectiveWeight(s.emaServiceNanos()) / totalActiveService);
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

        for (int i = 0; i < stages.size(); i++) {
            CpuStageView s = stages.get(i);
            // Respect cooldown/persistence strictly: frozen stages stay at currentConcurrency.
            int finalTarget = (s.cooldownReady() && s.persistent())
                    ? clamp(allocated[i], s.minConcurrency(), s.maxConcurrency())
                    : s.currentConcurrency();
            result.put(s.stageName(), finalTarget);
        }
        return result;
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
