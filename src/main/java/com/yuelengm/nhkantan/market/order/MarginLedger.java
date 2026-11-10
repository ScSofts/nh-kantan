package com.yuelengm.nhkantan.market.order;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;

/** Single-writer fixed-point cash ledger shared by every instrument's settlement adapter. */
public final class MarginLedger {

    public static final class Position {

        public final long quantity;
        /** Absolute remaining entry cost in cents; retained remainders prevent partial-close rounding drift. */
        public final long cost;

        private Position(long quantity, long cost) {
            this.quantity = quantity;
            this.cost = cost;
        }
    }

    public static final class AccountView {

        public final long cash;
        public final long realized;
        public final long fees;
        public final long frozen;
        public final long equity;
        public final long usedMargin;
        public final long available;
        public final long nextTransfer;
        public final long pendingCredit;

        private AccountView(Account account, long equity, long usedMargin) {
            cash = account.cash;
            realized = account.realized;
            fees = account.fees;
            frozen = Math.addExact(account.frozen, account.transferFrozen);
            this.equity = equity;
            this.usedMargin = usedMargin;
            available = Math.subtractExact(Math.subtractExact(equity, usedMargin), frozen);
            nextTransfer = Math.addExact(account.transferId, 1);
            pendingCredit = account.transferFrozen;
        }
    }

    private static final class Account {

        private long cash;
        private long realized;
        private long fees;
        private long frozen;
        private long transferFrozen;
        private long transferId;
        private long transferDelta;
        private long institutionalCapital;
        private long institutionalPositionLimit;
        private int transferState; // 0: none, 1: prepared, 2: completed, 3: aborted
        private final Map<String, Position> positions = new HashMap<>();
    }

    private static final class Reservation {

        private final Account account;
        private final long perUnit;
        private final OrderBook.Side side;
        private long quantity;

        private Reservation(Account account, long perUnit, long quantity, OrderBook.Side side) {
            this.side = side;
            this.account = account;
            this.perUnit = perUnit;
            this.quantity = quantity;
        }
    }

    private final Map<UUID, Account> accounts = new HashMap<>();
    private final Map<String, Instrument> instruments = new HashMap<>();

    public java.util.Set<UUID> owners() {
        return new java.util.HashSet<>(accounts.keySet());
    }

    public boolean hasAccount(UUID owner) {
        return accounts.containsKey(owner);
    }

    /** Server-owned institution setup. Only the capital increase is granted once, never reset after losses. */
    public void provisionInstitution(UUID owner, long capital, long legacyCapital, long positionLimit) {
        if (capital < 1 || legacyCapital < 1 || positionLimit < 1 || positionLimit > 1000000)
            throw new IllegalArgumentException("Invalid institution budget");
        if (!hasAccount(owner)) {
            importAccount(owner, capital);
            Account account = requireAccount(owner);
            account.institutionalCapital = capital;
            account.institutionalPositionLimit = positionLimit;
            return;
        }
        Account account = requireAccount(owner);
        for (Position position : account.positions.values()) if (Math.abs(position.quantity) > positionLimit)
            throw new IllegalArgumentException("Cannot lower institution limit beneath an existing position");
        long previous = account.institutionalCapital == 0 ? legacyCapital : account.institutionalCapital;
        long increase = Math.max(0, capital - previous);
        long cash = Math.addExact(account.cash, increase);
        account.cash = cash;
        account.institutionalCapital = Math.max(previous, capital);
        account.institutionalPositionLimit = positionLimit;
    }

    /** Market-thread cash payment (e.g. option premium), limited by both actual cash and free collateral. */
    public boolean charge(UUID owner, long cents) {
        if (cents < 0) throw new IllegalArgumentException("Negative charge");
        Account account = requireAccount(owner);
        if (account.cash < cents || account(owner).available < cents) return false;
        account.cash = Math.subtractExact(account.cash, cents);
        return true;
    }

    public void credit(UUID owner, long cents) {
        if (cents < 0) throw new IllegalArgumentException("Negative credit");
        Account account = requireAccount(owner);
        account.cash = Math.addExact(account.cash, cents);
    }

