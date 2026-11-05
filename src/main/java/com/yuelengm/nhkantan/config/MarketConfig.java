package com.yuelengm.nhkantan.config;

import com.gtnewhorizon.gtnhlib.config.Config;
import com.yuelengm.nhkantan.NHKantan;

@Config(
    modid = NHKantan.MODID,
    category = "market",
    configSubDirectory = ConfigPaths.DIRECTORY_NAME,
    filename = ConfigPaths.CONFIG_FILE_NAME)
@Config.LangKeyPattern
@Config.Sync
@Config.Comment("Price engine settings. Stocks and modes are defined in stocks.json and modes.json.")
public class MarketConfig {

    @Config.Comment("Server ticks between two price updates. Prices only advance while the server is running.")
    @Config.DefaultInt(40)
    @Config.RangeInt(min = 1, max = 72000)
    public static int updateIntervalTicks;

    @Config.Comment({ "Number of K-line candles shown on displays and terminals; each price update is one candle.",
        "Clients only ever receive the prices needed for these candles; the full history stays on the server." })
    @Config.DefaultInt(50)
    @Config.RangeInt(min = 5, max = 500)
    @Config.RequiresWorldRestart
    public static int visibleCandles;
}
