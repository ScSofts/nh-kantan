package com.yuelengm.nhkantan.market.order;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

public class TradeHistoryTest {

    @Test
    public void actualFillsSetOhlcvAndEmptyBucketsHaveNoVolume() {
        TradeHistory history = new TradeHistory(4, 20);
        history.advance(100);
        assertTrue(
            history.snapshot()
                .isEmpty());
        history.fill(100, 100, 2);
        history.fill(105, 110, 3);
        history.fill(110, 90, 1);
        TradeHistory.Bar bar = history.snapshot()
            .get(0);
        assertEquals(100, bar.open);
        assertEquals(110, bar.high);
        assertEquals(90, bar.low);
        assertEquals(90, bar.close);
        assertEquals(6, bar.volume);
        history.advance(140);
        List<TradeHistory.Bar> snapshot = history.snapshot();
        assertEquals(3, snapshot.size());
        assertEquals(0, snapshot.get(1).volume);
        history.fill(145, 120, 2);
        assertEquals(
            120,
            history.snapshot()
                .get(2).open);
        assertEquals(90, snapshot.get(2).open); // Previously published snapshot is unchanged.
    }

    @Test
    public void longPauseCannotAllocateUnboundedEmptyCandles() {
        TradeHistory history = new TradeHistory(5, 1);
        history.fill(0, 10, 1);
        history.advance(1000000000);
        assertEquals(
            5,
            history.snapshot()
                .size());
        assertEquals(
            1000000000,
            history.snapshot()
                .get(4).tick);
    }
}
