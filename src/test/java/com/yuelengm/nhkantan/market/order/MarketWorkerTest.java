package com.yuelengm.nhkantan.market.order;

import static org.junit.Assert.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class MarketWorkerTest {

    @Test
    public void acceptedCommandsDrainInOrderBeforeStop() throws Exception {
        MarketWorker worker = new MarketWorker(100, 1, 1, 10);
        AtomicInteger state = new AtomicInteger();
        CompletableFuture<Integer> last = null;
        for (int i = 0; i < 50; i++) last = worker.submit(state::incrementAndGet);
        worker.close();
        assertEquals(Integer.valueOf(50), last.get(1, TimeUnit.SECONDS));
        assertTrue(
            worker.submit(state::incrementAndGet)
                .isCompletedExceptionally());
    }

    @Test
    public void boundedMailboxRejectsWithoutExecutingOverflow() throws Exception {
        MarketWorker worker = new MarketWorker(1, 1, 1, 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CompletableFuture<Void> blocking = worker.submit(() -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread()
                        .interrupt();
                }
                return null;
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            CompletableFuture<Integer> accepted = worker.submit(() -> 1);
            assertTrue(
                worker.submit(() -> 2)
                    .isCompletedExceptionally());
            release.countDown();
            assertNull(blocking.get(1, TimeUnit.SECONDS));
            assertEquals(Integer.valueOf(1), accepted.get(1, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            worker.close();
        }
    }
}
