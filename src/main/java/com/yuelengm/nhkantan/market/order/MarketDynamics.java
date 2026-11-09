package com.yuelengm.nhkantan.market.order;

/** Hidden, persistent market regimes. This model informs orders and never creates a trade or candle. */
final class MarketDynamics {

    enum Regime {
        RANGE,
        TREND_UP,
        TREND_DOWN,
        REVERSION,
        BREAKOUT_UP,
        BREAKOUT_DOWN
    }

    private long randomState;
    private double price;
    private double anchor;
    private double start;
    private double target;
    private Regime regime;
    private int age;
    private int duration;
    private double fairNoise;

    MarketDynamics(double price, long seed) {
        this.price = price;
        anchor = price;
        start = price;
        target = price;
        randomState = seed;
    }

    void validate(long lower, long upper) {
        if (!Double.isFinite(price) || price < lower
            || price > upper
            || !Double.isFinite(fairNoise)
            || !Double.isFinite(anchor)
            || anchor < lower
            || anchor > upper
            || !Double.isFinite(start)
            || !Double.isFinite(target)
            || start < lower
            || start > upper
            || target < lower
            || target > upper
            || age < 0
            || duration < 0
            || age > duration
            || (duration > 0 && regime == null)) throw new IllegalArgumentException("Invalid saved market regime");
    }

    double step(SimulatedInstrument.Settings settings, double flow) {
        if (regime == null || age >= duration) next(settings);
        age++;
        double progress = age / (double) duration;
        double noise = settings.volatility * 0.45;
        double speed = 0.10;
        double path = progress;
        switch (regime) {
            case RANGE -> {
                path = 1;
                noise = settings.volatility * 0.65;
                speed = 0.035;
            }
            case REVERSION -> path = (1 - Math.exp(-3 * progress)) / (1 - Math.exp(-3));
            case BREAKOUT_UP, BREAKOUT_DOWN -> {
                path = Math.min(1, progress / 0.22);
                speed = 0.28;
                noise *= progress < 0.22 ? 2 : 1;
            }
            default -> { /* Persistent trend follows the phase's moving target. */ }
        }
        double destination = start + (target - start) * path;
        double gaussian = Math.sqrt(-2 * Math.log(Math.max(1E-12, random()))) * Math.cos(2 * Math.PI * random());
        double candidate = price + (destination - price) * speed + gaussian * noise + settings.impact * flow;
        price = bound(candidate, settings);
        anchor = bound(anchor + (price - anchor) * 0.001, settings);
        return price;
    }

    private void next(SimulatedInstrument.Settings settings) {
        Regime previous = regime;
        double choice = random();
        if (previous == Regime.BREAKOUT_UP || previous == Regime.BREAKOUT_DOWN) {
            // Consolidation and a partial retracement can follow a breakout, rather than immediately resetting it.
            regime = choice < 0.5 ? Regime.RANGE : Regime.REVERSION;
        } else if (previous == Regime.TREND_UP || previous == Regime.TREND_DOWN) {
            regime = choice < 0.45 ? Regime.REVERSION
                : choice < 0.65 ? Regime.RANGE : random() < 0.5 ? Regime.BREAKOUT_UP : Regime.BREAKOUT_DOWN;
        } else {
            regime = choice < 0.18 ? Regime.RANGE
                : choice < 0.48 ? Regime.TREND_UP
                    : choice < 0.78 ? Regime.TREND_DOWN : random() < 0.5 ? Regime.BREAKOUT_UP : Regime.BREAKOUT_DOWN;
        }
        duration = settings.regimeMinSteps + (int) (random() * (settings.regimeMaxSteps - settings.regimeMinSteps + 1));
        age = 0;
        start = price;
        double magnitude = 0.7 + random() * 0.6;
        double direction = regime == Regime.TREND_DOWN || regime == Regime.BREAKOUT_DOWN ? -1 : 1;
        if (regime == Regime.RANGE) target = start;
        else if (regime == Regime.REVERSION) target = start + (anchor - start) * (0.5 + random() * 0.3);
        else {
            boolean breakout = regime == Regime.BREAKOUT_UP || regime == Regime.BREAKOUT_DOWN;
            double move = start * (breakout ? settings.breakoutMoveBps : settings.trendMoveBps) / 10000D * magnitude;
            // Turn away from a hard price boundary instead of spending entire phases clipped against it.
            double room = direction > 0 ? settings.upperPrice - start : start - settings.lowerPrice;
            if (room < move * 0.5) {
                direction = -direction;
                regime = breakout ? (direction > 0 ? Regime.BREAKOUT_UP : Regime.BREAKOUT_DOWN)
                    : (direction > 0 ? Regime.TREND_UP : Regime.TREND_DOWN);
            }
            target = start + direction * move;
            if (breakout) {
                duration = Math.max(20, duration / 3);
                anchor = bound(anchor + direction * move * 0.65, settings);
            }
        }
        target = bound(target, settings);
    }

    /** Persistent directional order-flow signal; does not publish the hidden price or phase to players. */
    double pressure() {
        if (regime == null || regime == Regime.RANGE) return 0;
        double sign = Math.signum(target - start);
        return sign * (regime == Regime.REVERSION ? 0.45
            : regime == Regime.BREAKOUT_UP || regime == Regime.BREAKOUT_DOWN ? 0.9 : 0.7);
    }

    /** Institutions shade executable quotes for directional risk; this never changes the VWAP fair-value definition. */
    double quotePremium(SimulatedInstrument.Settings settings, double fairValue) {
        boolean breakout = regime == Regime.BREAKOUT_UP || regime == Regime.BREAKOUT_DOWN;
        double bps = breakout ? settings.breakoutMoveBps * 0.5 : settings.trendMoveBps * 0.35;
        return fairValue * bps / 10000D * pressure();
    }

    /** Fair value follows traded VWAP, with bounded, smoothed noise rather than a fixed starting-price anchor. */
    double noisyFair(double average, double volatility) {
        double shock = (random() + random() + random() - 1.5) * volatility;
        fairNoise = Math.max(-3 * volatility, Math.min(3 * volatility, fairNoise * 0.85 + shock));
        return average + fairNoise;
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

    private static double bound(double value, SimulatedInstrument.Settings settings) {
        return Math.max(settings.lowerPrice, Math.min(settings.upperPrice, value));
    }

    /** SplitMix64, with its complete state included in the checkpoint. */
    private double random() {
        long value = (randomState += 0x9E3779B97F4A7C15L);
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return ((value ^ (value >>> 31)) >>> 11) * 0x1.0p-53;
    }
}
