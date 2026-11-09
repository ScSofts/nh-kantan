package com.yuelengm.nhkantan.market.order;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/** Bounded OHLCV history made exclusively from fills and empty time buckets. */
public final class TradeHistory {

    public static final class Bar {

        public final long tick;
        public final long open;
        public final long high;
        public final long low;
        public final long close;
        public final long volume;

        private Bar(long tick, long open, long high, long low, long close, long volume) {
            this.tick = tick;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
        }
    }

    private final int capacity;
    private final long bucketTicks;
    private final Deque<Bar> bars = new ArrayDeque<>();

    public TradeHistory(int capacity, long bucketTicks) {
        if (capacity < 1 || bucketTicks < 1) throw new IllegalArgumentException("Invalid history limits");
        this.capacity = capacity;
        this.bucketTicks = bucketTicks;
    }

    public void fill(long gameTick, long price, long quantity) {
        if (gameTick < 0 || price <= 0 || quantity <= 0) throw new IllegalArgumentException("Invalid fill");
        advance(gameTick);
        long bucket = gameTick / bucketTicks * bucketTicks;
        Bar previous = bars.peekLast();
        if (previous == null || previous.tick < bucket) {
            append(new Bar(bucket, price, price, price, price, quantity));
        } else {
            // First actual trade in an empty bucket determines its open; do not count carry prices as fills.
            long volume = Math.addExact(previous.volume, quantity);
            bars.removeLast();
            bars.addLast(
                new Bar(
                    bucket,
                    previous.volume == 0 ? price : previous.open,
                    previous.volume == 0 ? price : Math.max(previous.high, price),
                    previous.volume == 0 ? price : Math.min(previous.low, price),
                    price,
                    volume));
        }
    }

    public void advance(long gameTick) {
        if (gameTick < 0) throw new IllegalArgumentException("Negative game tick");
        Bar previous = bars.peekLast();
        if (previous == null) return; // No fake candles before the first real trade.
        long bucket = gameTick / bucketTicks * bucketTicks;
        if (bucket < previous.tick) throw new IllegalArgumentException("History clock moved backwards");
        long first = Math.max(previous.tick + bucketTicks, bucket - (capacity - 1L) * bucketTicks);
        for (long tick = first; tick <= bucket; tick += bucketTicks) {
            append(new Bar(tick, previous.close, previous.close, previous.close, previous.close, 0));
            if (Long.MAX_VALUE - tick < bucketTicks) break;
        }
    }

    private void append(Bar bar) {
        bars.addLast(bar);
        while (bars.size() > capacity) bars.removeFirst();
    }

    public List<Bar> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(bars));
    }

    public static TradeHistory restore(int capacity, long bucketTicks, List<Bar> saved) {
        TradeHistory history = new TradeHistory(capacity, bucketTicks);
        if (saved == null || saved.size() > capacity)
            throw new IllegalArgumentException("Invalid saved history length");
        long previous = -1;
        for (Bar bar : saved) {
            if (bar == null || bar.tick < 0
                || bar.tick <= previous
                || bar.tick % bucketTicks != 0
                || bar.open <= 0
                || bar.low <= 0
                || bar.high < bar.low
                || bar.open < bar.low
                || bar.open > bar.high
                || bar.close < bar.low
                || bar.close > bar.high
                || bar.volume < 0) {
                throw new IllegalArgumentException("Invalid saved candle");
            }
            history.append(bar);
            previous = bar.tick;
        }
        return history;
    }
}
