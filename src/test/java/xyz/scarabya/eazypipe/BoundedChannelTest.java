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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BoundedChannelTest {

    @Test
    void preservesFifoOrderAndReturnsNullAfterClose() throws Exception {
        BoundedChannel<String> channel = new BoundedChannel<>(4);
        channel.send("a");
        channel.send("b");
        channel.send("c");
        channel.close();

        assertEquals("a", channel.receive());
        assertEquals("b", channel.receive());
        assertEquals("c", channel.receive());
        assertNull(channel.receive());
        assertNull(channel.receive());
    }

    @Test
    void sendBlocksWhenFullUntilItemIsConsumed() throws Exception {
        BoundedChannel<Integer> channel = new BoundedChannel<>(2);
        channel.send(1);
        channel.send(2);

        CountDownLatch senderStarted = new CountDownLatch(1);
        CountDownLatch thirdItemSent = new CountDownLatch(1);

        Thread sender = Thread.ofVirtual().start(() -> {
            senderStarted.countDown();
            try {
                channel.send(3);
                thirdItemSent.countDown();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });

        assertTrue(senderStarted.await(2, TimeUnit.SECONDS));
        assertFalse(thirdItemSent.await(80, TimeUnit.MILLISECONDS));

        assertEquals(1, channel.receive());
        assertTrue(thirdItemSent.await(2, TimeUnit.SECONDS));
        assertEquals(2, channel.receive());
        assertEquals(3, channel.receive());
        sender.join(2000);
    }

    @Test
    void receiveBlocksWhenEmptyUntilItemArrives() throws Exception {
        BoundedChannel<String> channel = new BoundedChannel<>(2);
        CountDownLatch receiverStarted = new CountDownLatch(1);
        CountDownLatch itemReceived = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();

        Thread receiver = Thread.ofVirtual().start(() -> {
            receiverStarted.countDown();
            try {
                received.set(channel.receive());
                itemReceived.countDown();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });

        assertTrue(receiverStarted.await(2, TimeUnit.SECONDS));
        assertFalse(itemReceived.await(80, TimeUnit.MILLISECONDS));

        channel.send("hello");
        assertTrue(itemReceived.await(2, TimeUnit.SECONDS));
        assertEquals("hello", received.get());
        receiver.join(2000);
    }

    @Test
    void closeDrainsRemainingItemsAcrossMultipleConsumers() throws Exception {
        BoundedChannel<Integer> channel = new BoundedChannel<>(32);
        for (int i = 0; i < 20; i++) {
            channel.send(i);
        }
        channel.close();

        Set<Integer> consumed = ConcurrentHashMap.newKeySet();
        List<Thread> consumers = new ArrayList<>();
        for (int c = 0; c < 4; c++) {
            consumers.add(Thread.ofVirtual().start(() -> {
                try {
                    Integer item;
                    while ((item = channel.receive()) != null) {
                        consumed.add(item);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        for (Thread consumer : consumers) {
            consumer.join(2000);
            assertFalse(consumer.isAlive());
        }
        assertEquals(20, consumed.size());
    }

    @Test
    void abortUnblocksWaitingSendersAndReceiversImmediately() throws Exception {
        BoundedChannel<Integer> fullChannel = new BoundedChannel<>(1);
        fullChannel.send(1);
        BoundedChannel<Integer> emptyChannel = new BoundedChannel<>(1);

        CountDownLatch senderFailed = new CountDownLatch(1);
        CountDownLatch receiverFailed = new CountDownLatch(1);
        AtomicReference<Throwable> senderError = new AtomicReference<>();
        AtomicReference<Throwable> receiverError = new AtomicReference<>();
        RuntimeException rootCause = new RuntimeException("boom");

        Thread sender = Thread.ofVirtual().start(() -> {
            try {
                fullChannel.send(2);
            } catch (Throwable t) {
                senderError.set(t);
                senderFailed.countDown();
            }
        });

        Thread receiver = Thread.ofVirtual().start(() -> {
            try {
                emptyChannel.receive();
            } catch (Throwable t) {
                receiverError.set(t);
                receiverFailed.countDown();
            }
        });

        Thread.sleep(60);
        fullChannel.abort(rootCause);
        emptyChannel.abort(rootCause);

        assertTrue(senderFailed.await(2, TimeUnit.SECONDS));
        assertTrue(receiverFailed.await(2, TimeUnit.SECONDS));
        assertTrue(senderError.get() instanceof PipeAbortedException);
        assertSame(rootCause, senderError.get().getCause());
        assertTrue(receiverError.get() instanceof PipeAbortedException);
        assertSame(rootCause, receiverError.get().getCause());
        sender.join(2000);
        receiver.join(2000);
    }

    @Test
    void rejectsInvalidCapacityNullItemsAndSendAfterClose() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedChannel<>(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedChannel<>(-5));

        BoundedChannel<String> channel = new BoundedChannel<>(2);
        assertThrows(NullPointerException.class, () -> channel.send(null));

        channel.close();
        assertThrows(IllegalStateException.class, () -> channel.send("late"));
    }
}
