package com.yuelengm.nhkantan.wallet;

import com.yuelengm.nhkantan.util.Denominations;

/**
 * An item family accepted to repay a negative balance, valued per base unit. It can never raise the balance above zero.
 */
public class DebtItem {

    private final Denominations items;
    private final double value;

    public DebtItem(Denominations items, double value) {
        this.items = items;
        this.value = value;
    }

    /** The item family exchanged; amounts and values are in its base units. */
    public Denominations items() {
        return items;
    }

    public double value() {
        return value;
    }

    /** Cents repaid by {@code units} base units. */
    public long repayment(int units) {
        return Money.floorCents(units * value);
    }

    /** Base units needed to clear {@code debt} cents; the last one may be worth more than what is left. */
    public int unitsToClear(long debt) {
        if (debt <= 0) return 0;
        return (int) Math.min(Integer.MAX_VALUE, Money.ceil(debt / (value * Money.CENTS_PER_UNIT)));
    }
}
