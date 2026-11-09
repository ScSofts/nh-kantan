package com.yuelengm.nhkantan.market.order;

import static org.junit.Assert.*;

import org.junit.Test;

public class ProtectionPricesTest {

    @Test
    public void longAndShortTargetsUseProtectedQuantityAndTickValue() {
        assertEquals(10500, ProtectionPrices.fromProfit(10, 100000, 2, 1000, 1));
        assertEquals(9500, ProtectionPrices.fromProfit(10, 100000, 2, -1000, 1));
        assertEquals(9500, ProtectionPrices.fromProfit(-10, 100000, 2, 1000, 1));
        assertEquals(10500, ProtectionPrices.fromProfit(-10, 100000, 2, -1000, 1));
        assertEquals(5250, ProtectionPrices.fromProfit(10, 100000, 2, 1000, 2));
    }

    @Test
    public void fractionalAverageAndTickRoundInProfitDirection() {
        assertEquals(10001, ProtectionPrices.fromProfit(3, 30001, 3, 1, 1));
        assertEquals(9999, ProtectionPrices.fromProfit(-3, 30001, 3, 3, 1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void cannotProtectMoreThanThePosition() {
        ProtectionPrices.fromProfit(10, 100000, 11, 100, 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void impossibleLossTargetDoesNotSilentlyClampToAnotherPrice() {
        ProtectionPrices.fromProfit(1, 100, 1, -1000, 1);
    }
}
