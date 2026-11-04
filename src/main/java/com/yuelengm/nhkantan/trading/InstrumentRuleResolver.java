package com.yuelengm.nhkantan.trading;

import com.yuelengm.nhkantan.config.TradingConfig;

/**
 * Resolves the trading rules for a stock. All stocks currently share the synced global {@code trading} config, so
 * the same rules are available to client previews and server validation; this is the place to add per-stock rules.
 */
public class InstrumentRuleResolver {

    private InstrumentRuleResolver() {}

    /** Rules for the stock: the global trading config with the leverage range normalized. */
    public static InstrumentRules resolve(String stockId) {
        int maxOpenPositions = TradingConfig.maxOpenPositionsPerStock;
        int minLeverage = TradingConfig.minLeverage;
        int defaultLeverage = TradingConfig.defaultLeverage;
        int maxLeverage = TradingConfig.maxLeverage;
        double openFeePercent = TradingConfig.openFeePercent;
        double liquidationPercent = TradingConfig.liquidationPercent;
        if (minLeverage > maxLeverage) minLeverage = maxLeverage;
        if (defaultLeverage < minLeverage) defaultLeverage = minLeverage;
        if (defaultLeverage > maxLeverage) defaultLeverage = maxLeverage;
        return new InstrumentRules(
            maxOpenPositions,
            minLeverage,
            defaultLeverage,
            maxLeverage,
            openFeePercent,
            liquidationPercent);
    }
}
