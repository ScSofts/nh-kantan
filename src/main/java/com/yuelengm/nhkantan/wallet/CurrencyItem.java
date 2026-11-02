package com.yuelengm.nhkantan.wallet;

import net.minecraft.item.ItemStack;

import com.yuelengm.nhkantan.util.Denominations;

/**
 * An item family (see {@link Denominations}) exchangeable with the market currency, priced per base unit. A negative
 * value disables that direction, which allows deposit-only or withdraw-only items. Fees are fractions of the exchanged
 * amount.
 */
public class CurrencyItem {

    private final Denominations items;
    private final double depositValue;
    private final double depositFee;
    private final double withdrawValue;
    private final double withdrawFee;

    public CurrencyItem(Denominations items, double depositValue, double depositFee, double withdrawValue,
        double withdrawFee) {
        this.items = items;
        this.depositValue = depositValue;
        this.depositFee = depositFee;
        this.withdrawValue = withdrawValue;
        this.withdrawFee = withdrawFee;
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

    public double depositFee() {
        return depositFee;
    }

    public double withdrawValue() {
        return withdrawValue;
    }

    public double withdrawFee() {
        return withdrawFee;
    }

    public boolean canDeposit() {
        return depositValue >= 0.0D;
    }

    public boolean canWithdraw() {
        return withdrawValue >= 0.0D;
    }

    /** Cents credited for depositing {@code units} base units, after the fee. */
    public long depositCredit(int units) {
        return Money.floorCents(units * depositValue * (1.0D - depositFee));
    }

    /** Cents charged for withdrawing {@code units} base units, including the fee. */
    public long withdrawCost(int units) {
        return Money.ceilCents(units * withdrawValue * (1.0D + withdrawFee));
    }

    /**
     * The largest number of base units, at most {@code limit}, whose withdrawal cost does not exceed {@code balance}
     * cents.
     */
    public int affordable(long balance, int limit) {
        if (!canWithdraw() || balance <= 0) return 0;
        long count = (long) Math.min(limit, balance / (withdrawValue * (1.0D + withdrawFee) * Money.CENTS_PER_UNIT));
        while (count > 0 && withdrawCost((int) count) > balance) count--;
        return (int) count;
    }
}
