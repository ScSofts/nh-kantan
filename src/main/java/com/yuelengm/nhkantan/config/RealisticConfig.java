package com.yuelengm.nhkantan.config;

import com.gtnewhorizon.gtnhlib.config.Config;
import com.yuelengm.nhkantan.NHKantan;

/** Restart-bound exchange settings; structural rules must not change underneath open contracts. */
@Config(
    modid = NHKantan.MODID,
    category = "realistic",
    configSubDirectory = ConfigPaths.DIRECTORY_NAME,
    filename = ConfigPaths.CONFIG_FILE_NAME)
@Config.LangKeyPattern
@Config.RequiresWorldRestart
public class RealisticConfig {

    @Config.Comment("Prototype exchange switch. Enabling performs the user-approved net-equity-only migration.")
    @Config.DefaultBoolean(true)
    public static boolean enabled;

    @Config.Comment("Currency cents per price tick per contract, including the contract multiplier.")
    @Config.DefaultInt(1)
    @Config.RangeInt(min = 1, max = 1000000)
    public static int tickValueCents;

    @Config.Comment("Initial margin in basis points: 1000 = 10%, fixed for this instrument.")
    @Config.DefaultInt(1000)
    @Config.RangeInt(min = 1, max = 10000)
    public static int initialMarginBps;

    @Config.Comment("Maintenance margin in basis points; must not exceed initial margin.")
    @Config.DefaultInt(500)
    @Config.RangeInt(min = 0, max = 10000)
    public static int maintenanceMarginBps;

    @Config.Comment("Commission in parts per million: 550 = 0.055%, charged on each fill.")
    @Config.DefaultInt(550)
    @Config.RangeInt(min = 0, max = 1000000)
    public static int commissionPpm;

    @Config.DefaultInt(1000)
    @Config.RangeInt(min = 1, max = 1000000)
    public static int maxPosition;

    @Config.DefaultInt(8)
    @Config.RangeInt(min = 1, max = 100)
    public static int depth;

    @Config.DefaultInt(3)
    @Config.RangeInt(min = 1, max = 10000)
    public static int botOrderSize;

    @Config.DefaultInt(1000000)
    @Config.RangeInt(min = 1, max = 100000000)
    public static int botInitialCash;

    @Config.Comment("Profiles are market maker, trend, mean reversion, noise, whale, in that order.")
    @Config.DefaultIntList({ 3, 4, 4, 8, 1 })
    public static int[] botCounts;

    @Config.DefaultIntList({ 500, 700, 800, 350, 12000 })
    public static int[] botIntervalsMillis;

    @Config.DefaultDoubleList({ 0.9, 0.65, 0.6, 0.75, 0.15 })
    public static double[] botProbabilities;

    @Config.DefaultDouble(0.4)
    @Config.RangeDouble(min = 0, max = 1)
    public static double intervalJitter;

    @Config.DefaultDouble(0.5)
    @Config.RangeDouble(min = 0, max = 10000)
    public static double fairVolatilityTicks;

    @Config.Comment("Fair-price volatility multiplier; new default also applies to existing configuration files.")
    @Config.DefaultDouble(12)
    @Config.RangeDouble(min = 0, max = 100)
    public static double volatilityMultiplier;

    @Config.DefaultDouble(0.02)
    @Config.RangeDouble(min = 0, max = 1)
    public static double flowImpact;

    @Config.DefaultInt(42)
    public static int seed;

    @Config.Comment("Daily expiry ladder, from 1 to this many game days (maximum 14).")
    @Config.DefaultInt(14)
    @Config.RangeInt(min = 1, max = 14)
    public static int expiryGameDays;

    @Config.Comment("Legacy setting retained for configuration compatibility; expiry series now roll daily.")
    @Config.DefaultInt(2)
    @Config.RangeInt(min = 1, max = 365)
    public static int seriesEveryGameDays;

    @Config.DefaultDouble(0.8)
    @Config.RangeDouble(min = 0.001, max = 10)
    public static double baseIV;

    @Config.DefaultDouble(0.04)
    @Config.RangeDouble(min = 0, max = 1)
    public static double optionMarkup;

    @Config.DefaultDouble(0.01)
    @Config.RangeDouble(min = 0, max = 10000)
    public static double optionMinimumMarkup;
}
