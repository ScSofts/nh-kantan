package com.yuelengm.nhkantan.wallet;

/**
 * Market currency amounts are fixed-point {@code long}s in cents (hundredths of a unit), so every calculation keeps
 * two decimal places exactly. Conversions from fractional values round with a small tolerance that absorbs floating
 * point noise, so that for example {@code 1 x 1.1} costs 1.10 and not 1.11.
 */
public class Money {

    /** Cents per whole unit of market currency. */
    public static final long CENTS_PER_UNIT = 100L;

    private static final double EPSILON = 1.0E-9D;

    private Money() {}

    /** Whole currency units to cents. */
    public static long cents(long units) {
        return units * CENTS_PER_UNIT;
    }

    /** A fractional amount of currency units to cents, rounded down; used for amounts credited to the player. */
    public static long floorCents(double units) {
        return floor(units * CENTS_PER_UNIT);
    }

    /** A fractional amount of currency units to cents, rounded up; used for amounts charged to the player. */
    public static long ceilCents(double units) {
        return ceil(units * CENTS_PER_UNIT);
    }

    /** Rounds down with tolerance for floating point noise. */
    public static long floor(double value) {
        return (long) Math.floor(value + EPSILON);
    }

    /** Rounds up with tolerance for floating point noise. */
    public static long ceil(double value) {
        return (long) Math.ceil(value - EPSILON);
    }
}
