package com.yuelengm.nhkantan.market.definition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import com.yuelengm.nhkantan.market.LeverageRange;

/**
 * A stock from {@code stocks.json}: identity, price bounds, optional leverage limits and the modes it may run.
 */
public class StockDefinition {

    private final String id;
    private final String name;
    private final boolean enabled;
    private final double initialPrice;
    private final double lowerBound;
    private final double upperBound;
    private final LeverageRange leverage;
    private final List<StockModeEntry> modes;
    private final List<StockModeEntry> selectableModes;

    public StockDefinition(String id, String name, boolean enabled, double initialPrice, double lowerBound,
        double upperBound, LeverageRange leverage, List<StockModeEntry> modes) {
        this.id = id;
        this.name = name;
        this.enabled = enabled;
        this.initialPrice = initialPrice;
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
        this.leverage = leverage;
        this.modes = Collections.unmodifiableList(modes);
        List<StockModeEntry> selectable = new ArrayList<>();
        for (StockModeEntry entry : modes) {
            if (entry.weight() > 0.0D) selectable.add(entry);
        }
        this.selectableModes = Collections.unmodifiableList(selectable);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public boolean enabled() {
        return enabled;
    }

    public double initialPrice() {
        return initialPrice;
    }

    public double lowerBound() {
        return lowerBound;
    }

    public double upperBound() {
        return upperBound;
    }

    /** Per-stock leverage limits; unset ends fall back to the trading config. */
    public LeverageRange leverage() {
        return leverage;
    }

    public List<StockModeEntry> modes() {
        return modes;
    }

    /** Modes with a positive weight; only these can be entered. Never empty for a validated definition. */
    public List<StockModeEntry> selectableModes() {
        return selectableModes;
    }

    /**
     * @return the selectable entry for the mode id, or {@code null} if the mode cannot be run by this stock
     */
    @Nullable
    public StockModeEntry findSelectableMode(String modeId) {
        for (StockModeEntry entry : selectableModes) {
            if (entry.modeId()
                .equals(modeId)) return entry;
        }
        return null;
    }

    /** Applies the hard price bounds. */
    public double clamp(double price) {
        return Math.min(upperBound, Math.max(lowerBound, price));
    }
}
