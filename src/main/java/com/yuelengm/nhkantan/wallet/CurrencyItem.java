package com.yuelengm.nhkantan.wallet;

/**
 * An item exchangeable with the market currency. A negative value disables that direction, which allows deposit-only
 * or withdraw-only items. Fees are fractions of the exchanged amount.
 */
public class CurrencyItem {

    private final ItemSpec item;
    private final double depositValue;
    private final double depositFee;
    private final double withdrawValue;
    private final double withdrawFee;

    public CurrencyItem(ItemSpec item, double depositValue, double depositFee, double withdrawValue,
        double withdrawFee) {
        this.item = item;
        this.depositValue = depositValue;
        this.depositFee = depositFee;
        this.withdrawValue = withdrawValue;
        this.withdrawFee = withdrawFee;
    }

    public ItemSpec item() {
        return item;
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

    /** Currency credited for depositing {@code count} items, after the fee. */
    public long depositCredit(int count) {
        return Money.floor(count * depositValue * (1.0D - depositFee));
    }

    /** Currency charged for withdrawing {@code count} items, including the fee. */
    public long withdrawCost(int count) {
        return Money.ceil(count * withdrawValue * (1.0D + withdrawFee));
    }
}
