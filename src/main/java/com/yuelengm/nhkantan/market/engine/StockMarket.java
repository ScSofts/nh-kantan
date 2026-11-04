package com.yuelengm.nhkantan.market.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import javax.annotation.Nullable;

import com.yuelengm.nhkantan.NHKantan;
import com.yuelengm.nhkantan.market.PriceUpdate;
import com.yuelengm.nhkantan.market.PriceWindow;
import com.yuelengm.nhkantan.market.StockSnapshot;
import com.yuelengm.nhkantan.market.definition.MarketDefinitions;
import com.yuelengm.nhkantan.market.definition.StockDefinition;

/**
 * The running market: advances all enabled stocks and records every new price.
 * <p>
 * The {@link PriceStore} is the only persisted data. On startup each stock resumes from its newest stored price (or its
 * initial price if it has none) and enters a randomly chosen mode; mode state is never restored. Only the most recent
 * prices are kept in memory, for client snapshots.
 */
public class StockMarket {

    private final PriceStore priceStore;
    private final Random random;
    private final List<Stock> stocks = new ArrayList<>();
    private final Set<String> failingStocks = new HashSet<>();

    /**
     * @param recentPrices number of most recent prices kept in memory and sent to clients per stock
     */
    public StockMarket(MarketDefinitions definitions, PriceStore priceStore, int recentPrices, Random random) {
        this.priceStore = priceStore;
        this.random = random;
        for (StockDefinition definition : definitions.stocks()) {
            if (definition.enabled()) stocks.add(loadStock(definition, recentPrices));
        }
    }

    private Stock loadStock(StockDefinition definition, int recentPrices) {
        priceStore.repair(definition.id());
        PriceWindow window = new PriceWindow(recentPrices);
        window.addAll(priceStore.readTail(definition.id(), recentPrices));
        double price = window.isEmpty() ? definition.initialPrice() : resumePrice(definition, window.latest());
        if (window.isEmpty() || price != window.latest()) {
            // A new stock, or bounds that changed while the server was down: record the starting price
            priceStore.append(definition.id(), (float) price);
            window.add((float) price);
        }
        StockState state = new StockState(definition.id(), price);
        PriceEngine.enterRandomMode(definition, state, random);
        return new Stock(definition, state, window);
    }

    /** The stored price, clamped to possibly edited bounds; unusable values fall back to the initial price. */
    private static double resumePrice(StockDefinition definition, float stored) {
        boolean valid = !Float.isNaN(stored) && !Float.isInfinite(stored) && stored > 0.0F;
        return valid ? definition.clamp(stored) : definition.initialPrice();
    }

    public boolean isEmpty() {
        return stocks.isEmpty();
    }

    /**
     * Advances every enabled stock by one step.
     *
     * @return the new price of every stock that was updated
     */
    public List<PriceUpdate> update() {
        List<PriceUpdate> updates = new ArrayList<>(stocks.size());
        for (Stock stock : stocks) {
            String id = stock.definition.id();
            try {
                PriceEngine.step(stock.definition, stock.state, random);
                failingStocks.remove(id);
            } catch (PriceEvaluationException e) {
                // Keep the price unchanged; log once per failure streak to avoid spamming every update
                if (failingStocks.add(id)) {
                    NHKantan.LOG.warn("Stock '{}' skipped a price update: {}", id, e.getMessage());
                }
                continue;
            }
            float price = (float) stock.state.price();
            priceStore.append(id, price);
            stock.window.add(price);
            updates.add(new PriceUpdate(id, price));
        }
        return updates;
    }

    /**
     * @return the definition of the running (enabled) stock, or {@code null} when it is not running
     */
    @Nullable
    public StockDefinition definition(String stockId) {
        for (Stock stock : stocks) {
            if (stock.definition.id()
                .equals(stockId)) return stock.definition;
        }
        return null;
    }

    /** Public snapshots of all enabled stocks, in definition order. */
    public List<StockSnapshot> snapshots() {
        if (stocks.isEmpty()) return Collections.emptyList();
        List<StockSnapshot> snapshots = new ArrayList<>(stocks.size());
        for (Stock stock : stocks) {
            StockDefinition definition = stock.definition;
            snapshots.add(
                new StockSnapshot(
                    definition.id(),
                    definition.name(),
                    definition.lowerBound(),
                    definition.upperBound(),
                    definition.leverage(),
                    stock.window.toArray()));
        }
        return snapshots;
    }

    /** A running stock: its definition, runtime state and its most recent prices. */
    private static class Stock {

        private final StockDefinition definition;
        private final StockState state;
        private final PriceWindow window;

        private Stock(StockDefinition definition, StockState state, PriceWindow window) {
            this.definition = definition;
            this.state = state;
            this.window = window;
        }
    }
}
