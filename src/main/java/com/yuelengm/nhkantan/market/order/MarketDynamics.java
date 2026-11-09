package com.yuelengm.nhkantan.market.order;

/**
 * Hidden, persistent market dynamics.
 * <p>
 * The internal fundamental price follows a regime-driven stochastic process.
 * Order flow only adds a bounded, transient displacement to the public reference.
 * Actual trades and candles must still be produced by the matching engine.
 * <p>
 * A raw buy volume is NOT a valid `flow` argument. Supply a signed imbalance
 * in [-1, 1]: (aggressiveBuyVolume - aggressiveSellVolume) / totalVolume.
 */
final class MarketDynamics {

    enum Regime {
        RANGE,
        TREND_UP,
        TREND_DOWN,
        REVERSION,
        BREAKOUT_UP,
        BREAKOUT_DOWN
    }

    // These defaults can be moved to Settings if you want runtime configuration.
    private static final double ANCHOR_FOLLOW_RATE = 0.001;
    private static final double FLOW_FOLLOW_RATE = 0.22;
    private static final double MAX_FLOW_IMPACT_BPS = 20.0;
    private static final double VWAP_WEIGHT = 0.15;
    private static final double VWAP_DEVIATION_VOL_MULTIPLIER = 2.0;
    private static final double FAIR_NOISE_MEMORY = 0.85;
    private static final double FAIR_NOISE_LIMIT_MULTIPLIER = 3.0;

    private long randomState;

    /**
     * Bounded reference price, including temporary order-flow displacement.
     */
    private double price;
    /**
     * Fundamental/reference process independent of executable trades and VWAP.
     */
    private double fundamentalPrice;
    /**
     * Temporary, non-cumulative level displacement from signed order flow.
     */
    private double transientImpact;
    /**
     * Checkpoint migration marker; legacy saves lack the three new fields.
     */
    private boolean initialized;

    private double anchor;
    private double start;
    private double target;
    private Regime regime;
    private int age;
    private int duration;
    private double fairNoise;

    MarketDynamics(double initialPrice, long seed) {
        if (!Double.isFinite(initialPrice)) {
            throw new IllegalArgumentException("Initial price must be finite");
        }
        price = initialPrice;
        fundamentalPrice = initialPrice;
        transientImpact = 0.0;
        initialized = true;
        anchor = initialPrice;
        start = initialPrice;
        target = initialPrice;
        randomState = seed;
    }

    /**
     * Also upgrades checkpoints made before fundamentalPrice / transientImpact
     * were added. If you use a manual checkpoint codec, include those fields
     * and initialized in the new format.
     */
    void validate(long lower, long upper) {
        migrateLegacyCheckpoint();
        if (lower > upper || !inBounds(price, lower, upper)
            || !inBounds(fundamentalPrice, lower, upper)
            || !Double.isFinite(transientImpact)
            || !Double.isFinite(fairNoise)
            || !inBounds(anchor, lower, upper)
            || !inBounds(start, lower, upper)
            || !inBounds(target, lower, upper)
            || age < 0
            || duration < 0
            || age > duration
            || (duration > 0 && regime == null)) {
            throw new IllegalArgumentException("Invalid saved market regime");
        }
    }

