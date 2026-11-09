package com.yuelengm.nhkantan.market.order;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import com.google.gson.Gson;

/** Pure market-thread instrument: bots, players and option hedges all enter the same book. */
public final class SimulatedInstrument {

    public enum Strategy {
        MARKET_MAKER,
        TREND,
        MEAN_REVERSION,
        NOISE,
        WHALE
    }

    public static final class Settings {

        public long lowerPrice = 1;
        public long upperPrice = 100000;
        public long initialPrice = 10000;
        public long botCashCents = 100000000;
        public long institutionalCapitalCents = 10000000000L;
        public long institutionalPositionLimit = 100000;
        public long fairWindowTicks = 1200;
        public int depth = 8;
        public long baseOrderSize = 300;
        public double volatility = 6;
        public double impact = 0.02;
        public double jitter = 0.4;
        public int regimeMinSteps = 300;
        public int regimeMaxSteps = 750;
        public int trendMoveBps = 500;
        public int breakoutMoveBps = 800;
        public int historyCapacity = 100;
        public long candleTicks = 40;

        Settings copy() {
            if (lowerPrice < 1 || upperPrice <= lowerPrice
                || initialPrice < lowerPrice
                || initialPrice > upperPrice
                || botCashCents < 1
                || institutionalCapitalCents < 1
                || institutionalPositionLimit < 1
                || institutionalPositionLimit > 1000000
                || fairWindowTicks < 20
                || fairWindowTicks > 72000
                || depth < 1
                || depth > 100
                || baseOrderSize < 1
                || baseOrderSize > 1000000
                || !Double.isFinite(volatility)
                || volatility < 0
                || !Double.isFinite(impact)
                || impact < 0
                || !Double.isFinite(jitter)
                || jitter < 0
                || jitter > 1
                || regimeMinSteps < 20
                || regimeMaxSteps < regimeMinSteps
                || regimeMaxSteps > 36000
                || trendMoveBps < 0
                || trendMoveBps > 5000
                || breakoutMoveBps < 0
                || breakoutMoveBps > 10000) {
                throw new IllegalArgumentException("Invalid instrument simulation settings");
            }
            Settings copy = new Settings();
            copy.lowerPrice = lowerPrice;
            copy.upperPrice = upperPrice;
            copy.initialPrice = initialPrice;
            copy.botCashCents = botCashCents;
            copy.institutionalCapitalCents = institutionalCapitalCents;
            copy.institutionalPositionLimit = institutionalPositionLimit;
            copy.fairWindowTicks = fairWindowTicks;
            copy.depth = depth;
            copy.baseOrderSize = baseOrderSize;
            copy.volatility = volatility;
            copy.impact = impact;
            copy.jitter = jitter;
            copy.regimeMinSteps = regimeMinSteps;
            copy.regimeMaxSteps = regimeMaxSteps;
            copy.trendMoveBps = trendMoveBps;
            copy.breakoutMoveBps = breakoutMoveBps;
            copy.historyCapacity = historyCapacity;
            copy.candleTicks = candleTicks;
            return copy;
        }
    }

    public static final class Snapshot {

        public final List<Level> bids;
        public final List<Level> asks;
        public final List<TradeHistory.Bar> candles;
        public final long lastPrice;
        public final long volume;
        public final int capacity;
        public final long bucketTicks;
        public final long lowerPrice;
        public final long upperPrice;

        private Snapshot(SimulatedInstrument instrument) {
            bids = instrument.depth(OrderBook.Side.BUY);
            asks = instrument.depth(OrderBook.Side.SELL);
            candles = instrument.history.snapshot();
            lastPrice = instrument.lastPrice;
            volume = instrument.volume;
            capacity = instrument.settings.historyCapacity;
            bucketTicks = instrument.settings.candleTicks;
            lowerPrice = instrument.settings.lowerPrice;
            upperPrice = instrument.settings.upperPrice;
        }
    }

    /** Public aggregated depth deliberately omits account identities and private order IDs. */
    public static final class Level {

        public final long price;
        public final long quantity;

        private Level(long price, long quantity) {
            this.price = price;
            this.quantity = quantity;
        }
    }

