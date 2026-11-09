package com.yuelengm.nhkantan.options;

import static org.junit.Assert.*;

import java.util.UUID;

import org.junit.Test;

import com.yuelengm.nhkantan.market.order.MarginLedger;

public class OptionMarketTest {

    @Test
    public void expiredQuoteCannotChargeAndSettlementIsOnceAcrossRestart() {
        UUID owner = UUID.randomUUID();
        MarginLedger ledger = new MarginLedger();
        ledger.importAccount(owner, 100000);
        OptionMarket market = new OptionMarket(ledger);
        market.add(new OptionMarket.Contract("call", "stock", OptionPricing.Kind.CALL, 100, 336000, 1));
        OptionMarket.Quote quote = market.quote("call", 100, 0, 0, 1000, 0.8, 0.04, 0.01);
        try {
            market.buy(owner, "call", quote.id, 2, Long.MAX_VALUE, 0, 1000, 10);
            fail("Expired quote accepted");
        } catch (IllegalArgumentException expected) {
            assertEquals(100000, ledger.account(owner).cash);
        }
        market.buy(owner, "call", quote.id, 2, Long.MAX_VALUE, 0, 1, 10);
        long afterPremium = ledger.account(owner).cash;
        assertTrue(afterPremium < 100000);
        assertTrue(market.delta("stock", 100, 1, 0.8) > 0);
        market.settle("call", 336000, 100.01);
        assertEquals(afterPremium + 2, ledger.account(owner).cash);
        assertTrue(
            market.holdings(owner)
                .get(0)
                .settled());
        MarginLedger restored = MarginLedger.restore(ledger.checkpoint());
        OptionMarket copy = OptionMarket.restore(market.checkpoint(), restored);
        copy.settle("call", 336001, 100.01);
        assertEquals(afterPremium + 2, restored.account(owner).cash);
        assertEquals(0, copy.delta("stock", 100, 336001, 0.8), 0);
        assertTrue(
            copy.holdings(UUID.randomUUID())
                .isEmpty());
    }

    @Test
    public void acceptedPriceAndPositionLimitsAreCheckedBeforeCashChanges() {
        UUID owner = UUID.randomUUID();
        MarginLedger ledger = new MarginLedger();
        ledger.importAccount(owner, 100000);
        OptionMarket market = new OptionMarket(ledger);
        market.add(new OptionMarket.Contract("put", "stock", OptionPricing.Kind.PUT, 100, 336000, 1));
        OptionMarket.Quote quote = market.quote("put", 100, 0, 0, 1000, 0.8, 0.04, 0.01);
        try {
            market.buy(owner, "put", quote.id, 1, quote.unitCents - 1, 0, 1, 10);
            fail("Price protection ignored");
        } catch (IllegalArgumentException expected) {
            assertEquals(100000, ledger.account(owner).cash);
        }
        market.buy(owner, "put", quote.id, 1, quote.unitCents, 0, 1, 1);
        long cash = ledger.account(owner).cash;
        try {
            market.buy(owner, "put", quote.id, 1, quote.unitCents, 0, 1, 1);
            fail("Position limit ignored");
        } catch (IllegalArgumentException expected) {
            assertEquals(cash, ledger.account(owner).cash);
        }
        assertTrue(market.delta("stock", 100, 1, 0.8) < 0);
    }
}
