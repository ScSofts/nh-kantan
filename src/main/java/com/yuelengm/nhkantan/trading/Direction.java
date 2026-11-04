package com.yuelengm.nhkantan.trading;

/**
 * Direction of a margin position: betting the price goes up or down.
 */
public enum Direction {

    LONG,
    SHORT;

    /** {@code +1} for long, {@code -1} for short; multiplies the price change into profit. */
    public int sign() {
        return this == LONG ? 1 : -1;
    }
}