    /** Reserve a durable two-phase inventory transfer without exposing unbacked credit as available funds. */
    public boolean prepareTransfer(UUID owner, long transferId, long delta) {
        return prepareTransfer(owner, transferId, delta, false);
    }

    public boolean prepareTransfer(UUID owner, long transferId, long delta, boolean repayment) {
        Account account = requireAccount(owner);
        if (transferId < 1 || delta == 0 || delta == Long.MIN_VALUE)
            throw new IllegalArgumentException("Invalid cash transfer");
        if (transferId == account.transferId) {
            if (delta != account.transferDelta) throw new IllegalArgumentException("Conflicting transfer retry");
            return account.transferState != 3;
        }
        if (transferId < account.transferId || account.transferState == 1) {
            throw new IllegalArgumentException("Another transfer is pending or this transfer is stale");
        }
        if (repayment && (delta <= 0 || account.cash >= 0 || account.cash + delta > 0)) return false;
        if (!repayment && delta > 0 && account.cash < 0) return false;
        if (delta < 0 && (account.cash < -delta || account(owner).available < -delta)) return false;
        long cash = Math.addExact(account.cash, delta);
        long frozen = delta > 0 ? delta : 0;
        account.cash = cash;
        account.transferFrozen = frozen;
        account.transferId = transferId;
        account.transferDelta = delta;
        account.transferState = 1;
        return true;
    }

    public void completeTransfer(UUID owner, long transferId) {
        Account account = requireAccount(owner);
        if (account.transferId != transferId || account.transferState == 3) {
            throw new IllegalArgumentException("Transfer cannot be completed");
        }
        account.transferFrozen = 0;
        account.transferState = 2;
    }

    public void abortTransfer(UUID owner, long transferId) {
        Account account = requireAccount(owner);
        if (account.transferId != transferId || account.transferState == 2) {
            throw new IllegalArgumentException("Transfer cannot be aborted");
        }
        if (account.transferState == 3) return;
        account.cash = Math.subtractExact(account.cash, account.transferDelta);
        account.transferFrozen = 0;
        account.transferState = 3;
    }

    private static final class Checkpoint {

        private int version;
        private Map<UUID, Account> accounts;
        private Map<String, Rules> instruments;
    }

    private static final class Rules {

        private long tickValue;
        private int marginBps;
        private int feePpm;
        private long maxPosition;
        private long maximumPrice;
        private long lastPrice;
    }

    /** Capture on the writer thread together with every book checkpoint at the same save barrier. */
    public String checkpoint() {
        Checkpoint checkpoint = new Checkpoint();
        checkpoint.version = 1;
        checkpoint.accounts = accounts;
        checkpoint.instruments = new HashMap<>();
        for (Instrument instrument : instruments.values()) {
            Rules rules = new Rules();
            rules.tickValue = instrument.tickValue;
            rules.marginBps = instrument.marginBps;
            rules.feePpm = instrument.feePpm;
            rules.maxPosition = instrument.maxPosition;
            rules.maximumPrice = instrument.maximumPrice;
            rules.lastPrice = instrument.lastPrice;
            checkpoint.instruments.put(instrument.id, rules);
        }
        return new Gson().toJson(checkpoint);
    }

