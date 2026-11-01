package com.yuelengm.nhkantan.wallet;

/**
 * Outcome of a wallet operation.
 */
public class WalletResult {

    /** Why an operation succeeded or failed. */
    public enum Status {

        DEPOSITED,
        WITHDRAWN,
        REPAID,
        IN_DEBT,
        NOT_IN_DEBT,
        NO_ITEMS,
        NOT_EXCHANGEABLE,
        INSUFFICIENT_BALANCE,
        INVENTORY_FULL;

        public boolean isSuccess() {
            return this == DEPOSITED || this == WITHDRAWN || this == REPAID;
        }
    }

    private final Status status;
    private final int itemCount;
    private final long amount;
    private final long balance;

    /**
     * @param itemCount items moved
     * @param amount    currency credited, charged or repaid
     * @param balance   balance after the operation
     */
    public WalletResult(Status status, int itemCount, long amount, long balance) {
        this.status = status;
        this.itemCount = itemCount;
        this.amount = amount;
        this.balance = balance;
    }

    public static WalletResult failure(Status status, long balance) {
        return new WalletResult(status, 0, 0L, balance);
    }

    public Status status() {
        return status;
    }

    public int itemCount() {
        return itemCount;
    }

    public long amount() {
        return amount;
    }

    public long balance() {
        return balance;
    }
}
