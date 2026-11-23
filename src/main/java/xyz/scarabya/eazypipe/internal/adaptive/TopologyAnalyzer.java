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

import java.util.Arrays;
import xyz.scarabya.eazypipe.StageKind;
import xyz.scarabya.eazypipe.adaptive.AdaptiveConfig;
import xyz.scarabya.eazypipe.adaptive.StageState;

/**
 * Pure pipeline-topology analyzer computing cumulative fan-out flows, dominant bottlenecks,
 * and one-tick downstream backpressure brakes across the main stage chain.
 */
final class TopologyAnalyzer {

    record Analysis(
            double[] relativeFlows,
            boolean[] braked,
            StageState[] downstreamStates,
            String dominantBottleneck) {
    }

    private TopologyAnalyzer() {
    }

    static Analysis analyze(
            String[] stageNames,
            int[] stageIndices,
            StageKind[] kinds,
            boolean[] hasOutput,
            StageState[] states,
            StageTelemetry.Snapshot[] snapshots,
            int[] targets,
            AdaptiveConfig.Tuning tuning) {
        int total = stageNames.length;
        int mainCount = 0;
        for (int idx : stageIndices) {
            if (idx >= 0) {
                mainCount++;
            }
        }
        int[] mainPos = new int[mainCount];
        double[] mainFanOuts = new double[mainCount];
        StageState[] mainStates = new StageState[mainCount];
        StageKind[] mainKinds = new StageKind[mainCount];
        boolean[] mainHasOut = new boolean[mainCount];
        double[] mainOutFills = new double[mainCount];
        double[] mainSvc = new double[mainCount];
        double[] mainBaseSvc = new double[mainCount];
        double[] mainRetry = new double[mainCount];
        int[] mainTargets = new int[mainCount];

        int k = 0;
        for (int i = 0; i < total; i++) {
            if (stageIndices[i] >= 0) {
                mainPos[k] = i;
                mainFanOuts[k] = snapshots[i].emaFanOut();
                mainStates[k] = states[i];
                mainKinds[k] = kinds[i];
                mainHasOut[k] = hasOutput[i];
                mainOutFills[k] = snapshots[i].emaOutputFill();
                mainSvc[k] = snapshots[i].emaServiceNanos();
                mainBaseSvc[k] = snapshots[i].baselineServiceNanos();
                mainRetry[k] = snapshots[i].emaRetryRate();
                mainTargets[k] = targets[i];
                k++;
            }
        }

        double[] mainFlows = relativeFlows(mainFanOuts);
        boolean[] mainBraked = braked(
                mainStates, mainKinds, mainHasOut, mainOutFills, mainSvc, mainBaseSvc, mainRetry, tuning);
        int domMainIdx = dominantBottleneck(mainFlows, mainSvc, mainTargets);
        String dominantName = domMainIdx >= 0 ? stageNames[mainPos[domMainIdx]] : "";

        double[] relativeFlows = new double[total];
        Arrays.fill(relativeFlows, Double.NaN);
        boolean[] braked = new boolean[total];
        StageState[] downstreamStates = new StageState[total];
        for (int m = 0; m < mainCount; m++) {
            int pos = mainPos[m];
            relativeFlows[pos] = mainFlows[m];
            braked[pos] = mainBraked[m];
            downstreamStates[pos] = (m + 1 < mainCount) ? mainStates[m + 1] : null;
        }
        return new Analysis(relativeFlows, braked, downstreamStates, dominantName);
    }

    static double[] relativeFlows(double[] fanOuts) {
        int n = fanOuts.length;
        double[] flows = new double[n];
        if (n == 0) {
            return flows;
        }
        flows[0] = 1.0;
        if (n > 1) {
            flows[1] = 1.0;
        }
        for (int i = 2; i < n; i++) {
            flows[i] = clampFlow(flows[i - 1] * sanitizeFanOut(fanOuts[i - 1]));
        }
        return flows;
    }

    static int dominantBottleneck(double[] relativeFlows, double[] emaServiceNanos, int[] targets) {
        int best = -1;
        double bestLoad = -1.0;
        for (int i = 1; i < relativeFlows.length; i++) {
            double svc = emaServiceNanos[i];
            if (svc <= 0.0 || Double.isNaN(svc)) {
                continue;
            }
            double load = (relativeFlows[i] * svc) / Math.max(1, targets[i]);
            if (load > bestLoad) {
                bestLoad = load;
                best = i;
            }
        }
        return best;
    }

    static boolean[] braked(
            StageState[] states,
            StageKind[] kinds,
            boolean[] hasOutput,
            double[] emaOutputFill,
            double[] emaServiceNanos,
            double[] baselineServiceNanos,
            double[] emaRetryRate,
            AdaptiveConfig.Tuning tuning) {
        int n = states.length;
        boolean[] braked = new boolean[n];
        boolean pressure = false;
        for (int i = n - 1; i >= 0; i--) {
            braked[i] = pressure && hasOutput[i] && emaOutputFill[i] > tuning.deadbandHigh();
            boolean latencySaturated = baselineServiceNanos[i] > 0.0
                    && emaServiceNanos[i] > tuning.ioLatencyBeta() * baselineServiceNanos[i];
            boolean isPressureSource = states[i] == StageState.BACKPRESSURED
                    || (kinds[i] == StageKind.IO_BOUND
                            && (latencySaturated || emaRetryRate[i] > tuning.retryRateThreshold()));
            pressure = isPressureSource || braked[i];
        }
        return braked;
    }

    private static double sanitizeFanOut(double fanOut) {
        if (Double.isNaN(fanOut) || fanOut <= 0.0) {
            return 1.0;
        }
        return Math.max(1e-3, Math.min(1e4, fanOut));
    }

    private static double clampFlow(double flow) {
        if (Double.isNaN(flow) || flow <= 0.0) {
            return 1.0;
        }
        return Math.max(1e-6, Math.min(1e6, flow));
    }
}