    public static MarginLedger restore(String json) {
        Checkpoint checkpoint = new Gson().fromJson(json, Checkpoint.class);
        if (checkpoint == null || checkpoint.version != 1
            || checkpoint.accounts == null
            || checkpoint.instruments == null
            || checkpoint.accounts.size() > 100000
            || checkpoint.instruments.size() > 1000) {
            throw new IllegalArgumentException("Unsupported ledger checkpoint");
        }
        MarginLedger ledger = new MarginLedger();
        for (Map.Entry<String, Rules> entry : checkpoint.instruments.entrySet()) {
            Rules rules = entry.getValue();
            Instrument instrument = ledger.instrument(
                entry.getKey(),
                rules.tickValue,
                rules.marginBps,
                rules.feePpm,
                rules.maxPosition,
                rules.maximumPrice);
            if (rules.lastPrice < 0 || rules.lastPrice > rules.maximumPrice) {
                throw new IllegalArgumentException("Invalid persisted price");
            }
            instrument.lastPrice = rules.lastPrice;
        }
        for (Map.Entry<UUID, Account> entry : checkpoint.accounts.entrySet()) {
            Account account = entry.getValue();
            if (entry.getKey() == null || account == null
                || account.positions == null
                || account.institutionalCapital < 0
                || account.institutionalPositionLimit < 0
                || account.institutionalPositionLimit > 1000000
                || account.fees < 0
                || account.frozen < 0
                || account.transferFrozen < 0
                || account.transferState < 0
                || account.transferState > 3
                || (account.transferState == 1 && account.transferId < 1)) {
                throw new IllegalArgumentException("Invalid persisted account");
            }
            for (Map.Entry<String, Position> held : account.positions.entrySet()) {
                Instrument instrument = ledger.instruments.get(held.getKey());
                Position position = held.getValue();
                if (instrument == null || position == null
                    || position.quantity == 0
                    || position.quantity == Long.MIN_VALUE
                    || Math.abs(position.quantity)
                        > (account.institutionalPositionLimit > 0 ? account.institutionalPositionLimit
                            : instrument.maxPosition)
                    || position.cost < 0
                    || instrument.lastPrice == 0) {
                    throw new IllegalArgumentException("Invalid persisted position");
                }
            }
            // Rebuild collateral exclusively from accepted active orders; never freeze it twice on reload.
            account.frozen = 0;
            ledger.accounts.put(entry.getKey(), account);
            ledger.account(entry.getKey()); // Check derived arithmetic before exposing restored state.
        }
        return ledger;
    }

    public Instrument instrument(String id) {
        Instrument instrument = instruments.get(id);
        if (instrument == null) throw new IllegalArgumentException("Unknown instrument");
        return instrument;
    }

    /** Initial import only; duplicate initialization is an error rather than a second credit. */
    public void importAccount(UUID owner, long netEquity) {
        if (accounts.containsKey(owner)) throw new IllegalStateException("Account already imported");
        Account account = new Account();
        account.cash = netEquity;
        accounts.put(owner, account);
    }

    public AccountView account(UUID owner) {
        Account account = requireAccount(owner);
        return new AccountView(account, equity(account), usedMargin(account));
    }

    public Position position(UUID owner, String instrument) {
        Position position = requireAccount(owner).positions.get(instrument);
        return position == null ? new Position(0, 0) : position;
    }

    private Account requireAccount(UUID owner) {
        Account account = accounts.get(owner);
        if (account == null) throw new IllegalArgumentException("Unknown account");
        return account;
    }

    public Instrument instrument(String id, long tickValueCents, int initialMarginBps, int feePpm, long maxPosition,
        long maximumPrice) {
        if (instruments.containsKey(id) || tickValueCents <= 0
            || initialMarginBps < 1
            || initialMarginBps > 10000
            || feePpm < 0
            || feePpm > 1000000
            || maxPosition < 1
            || maxPosition > 1000000
            || maximumPrice < 1) {
            throw new IllegalArgumentException("Invalid or duplicate instrument rules");
        }
        Instrument result = new Instrument(id, tickValueCents, initialMarginBps, feePpm, maxPosition, maximumPrice);
        instruments.put(id, result);
        return result;
    }

    /** Apply server margin settings before restoring any order reservations. */
    public void configureMargin(String id, int marginBps) {
        Instrument instrument = instruments.get(id);
        if (instrument == null || marginBps < 10 || marginBps > 10000 || !instrument.reservations.isEmpty())
            throw new IllegalArgumentException("Cannot reconfigure margin with active reservations");
        instrument.marginBps = marginBps;
    }

    public final class Instrument implements OrderBook.Settlement {

