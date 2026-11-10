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
            ConfigurationManager.registerConfig(ClientConfig.class);
            ConfigurationManager.registerConfig(RealisticConfig.class);
            if (RealisticConfig.limitsConfigVersion == 0) {
                if (RealisticConfig.initialMarginBps == 1000) RealisticConfig.initialMarginBps = 10;
                if (RealisticConfig.maintenanceMarginBps == 500) RealisticConfig.maintenanceMarginBps = 5;
                if (RealisticConfig.maxPosition == 1000) RealisticConfig.maxPosition = 10000000;
                RealisticConfig.limitsConfigVersion = 1;
                ConfigurationManager.save(RealisticConfig.class);
            }
        } catch (ConfigException e) {
            throw new IllegalStateException("Failed to register NH kantan config", e);
        }
    }
}
