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

    @Test
    public void partialBuybackAndExerciseOnlySettleRemainingQuantityAfterRestart() {
        UUID owner = UUID.randomUUID();
        MarginLedger ledger = new MarginLedger();
        ledger.importAccount(owner, 1000000);
        OptionMarket market = new OptionMarket(ledger);
        market.add(new OptionMarket.Contract("call", "stock", OptionPricing.Kind.CALL, 100, 336000, 1));
        OptionMarket.Quote quote = market.quote("call", 110, 0, 0, 1000, 0.8, 0.04, 0.01);
        assertTrue(quote.bidUnitCents < quote.unitCents);
        OptionMarket.Holding held = market.buy(owner, "call", quote.id, 5, Long.MAX_VALUE, 0, 1, 10);
        long cash = ledger.account(owner).cash;
        double initialDelta = market.delta("stock", 110, 1, 0.8);
        market.close(owner, held.id, 2, quote.id, 2 * quote.bidUnitCents, 1, 2);
        assertEquals(initialDelta * 3 / 5, market.delta("stock", 110, 1, 0.8), 1E-9);
        market.exercise(owner, held.id, 1, 110, 1000, 1);
        assertEquals(
            2,
            market.holding(owner, held.id)
                .remainingQuantity());
        assertEquals(
            held.costCents * 2 / 5,
            market.holding(owner, held.id)
                .remainingCostCents());
        MarginLedger restored = MarginLedger.restore(ledger.checkpoint());
        OptionMarket copy = OptionMarket.restore(market.checkpoint(), restored);
        copy.settle("call", 336000, 120);
        long total = 2 * quote.bidUnitCents + 1000 + 4000;
        assertEquals(cash + total, restored.account(owner).cash);
        assertEquals(
            total,
            copy.holding(owner, held.id)
                .payoutCents());
        assertEquals(
            0,
            copy.holding(owner, held.id)
                .remainingQuantity());
        copy.settle("call", 336001, 120);
        assertEquals(cash + total, restored.account(owner).cash);
    }

    @Test
    public void exitsRejectForeignHoldingsStaleQuotesAndInsufficientPayoutBeforeCrediting() {
        UUID owner = UUID.randomUUID();
        MarginLedger ledger = new MarginLedger();
        ledger.importAccount(owner, 100000);
        OptionMarket market = new OptionMarket(ledger);
        market.add(new OptionMarket.Contract("put", "stock", OptionPricing.Kind.PUT, 100, 336000, 1));
        OptionMarket.Quote quote = market.quote("put", 90, 0, 0, 1000, 0.8, 0.04, 0.01);
        OptionMarket.Holding held = market.buy(owner, "put", quote.id, 3, Long.MAX_VALUE, 0, 1, 10);
        long cash = ledger.account(owner).cash;
        Runnable[] invalid = { () -> market.close(UUID.randomUUID(), held.id, 1, quote.id, 0, 1, 1),
            () -> market.close(owner, held.id, 1, quote.id, 0, 1, 1000),
            () -> market.close(owner, held.id, 1, quote.id, quote.bidUnitCents + 1, 1, 1),
            () -> market.exercise(owner, held.id, 1, 110, 0, 1), () -> market.exercise(owner, held.id, 4, 90, 0, 1),
            () -> market.exercise(owner, held.id, 1, 90, 1001, 1) };
        for (Runnable operation : invalid) {
            try {
                operation.run();
                fail("Invalid exit accepted");
            } catch (IllegalArgumentException expected) {
                assertEquals(cash, ledger.account(owner).cash);
                assertEquals(
                    3,
                    market.holding(owner, held.id)
                        .remainingQuantity());
            }
        }
        market.exercise(owner, held.id, 3, 90, 3000, 1);
        assertEquals(cash + 3000, ledger.account(owner).cash);
        assertTrue(
            market.holding(owner, held.id)
                .settled());
    }
}
