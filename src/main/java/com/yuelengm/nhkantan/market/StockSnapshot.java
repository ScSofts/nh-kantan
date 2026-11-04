package com.yuelengm.nhkantan.market;

/**
 * Public view of a stock sent to clients: display data, leverage limits and the most recent prices only. Older
 * history, mode, formulas and random state never leave the server.
 */
public class StockSnapshot {

    private final String id;
    private final String name;
    private final double lowerBound;
    private final double upperBound;
    private final LeverageRange leverage;
    private final float[] prices;

    /**
     * @param prices most recent prices, oldest first; never empty, the last one is the current price
     */
    public StockSnapshot(String id, String name, double lowerBound, double upperBound, LeverageRange leverage,
        float[] prices) {
        this.id = id;
        this.name = name;
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
        this.leverage = leverage;
        this.prices = prices;
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

    public LeverageRange leverage() {
        return leverage;
    }

    public float[] prices() {
        return prices;
    }
}
