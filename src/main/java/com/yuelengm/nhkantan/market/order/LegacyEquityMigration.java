package com.yuelengm.nhkantan.market.order;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.yuelengm.nhkantan.trading.Position;

/** Pure preflight for the user-approved incompatible migration; never deletes or mutates the source save. */
public final class LegacyEquityMigration {

    private LegacyEquityMigration() {}

    /**
     * Cash already excludes old opening fees and locked margin. Add that margin and current P/L exactly once.
     * The caller must durably commit the returned balances and migration marker before removing old records.
     */
    public static Map<UUID, Long> calculate(Map<UUID, Long> cash, Map<UUID, List<Position>> positions,
        Map<String, Double> lastPrices) {
        Map<UUID, Long> result = new HashMap<>(cash);
        for (Map.Entry<UUID, List<Position>> entry : positions.entrySet()) {
            UUID owner = entry.getKey();
            if (!cash.containsKey(owner)) throw new IllegalArgumentException("Position owner has no wallet: " + owner);
            long equity = cash.get(owner);
            for (Position position : entry.getValue()) {
                if (position == null || !Double.isFinite(position.openPrice())
                    || position.openPrice() <= 0
                    || position.marginCents() <= 0
                    || position.leverage() <= 0) {
                    throw new IllegalArgumentException("Invalid legacy position");
                }
                Double price = lastPrices.get(position.stockId());
                if (price == null || !Double.isFinite(price) || price <= 0) {
                    throw new IllegalArgumentException("Missing settlement price for " + position.stockId());
                }
                long profit = position.unrealizedCents(price);
                if (profit == Long.MIN_VALUE || profit == Long.MAX_VALUE) {
                    throw new ArithmeticException("Legacy profit exceeds monetary limits");
                }
                equity = Math.addExact(equity, Math.addExact(position.marginCents(), profit));
            }
            result.put(owner, equity);
        }
        return Collections.unmodifiableMap(result);
    }
}
