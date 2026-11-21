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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTest {

    @Test
    void allowsConfiguredBurstThenThrottles() throws Exception {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10.0, 5);
        long start = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            limiter.acquire();
        }
        long burstMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(burstMs < 60, "Expected 5 burst permits in < 60ms, took " + burstMs + "ms");

        long sixthStart = System.nanoTime();
        limiter.acquire();
        long sixthMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sixthStart);
        assertTrue(sixthMs >= 60, "Expected 6th permit to wait >= 60ms, took " + sixthMs + "ms");
    }

    @Test
    void pacingIsDriftFreeOverManyPermits() throws Exception {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100.0, 1);
        long start = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            limiter.acquire();
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs >= 450, "Expected 50 permits at 100/s to take >= 450ms, took " + elapsedMs + "ms");
    }

    @Test
    void pipelineRateLimitWithBurstAllowsInitialBurst() {
        long start = System.nanoTime();
        List<Integer> out = EazyPipe.of(1, 2, 3, 4)
                .rateLimit(10.0, 4)
                .toList();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertEquals(List.of(1, 2, 3, 4), out);
        assertTrue(elapsedMs < 200, "Expected burst=4 to complete rapidly, took " + elapsedMs + "ms");
    }
}