        private final String id;
        private final long tickValue;
        private int marginBps;
        private final int feePpm;
        private final long maxPosition;
        private final long maximumPrice;
        private long lastPrice;
        private final Map<Long, Reservation> reservations = new HashMap<>();

        private Instrument(String id, long tickValue, int marginBps, int feePpm, long maxPosition, long maximumPrice) {
            this.id = id;
            this.tickValue = tickValue;
            this.marginBps = marginBps;
            this.feePpm = feePpm;
            this.maxPosition = maxPosition;
            this.maximumPrice = maximumPrice;
        }

        public long positionLimit() {
            return maxPosition;
        }

        public long positionLimit(UUID owner) {
            Account account = requireAccount(owner);
            return account.institutionalPositionLimit > 0 ? account.institutionalPositionLimit : maxPosition;
        }

        @Override
        public boolean reserve(OrderBook.Order order) {
            Account account = accounts.get(order.owner);
            if (account == null || order.quantity > positionLimit(order.owner) || order.price > maximumPrice)
                return false;
            if (order.reduceOnly && executable(order) < order.quantity) return false;
            try {
                if (!order.reduceOnly) {
                    long exposure = position(order.owner, id).quantity;
                    long sign = order.side == OrderBook.Side.BUY ? 1 : -1;
                    for (Reservation reservation : reservations.values()) {
                        if (reservation.account == account && reservation.side == order.side)
                            exposure = Math.addExact(exposure, Math.multiplyExact(sign, reservation.quantity));
                    }
                    if (Math.abs(Math.addExact(exposure, Math.multiplyExact(sign, order.quantity)))
                        > positionLimit(order.owner) || account.cash < 0) return false;
                }
                // Both sides reserve worst-case notional, so a resting order cannot spend someone else's collateral.
                long reservePrice = order.side == OrderBook.Side.SELL ? maximumPrice : order.price;
                long unitValue = Math.multiplyExact(reservePrice, tickValue);
                long perUnit = order.reduceOnly ? 0
                    : Math.addExact(ratioCeil(unitValue, marginBps), ratioCeil(unitValue, feePpm, 1000000));
                long frozen = Math.multiplyExact(perUnit, order.quantity);
                long available = Math.subtractExact(
                    Math.subtractExact(equity(account), usedMargin(account)),
                    Math.addExact(account.frozen, account.transferFrozen));
                if (frozen > available && frozen != 0) return false;
                account.frozen = Math.addExact(account.frozen, frozen);
                reservations.put(order.id, new Reservation(account, perUnit, order.quantity, order.side));
                return true;
            } catch (ArithmeticException e) {
                return false;
            }
        }

        @Override
        public long executable(OrderBook.Order order) {
            if (!order.reduceOnly) return order.remaining;
            long quantity = position(order.owner, id).quantity;
            return order.side == OrderBook.Side.SELL && quantity > 0 ? quantity
                : order.side == OrderBook.Side.BUY && quantity < 0 ? -quantity : 0;
        }

        @Override
        public boolean restore(OrderBook.Order order) {
            Account account = accounts.get(order.owner);
            if (account == null || order.price > maximumPrice
                || order.remaining > positionLimit(order.owner)
                || reservations.containsKey(order.id)) return false;
            long reservePrice = order.side == OrderBook.Side.SELL ? maximumPrice : order.price;
            long value = Math.multiplyExact(reservePrice, tickValue);
            long perUnit = order.reduceOnly ? 0
                : Math.addExact(ratioCeil(value, marginBps), ratioCeil(value, feePpm, 1000000));
            long updated = Math.addExact(account.frozen, Math.multiplyExact(perUnit, order.remaining));
            reservations.put(order.id, new Reservation(account, perUnit, order.remaining, order.side));
            account.frozen = updated;
            return true;
        }

