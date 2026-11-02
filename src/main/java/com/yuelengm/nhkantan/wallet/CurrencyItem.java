package com.yuelengm.nhkantan.wallet;

import net.minecraft.item.ItemStack;

import com.yuelengm.nhkantan.util.Denominations;

/**
 * An item family (see {@link Denominations}) exchangeable with the market currency, priced per base unit. A negative
 * value disables that direction, which allows deposit-only or withdraw-only items.
 */
public class CurrencyItem {

    private final Denominations items;
    private final double depositValue;
    private final double withdrawValue;

    public CurrencyItem(Denominations items, double depositValue, double withdrawValue) {
        this.items = items;
        this.depositValue = depositValue;
        this.withdrawValue = withdrawValue;
    }

    /** The item family exchanged; amounts and values are in its base units. */
    public Denominations items() {
        return items;
    }

    /** One base-unit item, representing this entry in lists. */
    public ItemStack displayStack() {
        return items.base()
            .createStack(1);
    }

    public double depositValue() {
        return depositValue;
    }

    public double withdrawValue() {
        return withdrawValue;
    }

    public boolean canDeposit() {
        return depositValue > 0.0D;
    }

    public boolean canWithdraw() {
        return withdrawValue > 0.0D;
    }

    /** Cents credited for depositing {@code units} base units. Rounded down. */
    public long depositCredit(int units) {
        return Money.floorCents(units * depositValue);
    }

    /** Cents charged for withdrawing {@code units} base units. Rounded up. */
    public long withdrawCost(int units) {
        return Money.ceilCents(units * withdrawValue);
    }

    /**
     * The largest number of base units, at most {@code limit}, whose withdrawal cost does not exceed {@code balance}
     * cents.
     */
    public int affordable(long balance, int limit) {
        if (!canWithdraw() || balance <= 0) return 0;
        long count = (long) Math.min(limit, balance / (withdrawValue * Money.CENTS_PER_UNIT));
        while (count > 0 && withdrawCost((int) count) > balance) count--;
        return (int) count;
    }
}