    /**
     * Advances the hidden reference process by one simulation tick.
     *
     * @param flow signed, normalized aggressive order imbalance in [-1, 1];
     *             0 = neutral, +1 = all aggressive buys, -1 = all sells.
     */
    double step(SimulatedInstrument.Settings settings, double flow) {
        migrateLegacyCheckpoint();
        validateSettings(settings);
        if (regime == null || age >= duration) {
            next(settings);
        }
        age++;

        double progress = age / (double) duration;
        double path = progress;
        double noiseScale = settings.volatility * 0.45;
        double speed = 0.10;

        switch (regime) {
            case RANGE -> {
                path = 1.0;
                noiseScale = settings.volatility * 0.65;
                speed = 0.035;
            }
            case REVERSION -> {
                path = (1.0 - Math.exp(-3.0 * progress)) / (1.0 - Math.exp(-3.0));
            }
            case BREAKOUT_UP, BREAKOUT_DOWN -> {
                path = Math.min(1.0, progress / 0.22);
                speed = 0.28;
                if (progress < 0.22) {
                    noiseScale *= 2.0;
                }
            }
            case TREND_UP, TREND_DOWN -> {
                // Regular trend follows a gradually moving phase destination.
            }
        }

        double destination = start + (target - start) * path;
        double fundamentalCandidate = fundamentalPrice + (destination - fundamentalPrice) * speed
            + gaussian() * noiseScale;
        fundamentalPrice = bound(fundamentalCandidate, settings);

        // Flow moves the *level* temporarily. It must not be added to the
        // fundamental price on every tick, which would accumulate indefinitely.
        double signedFlow = Double.isFinite(flow) ? clamp(flow, -1.0, 1.0) : 0.0;
        double maxImpact = Math.max(0.0, fundamentalPrice * MAX_FLOW_IMPACT_BPS / 10000.0);
        double desiredImpact = clamp(settings.impact * signedFlow, -maxImpact, maxImpact);
        transientImpact += (desiredImpact - transientImpact) * FLOW_FOLLOW_RATE;
        price = bound(fundamentalPrice + transientImpact, settings);

        // Follow only the fundamental, not the trade-flow distortion.
        anchor = bound(anchor + (fundamentalPrice - anchor) * ANCHOR_FOLLOW_RATE, settings);
        return price;
    }

    private void next(SimulatedInstrument.Settings settings) {
        Regime previous = regime;
        double choice = random();
        if (previous == Regime.BREAKOUT_UP || previous == Regime.BREAKOUT_DOWN) {
            regime = choice < 0.5 ? Regime.RANGE : Regime.REVERSION;
        } else if (previous == Regime.TREND_UP || previous == Regime.TREND_DOWN) {
            regime = choice < 0.45 ? Regime.REVERSION
                : choice < 0.65 ? Regime.RANGE : random() < 0.5 ? Regime.BREAKOUT_UP : Regime.BREAKOUT_DOWN;
        } else {
            regime = choice < 0.18 ? Regime.RANGE
                : choice < 0.48 ? Regime.TREND_UP
                    : choice < 0.78 ? Regime.TREND_DOWN : random() < 0.5 ? Regime.BREAKOUT_UP : Regime.BREAKOUT_DOWN;
        }

        long span = (long) settings.regimeMaxSteps - settings.regimeMinSteps + 1;
        duration = settings.regimeMinSteps + (int) (random() * span);
        age = 0;
        start = fundamentalPrice;

        if (regime == Regime.RANGE) {
            target = start;
            return;
        }
        if (regime == Regime.REVERSION) {
            target = bound(start + (anchor - start) * (0.5 + random() * 0.3), settings);
            return;
        }

        boolean breakout = regime == Regime.BREAKOUT_UP || regime == Regime.BREAKOUT_DOWN;
        double direction = (regime == Regime.TREND_DOWN || regime == Regime.BREAKOUT_DOWN) ? -1.0 : 1.0;
        double magnitude = 0.7 + random() * 0.6;
        double moveBps = breakout ? settings.breakoutMoveBps : settings.trendMoveBps;
        double move = start * moveBps / 10000.0 * magnitude;

        // Do not flip a down regime into an up regime merely because the lower
        // boundary is close. That creates strong upward bias with asymmetric
        // price limits. Instead, clip the intended phase destination.
        target = bound(start + direction * move, settings);

        if (breakout) {
            duration = Math.max(20, duration / 3);
            // A breakout can move the medium-term equilibrium, but only in
            // the chosen (symmetric) direction.
            anchor = bound(anchor + direction * move * 0.65, settings);
        }
    }

