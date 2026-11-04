package com.yuelengm.nhkantan.trading;

import com.yuelengm.nhkantan.util.MathUtils;
import com.yuelengm.nhkantan.wallet.Money;

/**
 * Pure margin math shared by previews and settlement, in fixed-point cents (see {@link Money}).
 */
public class TradingMath {

    private TradingMath() {}

    /**
     * Notional position of a trade in cents: margin times leverage. Bypasses {@link Money} scaling because both
     * operands are already fixed-point divided by the same unit, so the product is exact when it fits in a
     * {@code long}.
     */
    public static long notional(long marginCents, int leverage) {
        return MathUtils.saturatedMultiply(marginCents, leverage);
    }

    /** Opening fee in cents, rounded up; zero when the configured rate is zero. */
    public static long openFee(long notionalCents, double openFeePercent) {
        if (notionalCents <= 0L || openFeePercent <= 0.0D) return 0L;
        return Money.ceil(notionalCents * openFeePercent / 100.0D);
    }

    /**
     * Unrealized profit or loss in cents at {@code price}, rounded half away from zero to the nearest cent. Positive
     * for profit, negative for loss.
     */
    public static long unrealized(long marginCents, int leverage, Direction direction, double openPrice, double price) {
        if (marginCents <= 0L || openPrice <= 0.0D) return 0L;
        double raw = (double) marginCents * leverage * direction.sign() * (price / openPrice - 1.0D);
        double rounded = raw >= 0.0D ? Math.floor(raw + 0.5D) : Math.ceil(raw - 0.5D);
        if (rounded >= (double) Long.MAX_VALUE) return Long.MAX_VALUE;
        if (rounded <= (double) Long.MIN_VALUE) return Long.MIN_VALUE;
        return (long) rounded;
    }

    /** Whether the unrealized loss reaches the liquidation threshold of the initial margin; never when disabled. */
    public static boolean liquidated(long unrealizedCents, long marginCents, double liquidationPercent) {
        if (liquidationPercent <= 0.0D || unrealizedCents >= 0L) return false;
        double threshold = (double) marginCents * liquidationPercent / 100.0D;
        return (double) -unrealizedCents >= threshold;
    }
}
