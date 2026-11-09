package com.yuelengm.nhkantan.market.order;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;

import com.yuelengm.nhkantan.trading.Direction;
import com.yuelengm.nhkantan.trading.Position;

public class LegacyEquityMigrationTest {

    @Test
    public void equityRestoresMarginAndProfitWithoutChargingOpeningFeeTwice() {
        UUID owner = UUID.randomUUID();
        Position position = new Position(1, "stock", Direction.LONG, 10000, 2, 100, 55, 100);
        Map<UUID, Long> cash = Collections.singletonMap(owner, 12300L);
        Map<UUID, Long> result = LegacyEquityMigration.calculate(
            cash,
            Collections.singletonMap(owner, Collections.singletonList(position)),
            Collections.singletonMap("stock", 110D));
        assertEquals(Long.valueOf(24300), result.get(owner));
        assertEquals(Long.valueOf(12300), cash.get(owner));
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingPriceRefusesMigrationInsteadOfErasingAssets() {
        UUID owner = UUID.randomUUID();
        LegacyEquityMigration.calculate(
            Collections.singletonMap(owner, 100L),
            Collections.singletonMap(
                owner,
                Collections.singletonList(new Position(1, "missing", Direction.SHORT, 10000, 2, 100, 0, 100))),
            Collections.emptyMap());
    }
}
