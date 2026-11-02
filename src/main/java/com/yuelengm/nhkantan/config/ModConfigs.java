package com.yuelengm.nhkantan.config;

import com.gtnewhorizon.gtnhlib.config.ConfigException;
import com.gtnewhorizon.gtnhlib.config.ConfigurationManager;

/**
 * Registers all GTNHLib config classes; they share {@code config/nh-kantan/nhkantan.cfg}.
 */
public class ModConfigs {

    private ModConfigs() {}

    public static void register() {
        try {
            ConfigurationManager.registerConfig(MarketConfig.class);
            ConfigurationManager.registerConfig(TradingConfig.class);
            ConfigurationManager.registerConfig(CurrencyConfig.class);
            ConfigurationManager.registerConfig(ExchangeConfig.class);
            ConfigurationManager.registerConfig(CompatConfig.class);
        } catch (ConfigException e) {
            throw new IllegalStateException("Failed to register NH kantan config", e);
        }
    }
}