    private List<Level> depth(OrderBook.Side side) {
        Map<Long, Long> amounts = new LinkedHashMap<>();
        for (OrderBook.Order order : book.orders(side)) {
            if (!amounts.containsKey(order.price) && amounts.size() >= settings.depth) break;
            amounts.put(order.price, Math.addExact(amounts.getOrDefault(order.price, 0L), order.remaining));
        }
        List<Level> result = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : amounts.entrySet()) result.add(new Level(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableList(result);
    }

    public List<OrderBook.Order> orders(UUID owner) {
        List<OrderBook.Order> result = new ArrayList<>();
        for (OrderBook.Side side : OrderBook.Side.values()) {
            for (OrderBook.Order order : book.orders(side)) if (order.owner.equals(owner)) result.add(order);
        }
        return Collections.unmodifiableList(result);
    }

    private final String id;
    private final Settings settings;
    private final MarginLedger ledger;
    private final OrderBook book;
    private final TradeHistory history;
    private MarketDynamics dynamics;
    private double fairPrice;
    private double flow;
    private long lastPrice;
    private long volume;
    private long marketTick;
    private final Map<UUID, Protection> protections = new LinkedHashMap<>();
    private boolean checkingProtections;

    public static final class Protection {

        public final long stopPrice;
        public final long takePrice;
        public final long quantity;
        public final int direction;
        private final long initialPosition;
        private long takeOrder;
        private boolean triggered;

        private Protection(long stopPrice, long takePrice, long quantity, long held) {
            this.stopPrice = stopPrice;
            this.takePrice = takePrice;
            this.quantity = quantity;
            this.direction = Long.signum(held);
            initialPosition = Math.abs(held);
        }
    }

    /** Detached public fields only: the actor keeps ownership of mutable OCO state. */
    public Protection protection(UUID owner) {
        Protection value = protections.get(owner);
        return value == null ? null
            : new Protection(value.stopPrice, value.takePrice, value.quantity, value.direction * value.initialPosition);
    }

    /** Reapply restart-bound behavior without altering saved contracts, balances or price bounds. */
    public void configureBehavior(Settings configured) {
        Settings checked = configured.copy();
        settings.institutionalCapitalCents = checked.institutionalCapitalCents;
        settings.institutionalPositionLimit = checked.institutionalPositionLimit;
        settings.fairWindowTicks = checked.fairWindowTicks;
        settings.volatility = checked.volatility;
        settings.impact = checked.impact;
        settings.jitter = checked.jitter;
        settings.depth = checked.depth;
        settings.baseOrderSize = checked.baseOrderSize;
        settings.regimeMinSteps = checked.regimeMinSteps;
        settings.regimeMaxSteps = checked.regimeMaxSteps;
        settings.trendMoveBps = checked.trendMoveBps;
        settings.breakoutMoveBps = checked.breakoutMoveBps;
    }

    public void clearProtection(UUID owner) {
        Protection previous = protections.remove(owner);
        if (previous != null && previous.takeOrder > 0) book.cancel(owner, previous.takeOrder);
    }

    public void protect(UUID owner, long quantity, long stopPrice, long takePrice, long gameTick) {
        long held = ledger.position(owner, id).quantity;
        if (quantity < 1 || quantity > Math.abs(held)
            || stopPrice < 0
            || takePrice < 0
            || stopPrice > settings.upperPrice
            || takePrice > settings.upperPrice
            || (stopPrice > 0 && stopPrice < settings.lowerPrice)
            || (takePrice > 0 && takePrice < settings.lowerPrice)
            || (stopPrice == 0 && takePrice == 0)) throw new IllegalArgumentException("Invalid position protection");
        Protection prior = protections.remove(owner);
        if (prior != null && prior.takeOrder > 0) book.cancel(owner, prior.takeOrder);
        Protection protection = new Protection(stopPrice, takePrice, quantity, held);
        protections.put(owner, protection);
        if (takePrice > 0) {
            OrderBook.Result take = submit(
                owner,
                held > 0 ? OrderBook.Side.SELL : OrderBook.Side.BUY,
                takePrice,
                quantity,
                false,
                true,
                gameTick);
            protection.takeOrder = take.order.id;
        }
        checkProtections(gameTick);
    }

    public void checkProtections(long gameTick) {
        if (checkingProtections) return;
        checkingProtections = true;
        try {
            for (Map.Entry<UUID, Protection> entry : new ArrayList<>(protections.entrySet())) {
                UUID owner = entry.getKey();
                Protection protection = entry.getValue();
                long position = ledger.position(owner, id).quantity;
                long alreadyClosed = Math.max(0, protection.initialPosition - Math.abs(position));
                long remaining = Math.max(0, protection.quantity - alreadyClosed);
                if (Long.signum(position) != protection.direction || remaining == 0) {
                    if (protection.takeOrder > 0) book.cancel(owner, protection.takeOrder);
                    protections.remove(owner);
                    continue;
                }
                boolean stopped = lastPrice > 0 && protection.stopPrice > 0
                    && (position > 0 ? lastPrice <= protection.stopPrice : lastPrice >= protection.stopPrice);
                if (!protection.triggered && !stopped) continue;
                protection.triggered = true;
                if (protection.takeOrder > 0) book.cancel(owner, protection.takeOrder);
                submit(
                    owner,
                    position > 0 ? OrderBook.Side.SELL : OrderBook.Side.BUY,
                    position > 0 ? settings.lowerPrice : settings.upperPrice,
                    Math.min(Math.abs(position), remaining),
                    true,
                    true,
                    gameTick);
                if (ledger.position(owner, id).quantity == 0) protections.remove(owner);
            }
        } finally {
            checkingProtections = false;
        }
    }

    private static final class Checkpoint {

        private int version;
        private String id;
        private Settings settings;
        private String book;
        private List<TradeHistory.Bar> candles;
        private double fair;
        private double drift; // Kept for version-one save validation.
        private MarketDynamics dynamics;
        private double flow;
        private long lastPrice;
        private long volume;
        private Map<UUID, Protection> protections;
    }

    public SimulatedInstrument(String id, Settings settings, MarginLedger ledger, MarginLedger.Instrument settlement,
        long seed) {
        this.id = id;
        this.settings = settings.copy();
        this.ledger = ledger;
        this.book = new OrderBook(settlement);
        this.history = new TradeHistory(settings.historyCapacity, settings.candleTicks);
        fairPrice = settings.initialPrice;
        dynamics = new MarketDynamics(fairPrice, seed ^ id.hashCode());
    }

    private SimulatedInstrument(Checkpoint saved, MarginLedger ledger, long seed) {
        id = saved.id;
        settings = saved.settings.copy();
        this.ledger = ledger;
        book = OrderBook.restore(saved.book, ledger.instrument(id));
        history = TradeHistory.restore(settings.historyCapacity, settings.candleTicks, saved.candles);
        fairPrice = saved.fair;
        dynamics = saved.dynamics == null ? new MarketDynamics(fairPrice, seed ^ id.hashCode()) : saved.dynamics;
        dynamics.validate(settings.lowerPrice, settings.upperPrice);
        flow = saved.flow;
        lastPrice = saved.lastPrice;
        volume = saved.volume;
        if (saved.protections == null) throw new IllegalArgumentException("Missing saved protections");
        for (Map.Entry<UUID, Protection> entry : saved.protections.entrySet()) {
            Protection protection = entry.getValue();
            if (!ledger.hasAccount(entry.getKey()) || protection == null
                || protection.quantity < 1
                || Math.abs(protection.direction) != 1
                || protection.stopPrice < 0
                || protection.takePrice < 0) {
                throw new IllegalArgumentException("Invalid saved protection");
            }
        }
        protections.putAll(saved.protections);
    }

    public String checkpoint() {
        Checkpoint saved = new Checkpoint();
        saved.version = 1;
        saved.id = id;
        saved.settings = settings;
        saved.book = book.checkpoint();
        saved.candles = history.snapshot();
        saved.fair = fairPrice;
        saved.dynamics = dynamics;
        saved.flow = flow;
        saved.lastPrice = lastPrice;
        saved.volume = volume;
        saved.protections = protections;
        return new Gson().toJson(saved);
    }

    public static SimulatedInstrument restore(String json, MarginLedger ledger, long seed) {
        Checkpoint saved = new Gson().fromJson(json, Checkpoint.class);
        if (saved == null || saved.version != 1
            || saved.id == null
            || saved.settings == null
            || !Double.isFinite(saved.fair)
            || !Double.isFinite(saved.drift)
            || !Double.isFinite(saved.flow)
            || saved.fair < saved.settings.lowerPrice
            || saved.fair > saved.settings.upperPrice
            || saved.volume < 0
            || saved.lastPrice < 0
            || saved.lastPrice > saved.settings.upperPrice) {
            throw new IllegalArgumentException("Unsupported instrument checkpoint");
        }
        return new SimulatedInstrument(saved, ledger, seed);
    }

    public OrderBook.Result submit(UUID owner, OrderBook.Side side, long price, long quantity, boolean market,
        boolean reduceOnly, long gameTick) {
        if (price < settings.lowerPrice || price > settings.upperPrice) {
            throw new IllegalArgumentException("Order price outside instrument bounds");
        }
        marketTick = Math.max(marketTick, gameTick);
        OrderBook.Result result = book.submit(owner, side, price, quantity, market, reduceOnly);
        for (OrderBook.Fill fill : result.fills) {
            history.fill(gameTick, fill.price, fill.quantity);
            lastPrice = fill.price;
            volume = Math.addExact(volume, fill.quantity);
            flow = Math.max(-1, Math.min(1, flow * 0.95 + (side == OrderBook.Side.BUY ? 1 : -1) * 0.05));
        }
        checkProtections(gameTick);
        return result;
    }

    public boolean cancel(UUID owner, long id) {
        return book.cancel(owner, id);
    }

    public Snapshot snapshot(long gameTick) {
        marketTick = Math.max(marketTick, gameTick);
        history.advance(gameTick);
        return new Snapshot(this);
    }

    /** Hidden fair price only informs bots; it is never returned by a public snapshot. */
    public void updateFairPrice() {
        updateFairPrice(marketTick);
    }

    public void updateFairPrice(long gameTick) {
        marketTick = Math.max(marketTick, gameTick);
        dynamics.step(settings, flow); // Persistent institutional demand, not the quoted fair-value anchor.
        double average = history.averagePrice(marketTick, settings.fairWindowTicks);
        if (average <= 0) average = lastPrice > 0 ? lastPrice : fairPrice;
        fairPrice = Math
            .max(settings.lowerPrice, Math.min(settings.upperPrice, dynamics.noisyFair(average, settings.volatility)));
        flow *= 0.95;
    }

    /** Register from the worker thread before opening the player command endpoint. */
    public void addBot(MarketWorker worker, Strategy strategy, int index, long seed, long interval,
        double probability) {
        UUID owner = UUID
            .nameUUIDFromBytes(("nhkantan:" + id + ":" + strategy + ":" + index).getBytes(StandardCharsets.UTF_8));
        ledger.provisionInstitution(
            owner,
            settings.institutionalCapitalCents,
            settings.botCashCents,
            settings.institutionalPositionLimit);
        Random random = new Random(seed ^ owner.getLeastSignificantBits());
        worker.schedule(
            seed ^ owner.getMostSignificantBits(),
            interval,
            settings.jitter,
            probability,
            () -> act(owner, strategy, random, worker.gameTick()));
    }

    void act(UUID owner, Strategy strategy, Random random, long gameTick) {
        if (strategy == Strategy.MARKET_MAKER) {
            maintainQuotes(owner, random, gameTick);
            return;
        }
        // Short-lived noise quotes cannot pin the market to yesterday's price indefinitely.
        if (strategy == Strategy.NOISE) for (OrderBook.Order order : orders(owner)) book.cancel(owner, order.id);
        List<TradeHistory.Bar> bars = history.snapshot();
        long reference = lastPrice > 0 ? lastPrice : Math.round(fairPrice);
        double signal;
        if (strategy == Strategy.TREND) {
            if (bars.size() < 2) return;
            int lookback = Math.min(bars.size() - 1, 3 + random.nextInt(10));
            signal = reference - bars.get(bars.size() - 1 - lookback).close
                + dynamics.pressure() * Math.max(settings.volatility * 8, reference * 0.002);
        } else if (strategy == Strategy.MEAN_REVERSION) {
            if (bars.isEmpty()) return;
            int window = Math.min(bars.size(), 5 + random.nextInt(15));
            double sum = 0;
            for (int i = bars.size() - window; i < bars.size(); i++) sum += bars.get(i).close;
            signal = sum / window - reference;
        } else signal = random.nextDouble()
            < 0.5 + dynamics.pressure() * (dynamics.burst() ? 0.45 : strategy == Strategy.WHALE ? 0.35 : 0.22) ? 1 : -1;
        if (signal == 0) return;
        long inventory = ledger.position(owner, id).quantity;
        long softLimit = Math.max(
            1,
            ledger.instrument(id)
                .positionLimit(owner) * 3
                / 4);
        // Inventory-constrained participants take profits rather than eventually becoming permanently inactive.
        if (inventory >= softLimit) signal = -1;
        else if (inventory <= -softLimit) signal = 1;
        OrderBook.Side side = signal > 0 ? OrderBook.Side.BUY : OrderBook.Side.SELL;
        long quantity = strategy == Strategy.WHALE
            ? (30 + random.nextInt(121)) * Math.max(1, settings.baseOrderSize / 3)
            : Math.max(1, settings.baseOrderSize / 3) + (long) (random.nextDouble() * settings.baseOrderSize);
        if (dynamics.burst()) quantity = Math.multiplyExact(quantity, 3);
        quantity = Math.min(
            quantity,
            ledger.instrument(id)
                .positionLimit(owner));
        boolean market = strategy != Strategy.NOISE || random.nextBoolean();
        long price = market ? (side == OrderBook.Side.BUY ? settings.upperPrice : settings.lowerPrice)
            : bounded(reference + (side == OrderBook.Side.BUY ? -1 : 1) * (1 + random.nextInt(5)));
        submit(owner, side, price, quantity, market, false, gameTick);
    }

    private void maintainQuotes(UUID owner, Random random, long gameTick) {
        List<OrderBook.Order> owned = new ArrayList<>();
        for (OrderBook.Side side : OrderBook.Side.values()) {
            for (OrderBook.Order order : book.orders(side)) if (order.owner.equals(owner)) owned.add(order);
        }
        // Retire a bounded number of stale quotes per independent action, preserving finite standing liquidity.
        owned
            .sort((left, right) -> Double.compare(Math.abs(right.price - fairPrice), Math.abs(left.price - fairPrice)));
        double staleDistance = Math.max(settings.depth * 3D, settings.volatility * 8);
        int removed = 0;
        for (OrderBook.Order order : owned) {
            if (removed >= 2) break;
            if (Math.abs(order.price - fairPrice) > staleDistance || owned.size() - removed >= settings.depth * 2)
                if (book.cancel(owner, order.id)) removed++;
        }
        if (owned.size() - removed + 2 > settings.depth * 2) {
            for (OrderBook.Order order : owned) {
                if (removed >= 2) break;
                if (book.cancel(owner, order.id)) removed++;
            }
        }
        long inventory = ledger.position(owner, id).quantity;
        double center = fairPrice + dynamics.quotePremium(settings, fairPrice) - inventory * 0.01;
        center += flow * Math.max(settings.volatility * 3, fairPrice * 0.001);
        long softLimit = Math.max(
            1,
            ledger.instrument(id)
                .positionLimit(owner) * 3
                / 4);
        for (OrderBook.Side side : OrderBook.Side.values()) {
            if ((inventory >= softLimit && side == OrderBook.Side.BUY)
                || (inventory <= -softLimit && side == OrderBook.Side.SELL)) continue;
            int count = 0;
            for (OrderBook.Order order : orders(owner)) if (order.side == side) count++;
            // Rebuild a few missing levels after a large sweep, rather than replenishing one tiny order per action.
            int replenishments = Math.min(3, Math.max(0, settings.depth - count));
            for (int level = 0; level < replenishments; level++) {
                long distance = 1 + random.nextInt(settings.depth) + level;
                long price = bounded(Math.round(center) + (side == OrderBook.Side.BUY ? -distance : distance));
                long quantity = settings.baseOrderSize + (long) (random.nextDouble() * settings.baseOrderSize * 2);
                submit(owner, side, price, quantity, false, false, gameTick);
            }
        }
    }

    private long bounded(long price) {
        return Math.max(settings.lowerPrice, Math.min(settings.upperPrice, price));
    }
}
