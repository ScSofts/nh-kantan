package com.yuelengm.nhkantan.market;

/**
 * Per-stock leverage limits from {@code stocks.json}. Each end is optional: {@link #UNSET} (0) leaves it to the
 * trading config. Sent to clients with the stock snapshot so the order form shows the same range the server enforces.
 */
public class LeverageRange {

    /** Marks an end that falls back to the trading config. */
    public static final int UNSET = 0;
    /** No per-stock limits. */
    public static final LeverageRange DEFAULT = new LeverageRange(UNSET, UNSET);

    private final int min;
    private final int max;

    /**
     * @param min minimum leverage, or {@link #UNSET}
     * @param max maximum leverage, or {@link #UNSET}
     */
    public LeverageRange(int min, int max) {
        this.min = min;
        this.max = max;
    }

    /** Minimum leverage, or {@link #UNSET}. */
    public int min() {
        return min;
    }

    /** Maximum leverage, or {@link #UNSET}. */
    public int max() {
        return max;
    }

    public boolean hasMin() {
        return min != UNSET;
    }

    public boolean hasMax() {
        return max != UNSET;
    }
}
