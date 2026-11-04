package com.yuelengm.nhkantan.trading;

import com.yuelengm.nhkantan.config.TradingConfig;
import com.yuelengm.nhkantan.market.LeverageRange;

/**
 * Resolves the trading rules for a stock from the synced global {@code trading} config and the stock's optional
 * leverage range from {@code stocks.json}. Both reach the client (config sync and stock snapshot), so client previews
 * and server validation use the same rules.
 */
public class InstrumentRuleResolver {

    private InstrumentRuleResolver() {}

    /**
     * Rules for a stock: the global trading config with the stock's leverage ends applied. A configured end that
     * conflicts with an end set by the stock yields to it; the default leverage is kept inside the result.
     */
    public static InstrumentRules resolve(LeverageRange leverage) {
        int minLeverage = leverage.hasMin() ? leverage.min() : TradingConfig.minLeverage;
        int maxLeverage = leverage.hasMax() ? leverage.max() : TradingConfig.maxLeverage;
        if (minLeverage > maxLeverage) {
            if (leverage.hasMin() && !leverage.hasMax()) maxLeverage = minLeverage;
            else minLeverage = maxLeverage;
        }
        int defaultLeverage = Math.min(maxLeverage, Math.max(minLeverage, TradingConfig.defaultLeverage));
        return new InstrumentRules(
            TradingConfig.maxOpenPositionsPerStock,
            minLeverage,
            defaultLeverage,
            maxLeverage,
            TradingConfig.openFeePercent,
            TradingConfig.liquidationPercent);
    }
}
