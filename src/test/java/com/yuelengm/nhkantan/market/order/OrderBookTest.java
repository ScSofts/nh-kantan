package com.yuelengm.nhkantan.market.order;

import static org.junit.Assert.*;

import java.util.UUID;

import org.junit.Test;

public class OrderBookTest {

    private final UUID buyer = UUID.randomUUID();
    private final UUID seller = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    private OrderBook book(MarginLedger ledger) {
        ledger.importAccount(buyer, 100000000);
        ledger.importAccount(seller, 100000000);
        ledger.importAccount(other, 100000000);
        return new OrderBook(ledger.instrument("test", 25, 1000, 0, 10000, 30000));
    }

    @Test
    public void marketOrderWalksFourLevelsAndCancelsRemainder() {
        MarginLedger ledger = new MarginLedger();
        OrderBook book = book(ledger);
        book.submit(seller, OrderBook.Side.SELL, 20001, 10, false, false);
        book.submit(seller, OrderBook.Side.SELL, 20002, 20, false, false);
        book.submit(seller, OrderBook.Side.SELL, 20003, 30, false, false);
        book.submit(seller, OrderBook.Side.SELL, 20004, 50, false, false);
        OrderBook.Result result = book.submit(buyer, OrderBook.Side.BUY, 20004, 80, true, false);
        assertEquals(4, result.fills.size());
        assertEquals(20004, result.fills.get(3).price);
        assertEquals(20, result.fills.get(3).quantity);
        assertEquals(
            30,
            book.orders(OrderBook.Side.SELL)
                .get(0).remaining);
        assertEquals(80, ledger.position(buyer, "test").quantity);
        assertEquals(-80, ledger.position(seller, "test").quantity);
        assertEquals(0, ledger.account(buyer).frozen);
        assertEquals(
            OrderBook.Status.CANCELLED,
            book.submit(other, OrderBook.Side.BUY, 20004, 100, true, false).order.status);
        assertTrue(
            book.orders(OrderBook.Side.BUY)
                .isEmpty());
    }

    @Test
    public void fifoOwnershipAndSelfTradeAreEnforced() {
        MarginLedger ledger = new MarginLedger();
        OrderBook book = book(ledger);
        long first = book.submit(seller, OrderBook.Side.SELL, 100, 2, false, false).order.id;
        long second = book.submit(other, OrderBook.Side.SELL, 100, 2, false, false).order.id;
        assertFalse(book.cancel(buyer, first));
        OrderBook.Result result = book.submit(buyer, OrderBook.Side.BUY, 100, 3, true, false);
        assertEquals(first, result.fills.get(0).sellerOrder);
        assertEquals(second, result.fills.get(1).sellerOrder);
        OrderBook.Result self = book.submit(other, OrderBook.Side.BUY, 100, 1, false, false);
        assertEquals(OrderBook.Status.CANCELLED, self.order.status);
        assertTrue(self.fills.isEmpty());
        assertTrue(book.cancel(other, second));
        assertEquals(0, ledger.account(other).frozen);
    }

    @Test
    public void closingRealizesProfitAndCannotReverseReduceOnly() {
        MarginLedger ledger = new MarginLedger();
        OrderBook book = book(ledger);
        book.submit(seller, OrderBook.Side.SELL, 100, 4, false, false);
        book.submit(buyer, OrderBook.Side.BUY, 100, 4, true, false);
        book.submit(other, OrderBook.Side.BUY, 120, 10, false, false);
        OrderBook.Result excessive = book.submit(buyer, OrderBook.Side.SELL, 120, 5, true, true);
        assertEquals(OrderBook.Status.REJECTED, excessive.order.status);
        book.submit(buyer, OrderBook.Side.SELL, 120, 2, true, true);
        assertEquals(1000, ledger.account(buyer).realized);
        assertEquals(2, ledger.position(buyer, "test").quantity);
        book.submit(buyer, OrderBook.Side.SELL, 120, 2, true, true);
        assertEquals(2000, ledger.account(buyer).realized);
        assertEquals(0, ledger.position(buyer, "test").quantity);
        assertEquals(0, ledger.position(buyer, "test").cost);
    }

    @Test
    public void pendingInventoryCreditIsFrozenAndRecoveryCanCommitOrAbortExactlyOnce() {
        UUID owner = UUID.randomUUID();
        MarginLedger ledger = new MarginLedger();
        ledger.importAccount(owner, 1000);
        assertTrue(ledger.prepareTransfer(owner, 1, 500));
        assertEquals(1500, ledger.account(owner).cash);
        assertEquals(1000, ledger.account(owner).available);
        assertFalse(ledger.charge(owner, 1200));
        MarginLedger copy = MarginLedger.restore(ledger.checkpoint());
        assertEquals(1000, copy.account(owner).available);
        assertTrue(copy.prepareTransfer(owner, 1, 500));
        assertEquals(1500, copy.account(owner).cash);
        copy.completeTransfer(owner, 1);
        copy.completeTransfer(owner, 1);
        assertEquals(1500, copy.account(owner).available);
        assertTrue(copy.prepareTransfer(owner, 2, -300));
        assertEquals(1200, copy.account(owner).cash);
        copy.abortTransfer(owner, 2);
        copy.abortTransfer(owner, 2);
        assertEquals(1500, copy.account(owner).cash);
    }

    @Test
    public void checkpointRestoresPartialOrdersCollateralAndSequences() {
        MarginLedger ledger = new MarginLedger();
        OrderBook book = book(ledger);
        long sellId = book.submit(seller, OrderBook.Side.SELL, 100, 10, false, false).order.id;
        book.submit(buyer, OrderBook.Side.BUY, 100, 3, true, false);
        long frozen = ledger.account(seller).frozen;
        MarginLedger restored = MarginLedger.restore(ledger.checkpoint());
        OrderBook restoredBook = OrderBook.restore(book.checkpoint(), restored.instrument("test"));
        assertEquals(frozen, restored.account(seller).frozen);
        assertEquals(
            7,
            restoredBook.orders(OrderBook.Side.SELL)
                .get(0).remaining);
        assertEquals(3, restored.position(buyer, "test").quantity);
        OrderBook.Result fill = restoredBook.submit(other, OrderBook.Side.BUY, 100, 7, true, false);
        assertEquals(sellId, fill.fills.get(0).sellerOrder);
        assertEquals(2, fill.fills.get(0).sequence);
        assertEquals(0, restored.account(seller).frozen);
        assertEquals(-10, restored.position(seller, "test").quantity);
    }

    @Test
    public void cancelReleasesFundsAndOverflowDoesNotChangeCash() {
        MarginLedger ledger = new MarginLedger();
        OrderBook book = book(ledger);
        long id = book.submit(buyer, OrderBook.Side.BUY, 100, 10, false, false).order.id;
        assertEquals(2500, ledger.account(buyer).frozen);
        assertTrue(book.cancel(buyer, id));
        assertEquals(0, ledger.account(buyer).frozen);
        assertFalse(book.cancel(buyer, id));
        assertEquals(
            OrderBook.Status.REJECTED,
            book.submit(buyer, OrderBook.Side.BUY, Long.MAX_VALUE, 2, false, false).order.status);
        assertEquals(100000000, ledger.account(buyer).cash);
        assertEquals(0, ledger.account(buyer).frozen);
    }
}