    /**
     * Hidden directional signal for staggered bot decisions.
     */
    double pressure() {
        if (regime == null || regime == Regime.RANGE) {
            return 0.0;
        }
        double direction = Math.signum(target - start);
        if (regime == Regime.REVERSION) {
            return direction * 0.45;
        }
        if (regime == Regime.BREAKOUT_UP || regime == Regime.BREAKOUT_DOWN) {
            return direction * 0.9;
        }
        return direction * 0.7;
    }

    /**
     * Inventory-neutral directional quote shading; never changes VWAP.
     */
    double quotePremium(SimulatedInstrument.Settings settings, double fairValue) {
        boolean breakout = regime == Regime.BREAKOUT_UP || regime == Regime.BREAKOUT_DOWN;
        double bps = breakout ? settings.breakoutMoveBps * 0.5 : settings.trendMoveBps * 0.35;
        return fairValue * bps / 10000.0 * pressure();
    }

    /**
     * Produces a lightly VWAP-informed quote center without letting a rising
     * trade VWAP ratchet the entire reference process upward indefinitely.
     * This deliberately changes the old "VWAP + noise" behavior.
     */
    double noisyFair(double average, double volatility) {
        migrateLegacyCheckpoint();
        double vol = Double.isFinite(volatility) ? Math.max(0.0, volatility) : 0.0;
        double shock = (random() + random() + random() - 1.5) * vol;
        fairNoise = clamp(
            fairNoise * FAIR_NOISE_MEMORY + shock,
            -FAIR_NOISE_LIMIT_MULTIPLIER * vol,
            FAIR_NOISE_LIMIT_MULTIPLIER * vol);

        double deviation = Double.isFinite(average) ? average - price : 0.0;
        double limitedDeviation = clamp(
            deviation,
            -VWAP_DEVIATION_VOL_MULTIPLIER * vol,
            VWAP_DEVIATION_VOL_MULTIPLIER * vol);
        return price + VWAP_WEIGHT * limitedDeviation + fairNoise;
    }

    boolean burst() {
        return (regime == Regime.BREAKOUT_UP || regime == Regime.BREAKOUT_DOWN) && age < duration * 0.25;
    }

    double price() {
        return price;
    }

    Regime regime() {
        return regime;
    }

    int age() {
        return age;
    }

    private void migrateLegacyCheckpoint() {
        if (!initialized) {
            fundamentalPrice = price;
            transientImpact = 0.0;
            initialized = true;
        }
    }

    private static void validateSettings(SimulatedInstrument.Settings settings) {
        if (settings == null || settings.lowerPrice < 0
            || settings.lowerPrice >= settings.upperPrice
            || settings.regimeMinSteps <= 0
            || settings.regimeMaxSteps < settings.regimeMinSteps
            || !Double.isFinite(settings.volatility)
            || settings.volatility < 0.0
            || !Double.isFinite(settings.impact)
            || settings.impact < 0.0
            || !Double.isFinite(settings.trendMoveBps)
            || settings.trendMoveBps < 0.0
            || !Double.isFinite(settings.breakoutMoveBps)
            || settings.breakoutMoveBps < 0.0) {
            throw new IllegalArgumentException("Invalid market dynamics settings");
        }
    }

    private static boolean inBounds(double value, long lower, long upper) {
        return Double.isFinite(value) && value >= lower && value <= upper;
    }

    private static double bound(double value, SimulatedInstrument.Settings settings) {
        return clamp(value, settings.lowerPrice, settings.upperPrice);
    }

    private static double clamp(double value, double lower, double upper) {
        return Math.max(lower, Math.min(upper, value));
    }

    private double gaussian() {
        double u = Math.max(1e-12, random());
        return Math.sqrt(-2.0 * Math.log(u)) * Math.cos(2.0 * Math.PI * random());
    }

    /**
     * SplitMix64; randomState is part of the persisted checkpoint.
     */
    private double random() {
        long value = (randomState += 0x9E3779B97F4A7C15L);
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return ((value ^ (value >>> 31)) >>> 11) * 0x1.0p-53;
    }
}
