package com.yuelengm.nhkantan.market.view;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.yuelengm.nhkantan.market.LeverageRange;
import com.yuelengm.nhkantan.market.PriceWindow;
import com.yuelengm.nhkantan.market.StockSnapshot;

/**
 * Client copy of a stock's public data: the most recent prices only, plus the candles derived from them. Prices that
 * scroll out of the window are dropped; the client never keeps older history.
 */
public class StockView {

    private final String id;
    private final String name;
    private final double lowerBound;
    private final double upperBound;
    private final LeverageRange leverage;
    private final PriceWindow prices;
    private List<Candle> candles = Collections.emptyList();

    /**
     * @param recentPrices number of prices kept; yields one candle less
     */
    public StockView(StockSnapshot snapshot, int recentPrices) {
        this.id = snapshot.id();
        this.name = snapshot.name();
        this.lowerBound = snapshot.lowerBound();
        this.upperBound = snapshot.upperBound();
        this.leverage = snapshot.leverage();
        this.prices = new PriceWindow(recentPrices);
        this.prices.addAll(snapshot.prices());
        rebuildCandles();
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public double lowerBound() {
        return lowerBound;
    }

    public double upperBound() {
        return upperBound;
    }

    /** Per-stock leverage limits; unset ends fall back to the synced trading config. */
    public LeverageRange leverage() {
        return leverage;
    }

    public float price() {
        return prices.latest();
    }

    /** Visible candles, oldest first. Read only. */
    public List<Candle> candles() {
        return candles;
    }

    /** Number of candle slots on the chart's horizontal axis, independent of how many candles exist yet. */
    public int candleSlots() {
        return prices.capacity() - 1;
    }

    /** Relative change over the visible window, e.g. {@code 0.05} for +5%. */
    public double windowChange() {
        return (double) prices.latest() / prices.get(0) - 1.0D;
    }

    public void applyPrice(float price) {
        prices.add(price);
        rebuildCandles();
    }

    /** Candles are rebuilt once per price update so renderers can read them every frame without allocating. */
    private void rebuildCandles() {
        List<Candle> next = new ArrayList<>(Math.max(0, prices.size() - 1));
        for (int i = 1; i < prices.size(); i++) {
            next.add(new Candle(prices.get(i - 1), prices.get(i)));
        }
        candles = Collections.unmodifiableList(next);
    }
}
