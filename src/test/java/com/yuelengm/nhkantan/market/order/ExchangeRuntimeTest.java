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
            long started = System.nanoTime();
            long deadline = started + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < deadline) {
                runtime.clock(true, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) / 50);
                snapshot = runtime.publicSnapshot()
                    .get(1, TimeUnit.SECONDS)
                    .get("stock");
                if (snapshot.volume > 0) break;
                // Snapshot commands may all drain in one worker pump, before scheduled bot events run.
                // Yield between polls and keep the active-clock heartbeat alive while waiting for a real fill.
                Thread.sleep(25);
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

    @Test
    public void dailyExpiryLadderRollsRestoresAndFitsSelectedChainPacket() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        Map<UUID, Long> balances = new HashMap<>();
        balances.put(buyer, 10000000L);
        balances.put(seller, 10000000L);
        Map<String, ExchangeRuntime.InstrumentSpec> specs = Collections.singletonMap(
            "stock",
            new ExchangeRuntime.InstrumentSpec(new SimulatedInstrument.Settings(), 1, 1000, 0, 1000));
        String saved;
        try (ExchangeRuntime runtime = new ExchangeRuntime(specs, balances, null, 42)) {
            runtime.ready()
                .get(1, TimeUnit.SECONDS);
            runtime.clock(true, 10);
            runtime.submit(runtime.generation(), seller, 1, "stock", OrderBook.Side.SELL, 10000, 10, false, false)
                .get(1, TimeUnit.SECONDS);
            runtime.submit(runtime.generation(), buyer, 1, "stock", OrderBook.Side.BUY, 10000, 10, true, false)
                .get(1, TimeUnit.SECONDS);
            runtime.protect(runtime.generation(), buyer, 2, "stock", 2, 9000, 11000)
                .get(1, TimeUnit.SECONDS);
            runtime.publicSnapshot()
                .get(1, TimeUnit.SECONDS);
            ExchangeRuntime.PrivateSnapshot snapshot = runtime.privateSnapshot(buyer)
                .get(1, TimeUnit.SECONDS);
            assertEquals(196, snapshot.contracts.size());
            assertEquals(9000, snapshot.protections.get("stock").stopPrice);
            assertTrue(
                runtime.privateSnapshot(seller)
                    .get(1, TimeUnit.SECONDS).protections.isEmpty());
            com.yuelengm.nhkantan.options.OptionMarket.Contract contract = snapshot.contracts.get(0);
            com.yuelengm.nhkantan.options.OptionMarket.Quote quote = snapshot.quotes.get(contract.id);
            com.yuelengm.nhkantan.options.OptionMarket.Holding option = runtime
                .buyOption(runtime.generation(), buyer, 3, contract.id, quote.id, 2, Long.MAX_VALUE)
                .get(1, TimeUnit.SECONDS);
            com.yuelengm.nhkantan.options.OptionMarket.Holding exited = runtime
                .exitOption(runtime.generation(), buyer, 4, option.id, 1, false, quote.id, quote.bidUnitCents)
                .get(1, TimeUnit.SECONDS);
            assertEquals(1, exited.remainingQuantity());
            assertSame(
                exited,
                runtime.exitOption(runtime.generation(), buyer, 4, option.id, 1, false, quote.id, quote.bidUnitCents)
                    .get(1, TimeUnit.SECONDS));
            snapshot = runtime.privateSnapshot(buyer)
                .get(1, TimeUnit.SECONDS);
            String full = new com.google.gson.Gson().toJson(snapshot);
            assertTrue(full.length() > 32693);
            for (int day = 1; day <= 14; day++) {
                String filtered = com.yuelengm.nhkantan.market.view.OptionChainView.filter(full, "stock", day, 10);
                assertTrue(filtered.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32693);
                com.google.gson.JsonObject chain = new com.google.gson.JsonParser().parse(filtered)
                    .getAsJsonObject();
                assertEquals(
                    14,
                    chain.getAsJsonArray("contracts")
                        .size());
                assertEquals(
                    14,
                    chain.getAsJsonObject("quotes")
                        .entrySet()
                        .size());
            }
            runtime.clock(true, 24010);
            runtime.publicSnapshot()
                .get(1, TimeUnit.SECONDS);
            assertEquals(
                210,
                runtime.privateSnapshot(buyer)
                    .get(1, TimeUnit.SECONDS).contracts.size());
            saved = runtime.checkpoint()
                .get(1, TimeUnit.SECONDS);
        }
        specs.get("stock").simulation.volatility = 11;
        try (ExchangeRuntime restored = new ExchangeRuntime(specs, balances, saved, 42)) {
            restored.ready()
                .get(1, TimeUnit.SECONDS);
            restored.publicSnapshot()
                .get(1, TimeUnit.SECONDS);
            assertEquals(
                210,
                restored.privateSnapshot(buyer)
                    .get(1, TimeUnit.SECONDS).contracts.size());
            String checkpoint = restored.checkpoint()
                .get(1, TimeUnit.SECONDS);
            String instrument = new com.google.gson.JsonParser().parse(checkpoint)
                .getAsJsonObject()
                .getAsJsonObject("instruments")
                .get("stock")
                .getAsString();
            assertEquals(
                11,
                new com.google.gson.JsonParser().parse(instrument)
                    .getAsJsonObject()
                    .getAsJsonObject("settings")
                    .get("volatility")
                    .getAsDouble(),
                0);
        }
    }

    @Test
    public void closeAllRetriesBeyondEightContractsAndSurvivesRestart() throws Exception {
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
        Map<UUID, Long> cash = new HashMap<>();
        cash.put(owner, 10000000L);
        cash.put(other, 10000000L);
        Map<String, ExchangeRuntime.InstrumentSpec> specs = Collections.singletonMap(
            "stock",
            new ExchangeRuntime.InstrumentSpec(new SimulatedInstrument.Settings(), 1, 1000, 0, 1000));
        String saved;
        try (ExchangeRuntime runtime = new ExchangeRuntime(specs, cash, null, 1)) {
            runtime.ready()
                .get(1, TimeUnit.SECONDS);
            runtime.submit(runtime.generation(), other, 1, "stock", OrderBook.Side.SELL, 10000, 10, false, false)
                .get(1, TimeUnit.SECONDS);
            runtime.submit(runtime.generation(), owner, 1, "stock", OrderBook.Side.BUY, 10000, 10, true, false)
                .get(1, TimeUnit.SECONDS);
            runtime.submit(runtime.generation(), other, 2, "stock", OrderBook.Side.BUY, 10000, 8, false, false)
                .get(1, TimeUnit.SECONDS);
            runtime.closePosition(runtime.generation(), owner, 2, "stock")
                .get(1, TimeUnit.SECONDS);
            ExchangeRuntime.PrivateSnapshot partial = runtime.privateSnapshot(owner)
                .get(1, TimeUnit.SECONDS);
            assertEquals(2, partial.positions.get("stock").quantity);
            assertTrue(partial.closing.contains("stock"));
            // Retrying the same UI request must not create another close operation.
            runtime.closePosition(runtime.generation(), owner, 2, "stock")
                .get(1, TimeUnit.SECONDS);
            try {
                runtime.submit(runtime.generation(), owner, 3, "stock", OrderBook.Side.BUY, 10000, 1, false, false)
                    .get(1, TimeUnit.SECONDS);
                fail("New risk allowed while close-all pending");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IllegalArgumentException);
            }
            saved = runtime.checkpoint()
                .get(1, TimeUnit.SECONDS);
        }
        try (ExchangeRuntime restored = new ExchangeRuntime(specs, cash, saved, 1)) {
            restored.ready()
                .get(1, TimeUnit.SECONDS);
            restored.submit(restored.generation(), other, 3, "stock", OrderBook.Side.BUY, 10000, 2, false, false)
                .get(1, TimeUnit.SECONDS);
            restored.publicSnapshot()
                .get(1, TimeUnit.SECONDS);
            ExchangeRuntime.PrivateSnapshot flat = restored.privateSnapshot(owner)
                .get(1, TimeUnit.SECONDS);
            assertEquals(0, flat.positions.get("stock").quantity);
            assertFalse(flat.closing.contains("stock"));
            restored.publicSnapshot()
                .get(1, TimeUnit.SECONDS);
            assertEquals(
                0,
                restored.privateSnapshot(owner)
                    .get(1, TimeUnit.SECONDS).positions.get("stock").quantity);
        }
    }
}
