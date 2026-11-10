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

    @Config.Comment("Initial margin in basis points: 10 = 0.1% (1000x), 1000 = 10%. Configurable on server restart.")
    @Config.DefaultInt(1000)
    @Config.RangeInt(min = 10, max = 10000)
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

    @Config.Comment("Total market-maker spread in basis points, before depth offsets.")
    @Config.DefaultInt(10)
    @Config.RangeInt(min = 1, max = 200)
    public static int makerSpreadBps;

    @Config.Comment("Maximum inventory quote skew in basis points; also capped below half the spread.")
    @Config.DefaultInt(5)
    @Config.RangeInt(min = 0, max = 100)
    public static int makerInventorySkewBps;

    @Config.Comment("Maximum common market-maker reference movement per active second, in basis points.")
    @Config.DefaultInt(120)
    @Config.RangeInt(min = 1, max = 1000)
    public static int makerRepriceBpsPerSecond;

    @Config.Comment("Cancel only automated passive quotes outside this reference band; manual orders are preserved.")
    @Config.DefaultInt(30)
    @Config.RangeInt(min = 1, max = 1000)
    public static int automatedQuoteBandBps;

    @Config.DefaultInt(2)
    @Config.RangeInt(min = 1, max = 60)
    public static int automatedQuoteLifetimeSeconds;

    @Config.Comment("Worst-price protection for robot and option-hedge market IOC orders, in basis points.")
    @Config.DefaultInt(20)
    @Config.RangeInt(min = 1, max = 500)
    public static int botMaxSlippageBps;

    @Config.Comment("Maximum fraction of currently executable opposite-side liquidity consumed per robot action.")
    @Config.DefaultDouble(0.25)
    @Config.RangeDouble(min = 0.01, max = 1)
    public static double botMaxLiquidityFraction;

    @Config.Comment("Extra size multiplier for passive market-maker orders only; ordinary bot trades keep their base size.")
    @Config.DefaultInt(3)
    @Config.RangeInt(min = 1, max = 100)
    public static int makerQuoteSizeMultiplier;

    @Config.Comment("Market-maker interval multiplier; 0.4 turns the former 500 ms base into about 200 ms before per-instance pacing.")
    @Config.DefaultDouble(0.4)
    @Config.RangeDouble(min = 0.05, max = 2)
    public static double makerIntervalScale;

    @Config.Comment("Hard maximum quantity per whale action, including a breakout burst; may still be reduced by liquidity/risk limits.")
    @Config.DefaultInt(5000)
    @Config.RangeInt(min = 1, max = 100000)
    public static int whaleMaxOrderContracts;

    @Config.Comment("Institution capital is botInitialCash times this factor. Increases are granted once; losses are not reset.")
    @Config.DefaultInt(100)
    @Config.RangeInt(min = 1, max = 1000)
    public static int institutionalCapitalMultiplier;

    @Config.Comment("Institution order size is botOrderSize times this factor, including existing config files.")
    @Config.DefaultInt(100)
    @Config.RangeInt(min = 1, max = 100)
    public static int institutionalOrderMultiplier;

    @Config.DefaultInt(100000)
    @Config.RangeInt(min = 1000, max = 1000000)
    public static int institutionalPositionLimit;

    @Config.Comment("Rolling executed-price VWAP window in active game seconds, evaluated over candle buckets.")
    @Config.DefaultInt(60)
    @Config.RangeInt(min = 1, max = 3600)
    public static int fairAverageSeconds;

    @Config.Comment("Profiles are market maker, trend, mean reversion, noise, whale, in that order.")
    @Config.DefaultIntList({ 2, 2, 2, 2, 2 })
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

    @Config.Comment("Persistent market phase duration in seconds; phase changes use active market time only.")
    @Config.DefaultInt(30)
    @Config.RangeInt(min = 2, max = 3600)
    public static int regimeMinSeconds;

    @Config.DefaultInt(75)
    @Config.RangeInt(min = 2, max = 3600)
    public static int regimeMaxSeconds;

    @Config.Comment("Typical directional move per trend phase, in basis points of the current price.")
    @Config.DefaultInt(500)
    @Config.RangeInt(min = 0, max = 5000)
    public static int trendMoveBps;

    @Config.Comment("Typical breakout displacement in basis points; reprices through real orders, not synthetic fills.")
    @Config.DefaultInt(800)
    @Config.RangeInt(min = 0, max = 10000)
    public static int breakoutMoveBps;

    @Config.Comment("Per-step follow rate of the medium-term fundamental anchor.")
    @Config.DefaultDouble(0.001)
    @Config.RangeDouble(min = 0, max = 1)
    public static double anchorFollowRate;

    @Config.DefaultDouble(0.22)
    @Config.RangeDouble(min = 0, max = 1)
    public static double flowFollowRate;

    @Config.Comment("Maximum temporary order-flow displacement, in basis points; flowImpact scales this cap.")
    @Config.DefaultDouble(20)
    @Config.RangeDouble(min = 0, max = 1000)
    public static double maxFlowImpactBps;

    @Config.Comment("Weight of bounded rolling-VWAP deviation around the fundamental reference, from 0 to 1.")
    @Config.DefaultDouble(0.15)
    @Config.RangeDouble(min = 0, max = 1)
    public static double fairVwapWeight;

    @Config.Comment("Maximum VWAP deviation in multiples of effective tick volatility; prevents feedback ratcheting.")
    @Config.DefaultDouble(2)
    @Config.RangeDouble(min = 0, max = 100)
    public static double vwapDeviationVolMultiplier;

    @Config.DefaultDouble(0.85)
    @Config.RangeDouble(min = 0, max = 0.999)
    public static double fairNoiseMemory;

    @Config.DefaultDouble(3)
    @Config.RangeDouble(min = 0, max = 100)
    public static double fairNoiseLimitMultiplier;

    @Config.Comment("Fraction of maxFlowImpactBps used for normalized signed aggressive order flow.")
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

    /** Normalize at world start, before creating workers or touching finance files. The cfg itself is preserved. */
    public static void normalize() {
        try {
            for (java.lang.reflect.Field field : RealisticConfig.class.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                if (field.getType() == int.class) {
                    Config.RangeInt range = field.getAnnotation(Config.RangeInt.class);
                    Config.DefaultInt fallback = field.getAnnotation(Config.DefaultInt.class);
                    if (range != null && fallback != null) {
                        int value = field.getInt(null);
                        if (value < range.min() || value > range.max()) {
                            field.setInt(null, fallback.value());
                            corrected(field.getName());
                        }
                    }
                } else if (field.getType() == double.class) {
                    Config.RangeDouble range = field.getAnnotation(Config.RangeDouble.class);
                    Config.DefaultDouble fallback = field.getAnnotation(Config.DefaultDouble.class);
                    if (range != null && fallback != null) {
                        double value = field.getDouble(null);
                        if (!Double.isFinite(value) || value < range.min() || value > range.max()) {
                            field.setDouble(null, fallback.value());
                            corrected(field.getName());
                        }
                    }
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unable to normalize realistic configuration", e);
        }
        if (regimeMaxSeconds < regimeMinSeconds) {
            int previousMin = regimeMinSeconds;
            regimeMinSeconds = regimeMaxSeconds;
            regimeMaxSeconds = previousMin;
            corrected("regimeMinSeconds/regimeMaxSeconds (reordered)");
        }
        if (maintenanceMarginBps > initialMarginBps) {
            maintenanceMarginBps = initialMarginBps / 2;
            corrected("maintenanceMarginBps");
        }
        if (makerQuoteSizeMultiplier == 10) {
            makerQuoteSizeMultiplier = 3;
            NHKantan.LOG.info("Adjusted former 10x market-maker default to 3x quote size");
        }
        int minimumBand = Math.max(makerSpreadBps, botMaxSlippageBps);
        if (automatedQuoteBandBps < minimumBand) {
            automatedQuoteBandBps = minimumBand;
            corrected("automatedQuoteBandBps (spread/slippage floor)");
        }
        if (java.util.Arrays.equals(botCounts, new int[] { 3, 4, 4, 8, 1 })) {
            botCounts = new int[] { 2, 2, 2, 2, 2 };
            NHKantan.LOG.info("Migrated former default institution counts to two distinct instances per strategy");
        }
        botCounts = profile(botCounts, new int[] { 2, 2, 2, 2, 2 }, 0, 100);
        botIntervalsMillis = profile(botIntervalsMillis, new int[] { 500, 700, 800, 350, 12000 }, 100, 86400000);
        double[] defaults = { 0.9, 0.65, 0.6, 0.75, 0.15 };
        double[] probabilities = new double[5];
        for (int i = 0; i < 5; i++) {
            double value = botProbabilities != null && i < botProbabilities.length ? botProbabilities[i] : Double.NaN;
            probabilities[i] = Double.isFinite(value) && value >= 0 && value <= 1 ? value : defaults[i];
        }
        if (!java.util.Arrays.equals(botProbabilities, probabilities)) corrected("botProbabilities");
        botProbabilities = probabilities;
    }

    public static int[] botBudget(int instruments) {
        if (instruments < 1 || instruments > 500)
            throw new IllegalArgumentException("Expected 1 to 500 enabled instruments");
        int[] counts = botCounts.clone();
        int total = 0;
        for (int count : counts) total += count;
        int allowed = 500 / instruments;
        if (total <= allowed) return counts;
        for (int i = 0; i < counts.length; i++) counts[i] = (int) ((long) counts[i] * allowed / total);
        if (botCounts[0] > 0 && counts[0] == 0) counts[0] = 1;
        NHKantan.LOG.warn(
            "Reduced institution counts to fit the 500-instance market budget: {}",
            java.util.Arrays.toString(counts));
        return counts;
    }

    private static int[] profile(int[] values, int[] defaults, int min, int max) {
        int[] result = new int[5];
        for (int i = 0; i < 5; i++) {
            int value = values != null && i < values.length ? values[i] : -1;
            result[i] = value >= min && value <= max ? value : defaults[i];
        }
        if (!java.util.Arrays.equals(values, result)) corrected("institution profile array");
        return result;
    }

    private static void corrected(String name) {
        NHKantan.LOG.warn("Invalid realistic setting corrected in memory: {}. Original cfg retained.", name);
    }
}
