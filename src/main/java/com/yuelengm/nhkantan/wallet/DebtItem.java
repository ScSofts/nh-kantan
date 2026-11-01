package com.yuelengm.nhkantan.wallet;

import com.yuelengm.nhkantan.util.ItemSpec;

/**
 * An item accepted to repay a negative balance. It can never raise the balance above zero.
 */
public class DebtItem {

    private final ItemSpec item;
    private final double value;

    public DebtItem(ItemSpec item, double value) {
        this.item = item;
        this.value = value;
    }

    public ItemSpec item() {
        return item;
    }

    public double value() {
        return value;
    }

    /** Currency repaid by {@code count} items. */
    public long repayment(int count) {
        return Money.floor(count * value);
    }

    /** Items needed to clear {@code debt}; the last item may be worth more than what is left. */
    public int itemsToClear(long debt) {
        if (debt <= 0) return 0;
        return (int) Math.min(Integer.MAX_VALUE, Money.ceil(debt / value));
    }
}
