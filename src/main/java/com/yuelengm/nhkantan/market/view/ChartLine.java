package com.yuelengm.nhkantan.market.view;

/** A terminal-only price annotation. Never included in public market broadcasts. */
public final class ChartLine {

    public final double price;
    public final String label;
    public final int color;

    public ChartLine(double price, String label, int color) {
        this.price = price;
        this.label = label;
        this.color = color;
    }
}
