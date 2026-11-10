package com.yuelengm.nhkantan.market.order;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import com.google.gson.Gson;

/** Single-writer price/time-priority book. Prices are ticks, quantities are whole contracts. */
public final class OrderBook {

    public enum Side {

        BUY,
        SELL;

        public Side opposite() {
            return this == BUY ? SELL : BUY;
        }
    }

    public enum Status {
        NEW,
        PARTIALLY_FILLED,
        FILLED,
        CANCELLED,
        REJECTED,
        EXPIRED
    }

    /** Settlement must reserve before acceptance and atomically settle both sides before returning. */
    public interface Settlement {

        boolean reserve(Order order);

        /** May restrict a reduce-only order to its remaining closable position. */
        long executable(Order order);

        void fill(Order buyer, Order seller, long price, long quantity);

        void release(Order order);

        /** Restore a previously accepted reservation without treating it as new risk. */
        default boolean restore(Order order) {
            return reserve(order);
        }
    }

    /** Immutable public order view; the mutable remainder never leaves the book. */
    public static final class Order {

        public final long id;
        public final UUID owner;
        public final Side side;
        public final long price;
        public final long quantity;
        public final boolean market;
        public final boolean reduceOnly;
        public final long remaining;
        public final Status status;

        private Order(Entry entry) {
            id = entry.id;
            owner = entry.owner;
            side = entry.side;
            price = entry.price;
            quantity = entry.quantity;
            market = entry.market;
            reduceOnly = entry.reduceOnly;
            remaining = entry.remaining;
            status = entry.status;
        }
    }

    public static final class Fill {

        public final long sequence;
        public final long buyerOrder;
        public final long sellerOrder;
        public final long price;
        public final long quantity;

        private Fill(long sequence, Entry buyer, Entry seller, long price, long quantity) {
            this.sequence = sequence;
            buyerOrder = buyer.id;
            sellerOrder = seller.id;
            this.price = price;
            this.quantity = quantity;
        }
    }

    public static final class Result {

        public final Order order;
        public final List<Fill> fills;

        private Result(Entry entry, List<Fill> fills) {
            order = new Order(entry);
            this.fills = Collections.unmodifiableList(new ArrayList<>(fills));
        }
    }

    private static final class Entry {

        private final long id;
        private final UUID owner;
        private final Side side;
        private final long price;
        private final long quantity;
        private final boolean market;
        private final boolean reduceOnly;
        private long remaining;
        private Status status = Status.NEW;

        private Entry(long id, UUID owner, Side side, long price, long quantity, boolean market, boolean reduceOnly) {
            this.id = id;
            this.owner = owner;
            this.side = side;
            this.price = price;
            this.quantity = quantity;
            this.remaining = quantity;
            this.market = market;
            this.reduceOnly = reduceOnly;
        }
    }

    private final NavigableMap<Long, Deque<Entry>> bids = new TreeMap<>(Collections.reverseOrder());
    private final NavigableMap<Long, Deque<Entry>> asks = new TreeMap<>();
    private final Map<Long, Entry> active = new TreeMap<>();
    private final Settlement settlement;
    private long nextOrder = 1;
    private long nextFill = 1;

    private static final class Checkpoint {

        private int version;
        private long nextOrder;
        private long nextFill;
        private List<Order> orders;
    }

    public OrderBook(Settlement settlement) {
        this.settlement = Objects.requireNonNull(settlement);
    }

    public String checkpoint() {
        Checkpoint checkpoint = new Checkpoint();
        checkpoint.version = 1;
        checkpoint.nextOrder = nextOrder;
        checkpoint.nextFill = nextFill;
        checkpoint.orders = new ArrayList<>(orders(Side.BUY));
        checkpoint.orders.addAll(orders(Side.SELL));
        checkpoint.orders.sort((left, right) -> Long.compare(left.id, right.id));
        return new Gson().toJson(checkpoint);
    }

    /** Caller must discard the entire temporary ledger if any part of restoration fails. */
    public static OrderBook restore(String json, Settlement settlement) {
        Checkpoint checkpoint = new Gson().fromJson(json, Checkpoint.class);
        if (checkpoint == null || checkpoint.version != 1
            || checkpoint.nextOrder < 1
            || checkpoint.nextFill < 1
            || checkpoint.orders == null
            || checkpoint.orders.size() > 100000) {
            throw new IllegalArgumentException("Unsupported or invalid book checkpoint");
        }
        OrderBook book = new OrderBook(settlement);
        long previous = 0;
        for (Order order : checkpoint.orders) {
            if (order.id <= previous || order.id >= checkpoint.nextOrder
                || order.owner == null
                || order.side == null
                || order.market
                || order.price <= 0
                || order.quantity <= 0
                || order.remaining <= 0
                || order.remaining > order.quantity
                || (order.status != Status.NEW && order.status != Status.PARTIALLY_FILLED)) {
                throw new IllegalArgumentException("Invalid persisted order");
            }
            Entry entry = new Entry(
                order.id,
                order.owner,
                order.side,
                order.price,
                order.quantity,
                false,
                order.reduceOnly);
            entry.remaining = order.remaining;
            entry.status = order.status;
            if (!settlement.restore(new Order(entry))) throw new IllegalArgumentException("Invalid order collateral");
            book.levels(entry.side)
                .computeIfAbsent(entry.price, key -> new ArrayDeque<>())
                .addLast(entry);
            book.active.put(entry.id, entry);
            previous = entry.id;
        }
        if (!book.bids.isEmpty() && !book.asks.isEmpty() && book.bids.firstKey() >= book.asks.firstKey()) {
            throw new IllegalArgumentException("Persisted book is crossed");
        }
        book.nextOrder = checkpoint.nextOrder;
        book.nextFill = checkpoint.nextFill;
        return book;
    }

