package com.yuelengm.nhkantan.options;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** European cash-settled option math with zero interest; time and volatility use game years. */
public final class OptionPricing {

    public static final long TICKS_PER_DAY = 24000;
    public static final long TICKS_PER_YEAR = 365 * TICKS_PER_DAY;

    public enum Kind {
        CALL,
        PUT
    }

    public static final class Value {

        public final double premium;
        public final double delta;

        private Value(double premium, double delta) {
            this.premium = premium;
            this.delta = delta;
        }
    }

    private OptionPricing() {}

    public static Value value(Kind kind, double spot, double strike, long remainingGameTicks, double volatility) {
        if (kind == null || !Double.isFinite(spot)
            || !Double.isFinite(strike)
            || spot <= 0
            || strike <= 0
            || remainingGameTicks < 0
            || !Double.isFinite(volatility)
            || volatility < 0) {
            throw new IllegalArgumentException("Invalid option pricing inputs");
        }
        double intrinsic = kind == Kind.CALL ? Math.max(0, spot - strike) : Math.max(0, strike - spot);
        if (remainingGameTicks == 0 || volatility == 0) {
            double callDelta = spot > strike ? 1 : spot < strike ? 0 : 0.5;
            return new Value(intrinsic, kind == Kind.CALL ? callDelta : callDelta - 1);
        }
        double years = (double) remainingGameTicks / TICKS_PER_YEAR;
        double scale = volatility * Math.sqrt(years);
        double d1 = (Math.log(spot) - Math.log(strike)) / scale + scale / 2;
        double d2 = d1 - scale;
        double call = spot * normal(d1) - strike * normal(d2);
        double premium = kind == Kind.CALL ? call : call - spot + strike;
        double delta = kind == Kind.CALL ? normal(d1) : normal(d1) - 1;
        if (!Double.isFinite(premium) || !Double.isFinite(delta)) {
            throw new IllegalArgumentException("Option calculation exceeds numeric limits");
        }
        return new Value(Math.max(intrinsic, premium), delta);
    }

    /** Charge a whole order once, rounding up to cents; never silently saturate an unaffordable price. */
    public static long askCents(double premium, double markup, double minimumMarkup, long multiplier, long quantity) {
        if (!Double.isFinite(premium) || premium < 0
            || !Double.isFinite(markup)
            || markup < 0
            || !Double.isFinite(minimumMarkup)
            || minimumMarkup < 0
            || multiplier < 1
            || quantity < 1) {
            throw new IllegalArgumentException("Invalid option quote");
        }
        double ask = Math.max(premium * (1 + markup), premium + minimumMarkup);
        if (!Double.isFinite(ask)) throw new IllegalArgumentException("Option quote exceeds numeric limits");
        return BigDecimal.valueOf(ask)
            .multiply(BigDecimal.valueOf(multiplier))
            .multiply(BigDecimal.valueOf(quantity))
            .movePointRight(2)
            .setScale(0, RoundingMode.CEILING)
            .longValueExact();
    }

    private static double normal(double x) {
        double absolute = Math.abs(x);
        double t = 1 / (1 + 0.2316419 * absolute);
        double polynomial = t
            * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        double upper = 1 - Math.exp(-0.5 * absolute * absolute) * polynomial / Math.sqrt(2 * Math.PI);
        return x >= 0 ? upper : 1 - upper;
    }
}
