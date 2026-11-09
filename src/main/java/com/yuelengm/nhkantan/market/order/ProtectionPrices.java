package com.yuelengm.nhkantan.market.order;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Converts a gross P/L target for the protected quantity to an executable price tick. */
public final class ProtectionPrices {

    private ProtectionPrices() {}

    public static long fromProfit(long positionQuantity, long positionCostCents, long protectedQuantity,
        long targetCents, long tickValueCents) {
        if (positionQuantity == 0 || positionQuantity == Long.MIN_VALUE
            || positionCostCents < 0
            || protectedQuantity < 1
            || protectedQuantity > Math.abs(positionQuantity)
            || tickValueCents < 1) throw new IllegalArgumentException("Invalid protection calculation");
        BigDecimal entry = BigDecimal.valueOf(positionCostCents)
            .divide(BigDecimal.valueOf(Math.abs(positionQuantity)), 24, RoundingMode.HALF_UP);
        BigDecimal change = BigDecimal.valueOf(targetCents)
            .multiply(BigDecimal.valueOf(Long.signum(positionQuantity)))
            .divide(BigDecimal.valueOf(protectedQuantity), 24, RoundingMode.HALF_UP);
        // Round in the profit direction so a take-profit never promises an unattainable amount.
        long ticks = entry.add(change)
            .divide(
                BigDecimal.valueOf(tickValueCents),
                0,
                positionQuantity > 0 ? RoundingMode.CEILING : RoundingMode.FLOOR)
            .longValueExact();
        if (ticks < 1) throw new IllegalArgumentException("Protection price must be positive");
        return ticks;
    }
}