    /** For a market order price is its worst acceptable execution price, never an unbounded sentinel. */
    public Result submit(UUID owner, Side side, long price, long quantity, boolean market, boolean reduceOnly) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(side);
        if (price <= 0 || quantity <= 0) throw new IllegalArgumentException("Price and quantity must be positive");
        if (nextOrder == Long.MAX_VALUE || nextFill == Long.MAX_VALUE) {
            throw new IllegalStateException("Market sequence exhausted");
        }
        Entry incoming = new Entry(nextOrder++, owner, side, price, quantity, market, reduceOnly);
        List<Fill> fills = new ArrayList<>();
        if (!market && active.size() >= 100000) {
            incoming.status = Status.REJECTED;
            return new Result(incoming, fills);
        }
        if (!settlement.reserve(new Order(incoming))) {
            incoming.status = Status.REJECTED;
            return new Result(incoming, fills);
        }
        NavigableMap<Long, Deque<Entry>> opposite = levels(side.opposite());
        while (incoming.remaining > 0 && !opposite.isEmpty()) {
            long executionPrice = opposite.firstKey();
            if (side == Side.BUY ? executionPrice > price : executionPrice < price) break;
            Deque<Entry> queue = opposite.firstEntry()
                .getValue();
            Entry resting = queue.peekFirst();
            if (resting.owner.equals(owner)) break; // Cancel the aggressor remainder, never self-trade.
            long incomingAllowed = Math.min(incoming.remaining, settlement.executable(new Order(incoming)));
            long restingAllowed = Math.min(resting.remaining, settlement.executable(new Order(resting)));
            if (incomingAllowed <= 0) break;
            if (restingAllowed <= 0) {
                remove(resting, Status.CANCELLED);
                continue;
            }
            if (nextFill == Long.MAX_VALUE) throw new IllegalStateException("Fill sequence exhausted");
            long count = Math.min(incomingAllowed, restingAllowed);
            Entry buyer = side == Side.BUY ? incoming : resting;
            Entry seller = side == Side.SELL ? incoming : resting;
            settlement.fill(new Order(buyer), new Order(seller), executionPrice, count);
            incoming.remaining -= count;
            resting.remaining -= count;
            incoming.status = incoming.remaining == 0 ? Status.FILLED : Status.PARTIALLY_FILLED;
            resting.status = resting.remaining == 0 ? Status.FILLED : Status.PARTIALLY_FILLED;
            fills.add(new Fill(nextFill++, buyer, seller, executionPrice, count));
            if (resting.remaining == 0) remove(resting, Status.FILLED);
        }
        boolean selfBlocked = !opposite.isEmpty() && opposite.firstEntry()
            .getValue()
            .peekFirst().owner.equals(owner)
            && (side == Side.BUY ? opposite.firstKey() <= price : opposite.firstKey() >= price);
        if (incoming.remaining > 0 && !market && !selfBlocked && settlement.executable(new Order(incoming)) > 0) {
            levels(side).computeIfAbsent(price, key -> new ArrayDeque<>())
                .addLast(incoming);
            active.put(incoming.id, incoming);
        } else {
            if (incoming.remaining > 0) incoming.status = Status.CANCELLED;
            settlement.release(new Order(incoming));
        }
        return new Result(incoming, fills);
    }

    /** Ownership is checked here as well as at the server command boundary. */
    public boolean cancel(UUID owner, long id) {
        Entry entry = active.get(id);
        if (entry == null || !entry.owner.equals(owner)) return false;
        remove(entry, Status.CANCELLED);
        return true;
    }

    private void remove(Entry entry, Status status) {
        NavigableMap<Long, Deque<Entry>> book = levels(entry.side);
        Deque<Entry> queue = book.get(entry.price);
        queue.remove(entry);
        if (queue.isEmpty()) book.remove(entry.price);
        active.remove(entry.id);
        entry.status = status;
        settlement.release(new Order(entry));
    }

    private NavigableMap<Long, Deque<Entry>> levels(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    /** Defensive copies in price/time order, safe to publish as part of a snapshot. */
    public List<Order> orders(Side side) {
        List<Order> result = new ArrayList<>();
        for (Deque<Entry> queue : levels(side).values()) {
            for (Entry entry : queue) result.add(new Order(entry));
        }
        return Collections.unmodifiableList(result);
    }
}
