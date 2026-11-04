package com.yuelengm.nhkantan.trading;

import com.yuelengm.nhkantan.util.MathUtils;

/**
 * Resolved trading rules for one stock.
 */
public class InstrumentRules {

    private final int maxOpenPositions;
    private final int minLeverage;
    private final int defaultLeverage;
    private final int maxLeverage;
    private final double openFeePercent;
    private final double liquidationPercent;

    public InstrumentRules(int maxOpenPositions, int minLeverage, int defaultLeverage, int maxLeverage,
        double openFeePercent, double liquidationPercent) {
        this.maxOpenPositions = maxOpenPositions;
        this.minLeverage = minLeverage;
        this.defaultLeverage = defaultLeverage;
        this.maxLeverage = maxLeverage;
        this.openFeePercent = openFeePercent;
        this.liquidationPercent = liquidationPercent;
    }

    public int maxOpenPositions() {
        return maxOpenPositions;
    }

    public int minLeverage() {
        return minLeverage;
    }

    public int defaultLeverage() {
        return defaultLeverage;
    }

    public int maxLeverage() {
        return maxLeverage;
    }

    /** The leverage limited to {@code [minLeverage, maxLeverage]}. */
    public int clampLeverage(int leverage) {
        return MathUtils.clamp(leverage, minLeverage, maxLeverage);
    }

    public double openFeePercent() {
        return openFeePercent;
    }

    public double liquidationPercent() {
        return liquidationPercent;
    }
}
