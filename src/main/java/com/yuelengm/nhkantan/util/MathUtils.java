package com.yuelengm.nhkantan.util;

/**
 * Small numeric helpers missing from the Java 8 standard library.
 */
public class MathUtils {

    private MathUtils() {}

    /** {@code a + b}, saturating at {@link Long#MIN_VALUE} / {@link Long#MAX_VALUE} instead of overflowing. */
    public static long saturatedAdd(long a, long b) {
        long sum = a + b;
        // Overflow happens only when both operands share a sign that the sum does not
        if (((a ^ sum) & (b ^ sum)) < 0) return a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        return sum;
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
