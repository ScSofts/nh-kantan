package com.yuelengm.nhkantan.market.order;

import static org.junit.Assert.*;

import java.util.UUID;

import org.junit.Test;

import com.google.gson.Gson;

public class SimulatedInstrumentTest {

    @Test
    public void ocoPartialProfitThenPartialStopNeverClosesBeyondProtectedQuantity() {
        MarginLedger ledger = new MarginLedger();
        UUID owner = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        ledger.importAccount(owner, 10000000);
        ledger.importAccount(seller, 10000000);
        ledger.importAccount(other, 10000000);
        SimulatedInstrument instrument = new SimulatedInstrument(
            "stock",
            new SimulatedInstrument.Settings(),
            ledger,
            ledger.instrument("stock", 1, 1000, 0, 1000, 100000),
            1);
        instrument.submit(seller, OrderBook.Side.SELL, 10000, 10, false, false, 1);
        instrument.submit(owner, OrderBook.Side.BUY, 10000, 10, true, false, 1);
        instrument.protect(owner, 5, 9000, 11000, 2);
        instrument.submit(other, OrderBook.Side.BUY, 11000, 2, true, false, 3);
        assertEquals(8, ledger.position(owner, "stock").quantity);
        instrument.submit(other, OrderBook.Side.BUY, 8000, 2, false, false, 4);
        instrument.submit(seller, OrderBook.Side.SELL, 8000, 1, true, false, 4);
        assertEquals(7, ledger.position(owner, "stock").quantity); // Only one unit of stop liquidity remained.
        instrument.submit(other, OrderBook.Side.BUY, 8000, 10, false, false, 5);
        instrument.checkProtections(6);
        assertEquals(5, ledger.position(owner, "stock").quantity);
        assertTrue(
            instrument.orders(owner)
                .isEmpty());
        instrument.checkProtections(7);
        assertEquals(5, ledger.position(owner, "stock").quantity);
    }

    @Test
    public void fairPriceChangesCannotFabricateTradeAndPublicDepthIsAnonymous() {
        MarginLedger ledger = new MarginLedger();
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        ledger.importAccount(buyer, 1000000);
        ledger.importAccount(seller, 1000000);
        SimulatedInstrument instrument = new SimulatedInstrument(
            "stock",
            new SimulatedInstrument.Settings(),
            ledger,
            ledger.instrument("stock", 1, 1000, 0, 1000, 100000),
            1);
        for (int i = 0; i < 100; i++) instrument.updateFairPrice();
        assertEquals(0, instrument.snapshot(0).lastPrice);
        assertTrue(instrument.snapshot(0).candles.isEmpty());
        instrument.submit(seller, OrderBook.Side.SELL, 10000, 10, false, false, 1);
        String publicJson = new Gson().toJson(instrument.snapshot(1));
        assertFalse(publicJson.contains(seller.toString()));
        assertFalse(publicJson.contains("owner"));
        instrument.submit(buyer, OrderBook.Side.BUY, 10001, 3, true, false, 2);
        assertEquals(10000, instrument.snapshot(2).lastPrice);
        assertEquals(3, instrument.snapshot(2).volume);
        assertEquals(7, instrument.snapshot(2).asks.get(0).quantity);
        assertTrue(
            instrument.orders(buyer)
                .isEmpty());
        assertEquals(
            1,
            instrument.orders(seller)
                .size());
        MarginLedger restored = MarginLedger.restore(ledger.checkpoint());
        SimulatedInstrument copy = SimulatedInstrument.restore(instrument.checkpoint(), restored, 1);
        assertEquals(3, copy.snapshot(2).candles.get(0).volume);
        assertEquals(7, copy.snapshot(2).asks.get(0).quantity);
        assertEquals(ledger.account(seller).frozen, restored.account(seller).frozen);
    }
}