        @Override
        public void fill(OrderBook.Order buyer, OrderBook.Order seller, long price, long quantity) {
            long value = Math.multiplyExact(Math.multiplyExact(price, tickValue), quantity);
            long fee = ratioCeil(value, feePpm, 1000000);
            Change buy = change(requireAccount(buyer.owner), quantity, value, fee);
            Change sell = change(requireAccount(seller.owner), -quantity, value, fee);
            // Every checked calculation completes before either account is mutated.
            buy.commit();
            sell.commit();
            consume(buyer.id, quantity);
            consume(seller.id, quantity);
            lastPrice = price;
        }

        private Change change(Account account, long delta, long value, long fee) {
            Position old = account.positions.get(id);
            if (old == null) old = new Position(0, 0);
            long quantity = Math.addExact(old.quantity, delta);
            long cost;
            long pnl = 0;
            if (old.quantity == 0 || Long.signum(old.quantity) == Long.signum(delta)) {
                cost = Math.addExact(old.cost, value);
            } else {
                long closed = Math.min(Math.abs(old.quantity), Math.abs(delta));
                long oldCost = proportion(old.cost, closed, Math.abs(old.quantity));
                long closeValue = proportion(value, closed, Math.abs(delta));
                pnl = Math.multiplyExact(Long.signum(old.quantity), Math.subtractExact(closeValue, oldCost));
                cost = Math.abs(delta) > Math.abs(old.quantity) ? value - closeValue : old.cost - oldCost;
            }
            if (quantity == 0) cost = 0;
            return new Change(account, new Position(quantity, cost), pnl, fee);
        }

        private final class Change {

            private final Account account;
            private final Position position;
            private final long cash;
            private final long realized;
            private final long fees;

            private Change(Account account, Position position, long pnl, long fee) {
                this.account = account;
                this.position = position;
                cash = Math.subtractExact(Math.addExact(account.cash, pnl), fee);
                realized = Math.addExact(account.realized, pnl);
                fees = Math.addExact(account.fees, fee);
            }

            private void commit() {
                account.cash = cash;
                account.realized = realized;
                account.fees = fees;
                if (position.quantity == 0) account.positions.remove(id);
                else account.positions.put(id, position);
            }
        }

        private void consume(long orderId, long quantity) {
            Reservation reservation = reservations.get(orderId);
            reservation.quantity -= quantity;
            reservation.account.frozen -= reservation.perUnit * quantity;
        }

        @Override
        public void release(OrderBook.Order order) {
            Reservation reservation = reservations.remove(order.id);
            if (reservation != null) reservation.account.frozen -= reservation.perUnit * reservation.quantity;
        }
    }

    private long equity(Account account) {
        long result = account.cash;
        for (Map.Entry<String, Position> entry : account.positions.entrySet()) {
            Instrument instrument = instruments.get(entry.getKey());
            Position position = entry.getValue();
            if (instrument.lastPrice <= 0) throw new IllegalStateException("Position has no market mark");
            long marked = Math.multiplyExact(
                Math.multiplyExact(instrument.lastPrice, instrument.tickValue),
                Math.abs(position.quantity));
            long pnl = Math.multiplyExact(Long.signum(position.quantity), Math.subtractExact(marked, position.cost));
            result = Math.addExact(result, pnl);
        }
        return result;
    }

    private long usedMargin(Account account) {
        long margin = 0;
        for (Map.Entry<String, Position> entry : account.positions.entrySet()) {
            margin = Math.addExact(margin, ratioCeil(entry.getValue().cost, instruments.get(entry.getKey()).marginBps));
        }
        return margin;
    }

    private static long ratioCeil(long value, int bps) {
        return ratioCeil(value, bps, 10000);
    }

    private static long ratioCeil(long value, int rate, int denominator) {
        BigInteger numerator = BigInteger.valueOf(value)
            .multiply(BigInteger.valueOf(rate));
        return numerator.add(BigInteger.valueOf(denominator - 1))
            .divide(BigInteger.valueOf(denominator))
            .longValueExact();
    }

    private static long proportion(long value, long count, long total) {
        return BigInteger.valueOf(value)
            .multiply(BigInteger.valueOf(count))
            .divide(BigInteger.valueOf(total))
            .longValueExact();
    }
}
