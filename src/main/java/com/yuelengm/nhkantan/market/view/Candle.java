package com.yuelengm.nhkantan.market.view;

/**
 * Client-side K-line candle derived from two consecutive prices: it opens at the previous price and closes at the new
 * one for legacy data; realistic data uses actual trade OHLCV and cumulative game ticks.
 */
public class Candle {

    private final double open;
    private final double high;
    private final double low;
    private final long volume;
    private final long tick;
    private final double close;

    public Candle(float open, float close) {
        this(open, Math.max(open, close), Math.min(open, close), close, 0, 0);
    }

    public Candle(double open, double high, double low, double close, long volume, long tick) {
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.tick = tick;
    }

    public double open() {
        return open;
    }

    public double close() {
        return close;
    }

    public double high() {
        return high;
    }

    public double low() {
        return low;
    }

    public long volume() {
        return volume;
    }

    public long tick() {
        return tick;
    }

    public boolean isRising() {
        return close >= open;
    }
}
