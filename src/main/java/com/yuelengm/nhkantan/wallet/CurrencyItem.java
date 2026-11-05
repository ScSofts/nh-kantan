package com.yuelengm.nhkantan.wallet;

import net.minecraft.item.ItemStack;

import com.yuelengm.nhkantan.util.Denominations;

/**
 * An item family (see {@link Denominations}) priced per base unit. The same type represents normal exchange entries
 * and debt repayment entries.
 * <p>
 * A positive withdrawal value makes the item withdrawable from a positive balance. The special value
 * {@link #DEBT_WITHDRAW_VALUE} marks a debt-only entry: it cannot be deposited or withdrawn as currency, but its
 * deposit value is the amount of debt repaid per base unit. Other negative withdrawal values merely disable withdrawal.
 */
public class CurrencyItem {

    /** Withdrawal value marker for entries in the debt list. */
    public static final double DEBT_WITHDRAW_VALUE = -1.0D;

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

    /** Whether this entry belongs to the debt repayment list. */
    public boolean isDebtItem() {
        return withdrawValue == DEBT_WITHDRAW_VALUE;
    }

    public boolean canDeposit() {
        return !isDebtItem() && depositValue > 0.0D;
    }

    public boolean canWithdraw() {
        return withdrawValue > 0.0D;
    }

    public boolean canRepay() {
        return isDebtItem() && depositValue > 0.0D;
    }

    /** Cents credited for depositing {@code units} base units. Rounded down. */
    public long depositCredit(int units) {
        return Money.floorCents(units * depositValue);
    }

    /** Cents charged for withdrawing {@code units} base units. Rounded up. */
    public long withdrawCost(int units) {
        return Money.ceilCents(units * withdrawValue);
    }

    /** Cents repaid by handing in {@code units} base units from the debt list. Rounded down. */
    public long repayment(int units) {
        return Money.floorCents(units * depositValue);
    }

    /** Base units needed to clear {@code debt} cents; the last one may be worth more than what is left. */
    public int unitsToClear(long debt) {
        if (debt <= 0L || !canRepay()) return 0;
        return (int) Math.min(Integer.MAX_VALUE, Money.ceil(debt / (depositValue * Money.CENTS_PER_UNIT)));
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
