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
        /** Price-tick turnover used only for the rolling fair-value reference, never for account cash. */
        public final double turnover;

        private Bar(long tick, long open, long high, long low, long close, long volume, double turnover) {
            this.tick = tick;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
            this.turnover = turnover;
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
            append(new Bar(bucket, price, price, price, price, quantity, price * (double) quantity));
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
                    volume,
                    (previous.turnover > 0 ? previous.turnover : previous.close * (double) previous.volume)
                        + price * (double) quantity));
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
            append(new Bar(tick, previous.close, previous.close, previous.close, previous.close, 0, 0));
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

    /** Volume-weighted executed price over recent buckets; carry candles have no influence. */
    public double averagePrice(long gameTick, long windowTicks) {
        double turnover = 0, volume = 0;
        for (Bar bar : bars) {
            if (bar.tick + bucketTicks <= gameTick - windowTicks || bar.volume == 0) continue;
            turnover += bar.turnover > 0 ? bar.turnover : bar.close * (double) bar.volume;
            volume += bar.volume;
        }
        return volume == 0 ? 0 : turnover / volume;
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
                || bar.volume < 0
                || !Double.isFinite(bar.turnover)
                || bar.turnover < 0) {
                throw new IllegalArgumentException("Invalid saved candle");
            }
            history.append(bar);
            previous = bar.tick;
        }
        return history;
    }
}
