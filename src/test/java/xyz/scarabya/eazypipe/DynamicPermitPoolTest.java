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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DynamicPermitPoolTest {

    @Test
    void enforcesTargetConcurrencyAndAppliesLazyShrink() throws Exception {
        DynamicPermitPool pool = new DynamicPermitPool(1, 2, 4);
        assertTrue(pool.acquire());
        assertTrue(pool.acquire());
        assertEquals(2, pool.inFlight());

        CountDownLatch thirdAcquired = new CountDownLatch(1);
        Thread third = Thread.ofVirtual().start(() -> {
            try {
                if (pool.acquire()) {
                    thirdAcquired.countDown();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });

        assertFalse(thirdAcquired.await(60, TimeUnit.MILLISECONDS));

        // Lazy shrink: releasing one of two permits while target is 1 keeps inFlight == 1,
        // so third remains parked until target grows or inFlight drops below 1.
        pool.setTarget(1);
        pool.release();
        assertEquals(1, pool.inFlight());
        assertFalse(thirdAcquired.await(60, TimeUnit.MILLISECONDS));

        // Growing target wakes third immediately.
        pool.setTarget(3);
        assertTrue(thirdAcquired.await(2, TimeUnit.SECONDS));
        assertEquals(2, pool.inFlight());

        pool.release();
        pool.release();
        third.join(2000);
    }

    @Test
    void onEofWakesParkedWorkersAndReturnsFalse() throws Exception {
        DynamicPermitPool pool = new DynamicPermitPool(1, 1, 4);
        assertTrue(pool.acquire());

        AtomicInteger falseExits = new AtomicInteger();
        List<Thread> waiters = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            waiters.add(Thread.ofVirtual().start(() -> {
                try {
                    if (!pool.acquire()) {
                        falseExits.incrementAndGet();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        Thread.sleep(60);
        pool.onEof();
        for (Thread waiter : waiters) {
            waiter.join(2000);
            assertFalse(waiter.isAlive());
        }
        assertEquals(3, falseExits.get());
        pool.release();
    }

    @Test
    void abortUnblocksWaitingWorkersWithPipeAbortedException() throws Exception {
        DynamicPermitPool pool = new DynamicPermitPool(1, 1, 2);
        assertTrue(pool.acquire());

        CountDownLatch abortedLatch = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                pool.acquire();
            } catch (Throwable t) {
                thrown.set(t);
                abortedLatch.countDown();
            }
        });

        Thread.sleep(60);
        pool.abort(new IllegalStateException("stop"));

        assertTrue(abortedLatch.await(2, TimeUnit.SECONDS));
        assertTrue(thrown.get() instanceof PipeAbortedException);
        waiter.join(2000);
    }

    @Test
    void validatesPermitBounds() {
        assertThrows(IllegalArgumentException.class, () -> new DynamicPermitPool(0, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> new DynamicPermitPool(2, 1, 3));
        assertThrows(IllegalArgumentException.class, () -> new DynamicPermitPool(1, 3, 2));
    }
}
