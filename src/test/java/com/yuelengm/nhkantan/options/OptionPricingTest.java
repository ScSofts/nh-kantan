package com.yuelengm.nhkantan.options;

import static org.junit.Assert.*;

import org.junit.Test;

public class OptionPricingTest {

    @Test
    public void callPutParityAndDeltaUseGameYears() {
        long time = 14 * OptionPricing.TICKS_PER_DAY;
        OptionPricing.Value call = OptionPricing.value(OptionPricing.Kind.CALL, 100, 95, time, 0.8);
        OptionPricing.Value put = OptionPricing.value(OptionPricing.Kind.PUT, 100, 95, time, 0.8);
        assertEquals(5, call.premium - put.premium, 1e-8);
        assertEquals(1, call.delta - put.delta, 1e-8);
        assertTrue(call.delta > 0 && call.delta < 1);
        assertTrue(put.delta < 0 && put.delta > -1);
        assertTrue(call.premium > 5);
    }

    @Test
    public void expiryAndZeroVolatilityAreFinite() {
        assertEquals(10, OptionPricing.value(OptionPricing.Kind.CALL, 110, 100, 0, 0.8).premium, 0);
        assertEquals(10, OptionPricing.value(OptionPricing.Kind.PUT, 90, 100, 1, 0).premium, 0);
        assertEquals(0, OptionPricing.value(OptionPricing.Kind.PUT, 110, 100, 0, 0).premium, 0);
        assertEquals(0.5, OptionPricing.value(OptionPricing.Kind.CALL, 100, 100, 0, 0).delta, 0);
    }

    @Test
    public void quoteChargesMarkupAndQuantityOnce() {
        assertEquals(208, OptionPricing.askCents(1, 0.04, 0.01, 1, 2));
        // (0.001 + 0.01) * 2 * 2 = 0.044 currency units, charged as 5 cents.
        assertEquals(5, OptionPricing.askCents(0.001, 0.04, 0.01, 2, 2));
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidVolatilityIsRejected() {
        OptionPricing.value(OptionPricing.Kind.CALL, 100, 100, 1, Double.NaN);
    }
}
