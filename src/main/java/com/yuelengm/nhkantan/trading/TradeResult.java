package com.yuelengm.nhkantan.trading;

/**
 * Outcome of a trading operation.
 */
public class TradeResult {

    /** Why an operation succeeded or failed. */
    public enum Status {

        OPENED,
        CLOSED,
        LIQUIDATED,
        MARKET_UNAVAILABLE,
        NOT_TRADABLE,
        IN_DEBT,
        INVALID_MARGIN,
        INVALID_LEVERAGE,
        INSUFFICIENT_BALANCE,
        POSITION_LIMIT,
        STOCK_LIMIT,
        UNKNOWN_POSITION;

        public boolean isSuccess() {
            return this == OPENED || this == CLOSED || this == LIQUIDATED;
        }

    }

    private final Status status;
    private final long positionId;
    private final long amountCents;
    private final long balanceCents;

    /**
     * @param positionId   the position opened or closed, or 0 when no position is involved
     * @param amountCents  fee charged on open, profit or loss credited on close
     * @param balanceCents balance after the operation
     */
    public TradeResult(Status status, long positionId, long amountCents, long balanceCents) {
        this.status = status;
        this.positionId = positionId;
        this.amountCents = amountCents;
        this.balanceCents = balanceCents;
    }

    public static TradeResult failure(Status status, long balanceCents) {
        return new TradeResult(status, 0L, 0L, balanceCents);
    }

    public Status status() {
        return status;
    }

    public long positionId() {
        return positionId;
    }

    public long amountCents() {
        return amountCents;
    }

    public long balanceCents() {
        return balanceCents;
    }
}
