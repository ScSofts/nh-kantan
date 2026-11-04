package com.yuelengm.nhkantan.trading;

/**
 * One open margin position: direction, margin, leverage, entry price and fee paid on opening.
 */
public class Position {

    private final long id;
    private final String stockId;
    private final Direction direction;
    private final long marginCents;
    private final int leverage;
    private final double openPrice;
    private final long openFeeCents;
    private final double liquidationPercent;

    public Position(long id, String stockId, Direction direction, long marginCents, int leverage, double openPrice,
        long openFeeCents, double liquidationPercent) {
        this.id = id;
        this.stockId = stockId;
        this.direction = direction;
        this.marginCents = marginCents;
        this.leverage = leverage;
        this.openPrice = openPrice;
        this.openFeeCents = openFeeCents;
        this.liquidationPercent = liquidationPercent;
    }

    public long id() {
        return id;
    }

    public String stockId() {
        return stockId;
    }

    public Direction direction() {
        return direction;
    }

    public long marginCents() {
        return marginCents;
    }

    public int leverage() {
        return leverage;
    }

    public double openPrice() {
        return openPrice;
    }

    public long openFeeCents() {
        return openFeeCents;
    }

    public double liquidationPercent() {
        return liquidationPercent;
    }

    public long notionalCents() {
        return TradingMath.notional(marginCents, leverage);
    }

    public long unrealizedCents(double price) {
        return TradingMath.unrealized(marginCents, leverage, direction, openPrice, price);
    }

    public boolean liquidated(double price) {
        return TradingMath.liquidated(unrealizedCents(price), marginCents, liquidationPercent);
    }
}
