package com.yuelengm.nhkantan.market.order;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.Test;

import com.google.gson.Gson;

public class MarketDynamicsTest {

    @Test
    public void phasesHavePersistentDirectionBreakoutsAndPartialRetracements() {
        SimulatedInstrument.Settings settings = new SimulatedInstrument.Settings();
        settings.regimeMinSteps = 100;
        settings.regimeMaxSteps = 160;
        settings.volatility = 0;
        MarketDynamics dynamics = new MarketDynamics(10000, 42);
        EnumSet<MarketDynamics.Regime> seen = EnumSet.noneOf(MarketDynamics.Regime.class);
        int trends = 0, breakouts = 0, reversions = 0;
        MarketDynamics.Regime previous = null;
        double first = 10000, last = 10000;
        for (int i = 0; i < 6000; i++) {
            double price = dynamics.step(settings, 0);
            MarketDynamics.Regime regime = dynamics.regime();
            seen.add(regime);
            if (regime != previous) {
                if (previous == MarketDynamics.Regime.TREND_UP) {
                    assertTrue(last > first * 1.015);
                    trends++;
                }
                if (previous == MarketDynamics.Regime.TREND_DOWN) {
                    assertTrue(last < first * 0.985);
                    trends++;
                }
                if (previous == MarketDynamics.Regime.BREAKOUT_UP || previous == MarketDynamics.Regime.BREAKOUT_DOWN) {
                    assertTrue(Math.abs(last / first - 1) > 0.03);
                    breakouts++;
                }
                if (previous == MarketDynamics.Regime.REVERSION && Math.abs(last - first) > 10) reversions++;
                first = price;
                previous = regime;
            }
            last = price;
        }
        assertEquals(EnumSet.allOf(MarketDynamics.Regime.class), seen);
        assertTrue(trends > 5);
        assertTrue(breakouts > 2);
        assertTrue(reversions > 2);
    }

    @Test
    public void phaseAndRandomStateContinueExactlyAcrossCheckpointAndRespectBounds() {
        SimulatedInstrument.Settings settings = new SimulatedInstrument.Settings();
        settings.lowerPrice = 9000;
        settings.upperPrice = 11000;
        MarketDynamics original = new MarketDynamics(10000, 71);
        for (int i = 0; i < 517; i++) original.step(settings, 0.2);
        MarketDynamics restored = new Gson().fromJson(new Gson().toJson(original), MarketDynamics.class);
        restored.validate(settings.lowerPrice, settings.upperPrice);
        for (int i = 0; i < 5000; i++) {
            double price = original.step(settings, -0.3);
            assertEquals(price, restored.step(settings, -0.3), 0);
            assertTrue(price >= settings.lowerPrice && price <= settings.upperPrice);
        }
    }

    @Test
    public void realBotFillsFollowRegimesAndRemainActive() throws Exception {
        SimulatedInstrument.Settings settings = new SimulatedInstrument.Settings();
        settings.historyCapacity = 200;
        MarginLedger ledger = new MarginLedger();
        SimulatedInstrument instrument = new SimulatedInstrument(
            "stock",
            settings,
            ledger,
            ledger.instrument("stock", 1, 1000, 550, 1000, settings.upperPrice),
            42);
        int[] counts = { 3, 4, 4, 8, 1 };
        int[] periods = { 5, 7, 8, 4, 120 };
        List<UUID> owners = new ArrayList<>();
        List<Random> randomness = new ArrayList<>();
        List<SimulatedInstrument.Strategy> strategies = new ArrayList<>();
        List<Integer> intervals = new ArrayList<>();
        for (int type = 0; type < counts.length; type++) for (int index = 0; index < counts[type]; index++) {
            UUID owner = UUID.nameUUIDFromBytes((type + ":" + index).getBytes(StandardCharsets.UTF_8));
            ledger.provisionInstitution(
                owner,
                settings.institutionalCapitalCents,
                settings.botCashCents,
                settings.institutionalPositionLimit);
            owners.add(owner);
            randomness.add(new Random(42 ^ owner.getLeastSignificantBits()));
            strategies.add(SimulatedInstrument.Strategy.values()[type]);
            intervals.add(periods[type]);
        }
        List<String> report = new ArrayList<>();
        report.add("step,tick,price,volume,phase");
        long priorVolume = 0;
        int activeWindows = 0;
        double min = Double.POSITIVE_INFINITY, max = 0;
        for (int step = 0; step < 3000; step++) {
            instrument.updateFairPrice();
            for (int bot = 0; bot < owners.size(); bot++) {
                if ((step + bot) % intervals.get(bot) == 0)
                    instrument.act(owners.get(bot), strategies.get(bot), randomness.get(bot), step * 2L);
            }
            SimulatedInstrument.Snapshot snapshot = instrument.snapshot(step * 2L);
            if (snapshot.lastPrice > 0) {
                min = Math.min(min, snapshot.lastPrice);
                max = Math.max(max, snapshot.lastPrice);
            }
            if (snapshot.lastPrice > 0 && step % 10 == 0) {
                com.google.gson.JsonObject saved = new com.google.gson.JsonParser().parse(instrument.checkpoint())
                    .getAsJsonObject();
                report.add(
                    step + ","
                        + step * 2L
                        + ","
                        + snapshot.lastPrice
                        + ","
                        + snapshot.volume
                        + ","
                        + saved.getAsJsonObject("dynamics")
                            .get("regime")
                            .getAsString());
            }
            if (step % 300 == 299) {
                if (snapshot.volume > priorVolume) activeWindows++;
                priorVolume = snapshot.volume;
            }
        }
        Path output = Paths.get("build/reports/market-regimes.csv");
        Files.createDirectories(output.getParent());
        Files.write(output, report, StandardCharsets.UTF_8);
        assertTrue("Matched range: " + min + " to " + max, max - min > 500);
        assertEquals("Bots became permanently inactive", 10, activeWindows);
        assertTrue(instrument.snapshot(6000).volume > 1000);

    }

