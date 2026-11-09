package com.yuelengm.nhkantan.market.order;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class ExchangeRuntimeTest {

    @Test
    public void actorSaveBarrierRestoresBalancesBookAndReplayGuard() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        Map<UUID, Long> cash = new HashMap<>();
        cash.put(buyer, 1000000L);
        cash.put(seller, 1000000L);
        Map<String, ExchangeRuntime.InstrumentSpec> specs = Collections.singletonMap(
            "stock",
            new ExchangeRuntime.InstrumentSpec(new SimulatedInstrument.Settings(), 1, 1000, 0, 1000));
        String checkpoint;
        try (ExchangeRuntime runtime = new ExchangeRuntime(specs, cash, null, 1)) {
            runtime.ready()
                .get(1, TimeUnit.SECONDS);
            runtime.clock(true, 100);
            runtime.submit(runtime.generation(), seller, 1, "stock", OrderBook.Side.SELL, 10000, 10, false, false)
                .get(1, TimeUnit.SECONDS);
            OrderBook.Result result = runtime
                .submit(runtime.generation(), buyer, 1, "stock", OrderBook.Side.BUY, 10000, 3, true, false)
                .get(1, TimeUnit.SECONDS);
            assertEquals(OrderBook.Status.FILLED, result.order.status);
            assertSame(
                result,
                runtime.submit(runtime.generation(), buyer, 1, "stock", OrderBook.Side.BUY, 10000, 3, true, false)
                    .get(1, TimeUnit.SECONDS));
            checkpoint = runtime.checkpoint()
                .get(1, TimeUnit.SECONDS);
        }
        try (ExchangeRuntime restored = new ExchangeRuntime(specs, cash, checkpoint, 1)) {
            restored.ready()
                .get(1, TimeUnit.SECONDS);
            assertEquals(
                7,
                restored.publicSnapshot()
                    .get(1, TimeUnit.SECONDS)
                    .get("stock").asks.get(0).quantity);
            try {
                restored.submit(restored.generation(), buyer, 1, "stock", OrderBook.Side.BUY, 10000, 3, true, false)
                    .get(1, TimeUnit.SECONDS);
                fail("A restored request must not execute again");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IllegalArgumentException);
            }
            assertEquals(
                3,
                restored.publicSnapshot()
                    .get(1, TimeUnit.SECONDS)
                    .get("stock").volume);
        }
    }

    @Test
    public void independentlyScheduledBotsGenerateRealFillsWithoutPlayers() throws Exception {
        Map<String, ExchangeRuntime.InstrumentSpec> specs = Collections.singletonMap(
            "stock",
            new ExchangeRuntime.InstrumentSpec(new SimulatedInstrument.Settings(), 1, 1000, 0, 1000));
        try (ExchangeRuntime runtime = new ExchangeRuntime(specs, Collections.emptyMap(), null, 42)) {
            runtime.ready()
                .get(1, TimeUnit.SECONDS);
            runtime
                .startBots(
                    new int[] { 3, 0, 0, 4, 0 },
                    new long[] { 20, 20, 20, 20, 20 },
                    new double[] { 1, 1, 1, 1, 1 },
                    42)
                .get(1, TimeUnit.SECONDS);
            SimulatedInstrument.Snapshot snapshot = null;
            for (int i = 0; i < 40; i++) {
                runtime.clock(true, i);
                snapshot = runtime.publicSnapshot()
                    .get(1, TimeUnit.SECONDS)
                    .get("stock");
                if (snapshot.volume > 0) break;
            }
            assertNotNull(snapshot);
            assertTrue("Bots never produced an actual matched fill", snapshot.volume > 0);
            assertTrue(snapshot.lastPrice > 0);
            assertFalse(snapshot.candles.isEmpty());
        }
    }

    @Test
    public void oldWorldGenerationCannotSubmitOrders() throws Exception {
        UUID owner = UUID.randomUUID();
        Map<String, ExchangeRuntime.InstrumentSpec> specs = Collections.singletonMap(
            "stock",
            new ExchangeRuntime.InstrumentSpec(new SimulatedInstrument.Settings(), 1, 1000, 0, 1000));
        try (ExchangeRuntime runtime = new ExchangeRuntime(specs, Collections.singletonMap(owner, 1000000L), null, 1)) {
            runtime.ready()
                .get(1, TimeUnit.SECONDS);
            try {
                runtime.submit(UUID.randomUUID(), owner, 1, "stock", OrderBook.Side.BUY, 10000, 1, false, false)
                    .get(1, TimeUnit.SECONDS);
                fail("Stale session accepted");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IllegalArgumentException);
            }
            assertTrue(
                runtime.publicSnapshot()
                    .get(1, TimeUnit.SECONDS)
                    .get("stock").bids.isEmpty());
        }
    }
}
