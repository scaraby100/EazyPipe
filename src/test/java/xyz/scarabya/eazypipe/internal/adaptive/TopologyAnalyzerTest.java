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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import xyz.scarabya.eazypipe.StageKind;
import xyz.scarabya.eazypipe.adaptive.AdaptiveConfig;
import xyz.scarabya.eazypipe.adaptive.StageState;

class TopologyAnalyzerTest {

    @Test
    void computesRelativeFlowsAndDominantBottleneck() {
        // source (0), filter 0.2x (1), flatMap 10x (2), batch 0.1x (3), sink (4)
        double[] flows = TopologyAnalyzer.relativeFlows(new double[] {1.0, 0.2, 10.0, 0.1, 1.0});
        assertArrayEquals(new double[] {1.0, 1.0, 0.2, 2.0, 0.2}, flows, 1e-6);

        double[] svc = new double[] {0.0, 2_000_000.0, 1_000_000.0, 4_000_000.0, 5_000_000.0};
        int[] targets = new int[] {1, 2, 2, 1, 2};
        // Loads: i=1: 1*2/2=1.0, i=2: 0.2*1/2=0.1, i=3: 2.0*4/1=8.0, i=4: 0.2*5/2=0.5 -> index 3
        assertEquals(3, TopologyAnalyzer.dominantBottleneck(flows, svc, targets));

        assertEquals(-1, TopologyAnalyzer.dominantBottleneck(
                new double[] {1.0, 1.0}, new double[] {5_000_000.0, 0.0}, new int[] {1, 1}));
    }

    @Test
    void downstreamBrakeStopsPropagationWhenIntermediateChannelIsBelowDeadbandHigh() {
        AdaptiveConfig.Tuning tuning = AdaptiveConfig.Tuning.balanced(); // deadbandHigh = 0.70

        StageState[] states = {
                StageState.BOTTLENECK,
                StageState.BALANCED,
                StageState.BALANCED,
                StageState.BACKPRESSURED
        };
        StageKind[] kinds = {
                StageKind.IO_BOUND,
                StageKind.IO_BOUND,
                StageKind.IO_BOUND,
                StageKind.IO_BOUND
        };
        boolean[] hasOutput = {true, true, true, false};
        // Stage 2 output = 0.75 (>0.70 -> braked), Stage 1 output = 0.50 (<=0.70 -> stops cascade), Stage 0 output = 0.90
        double[] outFills = {0.90, 0.50, 0.75, 0.0};
        double[] svc = {1e6, 1e6, 1e6, 1e6};
        double[] base = {1e6, 1e6, 1e6, 1e6};
        double[] retry = {0.0, 0.0, 0.0, 0.0};

        boolean[] braked = TopologyAnalyzer.braked(states, kinds, hasOutput, outFills, svc, base, retry, tuning);
        assertFalse(braked[3]);
        assertTrue(braked[2]);
        assertFalse(braked[1]);
        assertFalse(braked[0]);
    }
}
