package com.yuelengm.nhkantan.config;

import com.gtnewhorizon.gtnhlib.config.Config;
import com.yuelengm.nhkantan.NHKantan;

@Config(
    modid = NHKantan.MODID,
    category = "compat",
    configSubDirectory = ConfigPaths.DIRECTORY_NAME,
    filename = ConfigPaths.CONFIG_FILE_NAME)
@Config.LangKeyPattern
@Config.Sync
@Config.Comment("Integration with other mods. Synced to clients.")
public class CompatConfig {

    @Config.Comment({ "Adds the GTNH quest coins (dreamcraft:Coin*) to the exchange and debt lists.",
        "Each coin type is listed once; all its sizes (worth 1, 10, 100, 1000 and 10000 coins) are accepted." })
    @Config.DefaultBoolean(true)
    public static boolean gtnhCoins;

    @Config.Comment("Market currency credited per base coin when depositing. 0 or negative disables depositing.")
    @Config.DefaultDouble(10.0D)
    public static double gtnhCoinDepositValue;

    @Config.Comment("Market currency charged per base coin when withdrawing. 0 or negative disables withdrawing.")
    @Config.DefaultDouble(11.0D)
    public static double gtnhCoinWithdrawValue;

    @Config.Comment("Debt repaid per base coin. 0 or negative disables using coins to repay debt.")
    @Config.DefaultDouble(20.0D)
    public static double gtnhCoinDebtValue;
}