    @Test
    public void fairValueTracksExecutedVwapInsteadOfTheInitialPrice() {
        MarginLedger ledger = new MarginLedger();
        UUID buyer = UUID.randomUUID(), seller = UUID.randomUUID();
        ledger.importAccount(buyer, 100000000);
        ledger.importAccount(seller, 100000000);
        SimulatedInstrument.Settings settings = new SimulatedInstrument.Settings();
        settings.volatility = 0;
        SimulatedInstrument market = new SimulatedInstrument(
            "stock",
            settings,
            ledger,
            ledger.instrument("stock", 1, 1000, 0, 1000, 100000),
            42);
        market.submit(seller, OrderBook.Side.SELL, 10000, 1, false, false, 0);
        market.submit(buyer, OrderBook.Side.BUY, 10000, 1, true, false, 0);
        market.submit(seller, OrderBook.Side.SELL, 12000, 3, false, false, 40);
        market.submit(buyer, OrderBook.Side.BUY, 12000, 3, true, false, 40);
        market.updateFairPrice(41);
        com.google.gson.JsonObject saved = new com.google.gson.JsonParser().parse(market.checkpoint())
            .getAsJsonObject();
        assertEquals(
            11500,
            saved.get("fair")
                .getAsDouble(),
            0);
        market.updateFairPrice(1240);
        saved = new com.google.gson.JsonParser().parse(market.checkpoint())
            .getAsJsonObject();
        assertEquals(
            12000,
            saved.get("fair")
                .getAsDouble(),
            0);
        assertEquals(12000, market.snapshot(1240).lastPrice);
        assertEquals(4, market.snapshot(1240).volume);
    }

    @Test
    public void institutionsHaveDeepQuotesAndLargeOrdersWithoutRaisingPlayerLimits() {
        MarginLedger ledger = new MarginLedger();
        SimulatedInstrument.Settings settings = new SimulatedInstrument.Settings();
        SimulatedInstrument market = new SimulatedInstrument(
            "stock",
            settings,
            ledger,
            ledger.instrument("stock", 1, 1000, 550, 1000, settings.upperPrice),
            42);
        UUID[] makers = { UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID() };
        for (UUID owner : makers) ledger.provisionInstitution(
            owner,
            settings.institutionalCapitalCents,
            settings.botCashCents,
            settings.institutionalPositionLimit);
        Random random = new Random(42);
        for (int step = 0; step < 40; step++) {
            market.updateFairPrice(step * 10L);
            for (UUID owner : makers) market.act(owner, SimulatedInstrument.Strategy.MARKET_MAKER, random, step * 10L);
        }
        SimulatedInstrument.Snapshot depth = market.snapshot(400);
        long bidSize = 0, askSize = 0;
        for (SimulatedInstrument.Level level : depth.bids) bidSize += level.quantity;
        for (SimulatedInstrument.Level level : depth.asks) askSize += level.quantity;
        assertTrue("Bid depth is still a few single contracts", bidSize >= 3000);
        assertTrue("Ask depth is still a few single contracts", askSize >= 3000);
        UUID player = UUID.randomUUID();
        ledger.importAccount(player, 100000000);
        assertEquals(
            1000,
            ledger.instrument("stock")
                .positionLimit(player));
        OrderBook.Result tooLarge = market
            .submit(player, OrderBook.Side.BUY, settings.upperPrice, 1001, true, false, 401);
        assertEquals(OrderBook.Status.REJECTED, tooLarge.order.status);
        long bestAsk = depth.asks.get(0).price;
        OrderBook.Result ordinary = market
            .submit(player, OrderBook.Side.BUY, settings.upperPrice, 1000, true, false, 402);
        assertEquals(OrderBook.Status.FILLED, ordinary.order.status);
        assertTrue("A player swept through the institutional depth", market.snapshot(402).lastPrice <= bestAsk + 24);
        UUID institution = UUID.randomUUID();
        ledger.provisionInstitution(
            institution,
            settings.institutionalCapitalCents,
            settings.botCashCents,
            settings.institutionalPositionLimit);
        OrderBook.Result large = market
            .submit(institution, OrderBook.Side.BUY, settings.upperPrice, 5000, true, false, 403);
        long filled = 0;
        for (OrderBook.Fill fill : large.fills) filled += fill.quantity;
        assertTrue("Institutions cannot actually exchange thousands of contracts", filled >= 1000);
        MarginLedger restored = MarginLedger.restore(ledger.checkpoint());
        assertEquals(
            settings.institutionalPositionLimit,
            restored.instrument("stock")
                .positionLimit(institution));
    }

    @Test
    public void capitalUpgradeIsGrantedOnceAcrossRestartAndDoesNotEraseLosses() {
        MarginLedger ledger = new MarginLedger();
        UUID institution = UUID.randomUUID();
        ledger.importAccount(institution, 900);
        ledger.provisionInstitution(institution, 100000, 1000, 100000);
        assertEquals(99900, ledger.account(institution).cash);
        assertTrue(ledger.charge(institution, 200));
        ledger.provisionInstitution(institution, 100000, 1000, 100000);
        assertEquals(99700, ledger.account(institution).cash);
        MarginLedger restored = MarginLedger.restore(ledger.checkpoint());
        restored.provisionInstitution(institution, 100000, 1000, 100000);
        assertEquals(99700, restored.account(institution).cash);
    }
}
