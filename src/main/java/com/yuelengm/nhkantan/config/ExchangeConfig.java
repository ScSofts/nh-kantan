package com.yuelengm.nhkantan.config;

import com.gtnewhorizon.gtnhlib.config.Config;
import com.yuelengm.nhkantan.NHKantan;

@Config(
    modid = NHKantan.MODID,
    category = "exchange",
    configSubDirectory = ConfigPaths.DIRECTORY_NAME,
    filename = ConfigPaths.CONFIG_FILE_NAME)
@Config.LangKeyPattern
@Config.Sync
@Config.Comment("Exchange between market currency and items, and items accepted to repay debt. Synced to clients.")
public class ExchangeConfig {

    @Config.Comment({ "Items exchangeable with the market currency in both directions, one per entry:",
        "modid:itemName;meta;depositValue;depositFee;withdrawValue;withdrawFee",
        "Values are market currency per item; a negative value disables that direction (deposit-only or",
        "withdraw-only items). Fees are fractions of the exchanged amount (0.1 = 10%):",
        "depositing credits value x (1 - fee), withdrawing costs value x (1 + fee)." })
    @Config.DefaultStringList({ "minecraft:diamond;0;100;0;100;0.1" })
    public static String[] currencyItems;

    @Config.Comment({ "Items accepted to repay a negative balance, one per entry: modid:itemName;meta;value",
        "value: market currency repaid per item. Repayment never raises the balance above 0." })
    @Config.DefaultStringList({})
    public static String[] debtItems;
}
