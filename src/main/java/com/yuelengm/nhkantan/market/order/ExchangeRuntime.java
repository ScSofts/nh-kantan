package com.yuelengm.nhkantan.market.order;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.gson.Gson;
import com.yuelengm.nhkantan.options.OptionMarket;

/** Lifecycle-owned asynchronous facade. Only the private worker accesses books, account state or request history. */
public final class ExchangeRuntime implements AutoCloseable {

    public static final class PrivateSnapshot {

        public final UUID generation;
        public final long nextRequest;
        public final MarginLedger.AccountView account;
        public final java.util.List<OptionMarket.Contract> contracts;
        public final Map<String, OptionMarket.Quote> quotes;
        public final java.util.List<OptionMarket.Holding> options;
        public final Map<String, MarginLedger.Position> positions;
        public final Map<String, java.util.List<OrderBook.Order>> orders;

        private PrivateSnapshot(ExchangeRuntime runtime, UUID owner) {
            generation = runtime.generation;
            nextRequest = runtime.requests.nextSequence(owner);
            account = runtime.ledger.account(owner);
            contracts = runtime.options.contracts();
            quotes = runtime.options.quotes();
            options = runtime.options.holdings(owner);
            Map<String, MarginLedger.Position> held = new LinkedHashMap<>();
            Map<String, java.util.List<OrderBook.Order>> working = new LinkedHashMap<>();
            for (Map.Entry<String, SimulatedInstrument> entry : runtime.instruments.entrySet()) {
                held.put(entry.getKey(), runtime.ledger.position(owner, entry.getKey()));
                working.put(
                    entry.getKey(),
                    entry.getValue()
                        .orders(owner));
            }
            positions = Collections.unmodifiableMap(held);
            orders = Collections.unmodifiableMap(working);
        }
    }

    public static final class InstrumentSpec {

        public final SimulatedInstrument.Settings simulation;
        public final long tickValueCents;
        public final int marginBps;
        public final int feePpm;
        public final long maxPosition;

        public InstrumentSpec(SimulatedInstrument.Settings simulation, long tickValueCents, int marginBps, int feePpm,
            long maxPosition) {
            this.simulation = simulation.copy();
            this.tickValueCents = tickValueCents;
            this.marginBps = marginBps;
            this.feePpm = feePpm;
            this.maxPosition = maxPosition;
        }
    }

    private static final class Checkpoint {

        private int version;
        private long gameTick;
        private String ledger;
        private Map<String, String> instruments;
        private Map<UUID, Long> requests;
        private String options;
    }

    private final MarketWorker worker;
    private final UUID generation = UUID.randomUUID();

    private static final class Reply {

        private OrderBook.Result order;
        private OptionMarket.Holding option;
    }

    private final RequestJournal<Reply> requests = new RequestJournal<>(256);
    private final Map<String, SimulatedInstrument> instruments = new LinkedHashMap<>();
    private MarginLedger ledger;
    private OptionMarket options;
    private final CompletableFuture<Void> ready;
    private long restoredTick;
    private boolean botsStarted;
    private int expiryDays = 14;
    private int seriesDays = 2;
    private double iv = 0.8;
    private double markup = 0.04;
    private double minimumMarkup = 0.01;
    private int priceCents = 1;
    private double maintenanceRatio = 0.5;
    private final Map<String, Long> nextSeries = new LinkedHashMap<>();

    public ExchangeRuntime(Map<String, InstrumentSpec> specs, Map<UUID, Long> initialEquity, String checkpoint,
        long seed) {
        worker = new MarketWorker(1024, 64, 40, 100);
        // Copy caller-owned maps before they can change underneath a queued initialization command.
        Map<String, InstrumentSpec> definitions = new LinkedHashMap<>();
        for (Map.Entry<String, InstrumentSpec> entry : specs.entrySet()) {
            InstrumentSpec spec = entry.getValue();
            definitions.put(
                entry.getKey(),
                new InstrumentSpec(
                    spec.simulation,
                    spec.tickValueCents,
                    spec.marginBps,
                    spec.feePpm,
                    spec.maxPosition));
        }
        Map<UUID, Long> balances = new LinkedHashMap<>(initialEquity);
        ready = worker.submit(() -> {
            if (checkpoint == null || checkpoint.isEmpty()) {
                ledger = new MarginLedger();
                for (Map.Entry<UUID, Long> balance : balances.entrySet())
                    ledger.importAccount(balance.getKey(), balance.getValue());
                for (Map.Entry<String, InstrumentSpec> definition : definitions.entrySet()) {
                    InstrumentSpec spec = definition.getValue();
                    MarginLedger.Instrument settlement = ledger.instrument(
                        definition.getKey(),
                        spec.tickValueCents,
                        spec.marginBps,
                        spec.feePpm,
                        spec.maxPosition,
                        spec.simulation.upperPrice);
                    instruments.put(
                        definition.getKey(),
                        new SimulatedInstrument(definition.getKey(), spec.simulation, ledger, settlement, seed));
                }
                options = new OptionMarket(ledger);
            } else {
                Checkpoint saved = new Gson().fromJson(checkpoint, Checkpoint.class);
                if (saved == null || saved.version != 1
                    || saved.gameTick < 0
                    || saved.instruments == null
                    || saved.requests == null
                    || !saved.instruments.keySet()
                        .equals(definitions.keySet())) {
                    throw new IllegalArgumentException("Unsupported exchange save or changed instrument set");
                }
                ledger = MarginLedger.restore(saved.ledger);
                for (Map.Entry<String, String> entry : saved.instruments.entrySet()) {
                    instruments.put(entry.getKey(), SimulatedInstrument.restore(entry.getValue(), ledger, seed));
                }
                requests.restore(saved.requests);
                options = OptionMarket.restore(saved.options, ledger);
                restoredTick = saved.gameTick;
            }
            worker.clock(false, restoredTick);
            return null;
        });
    }

    public CompletableFuture<Void> ready() {
        return ready;
    }

    public UUID generation() {
        return generation;
    }

    public long initialGameTick() {
        requireReady();
        return restoredTick;
    }

    public void clock(boolean active, long gameTick) {
        if (!ready.isDone() || ready.isCompletedExceptionally()) return;
        worker.clock(active, Math.max(restoredTick, gameTick));
    }

    public CompletableFuture<Void> configureMaintenance(int initialBps, int maintenanceBps) {
        return worker.submit(() -> {
            if (initialBps < 1 || maintenanceBps < 0 || maintenanceBps > initialBps)
                throw new IllegalArgumentException("Invalid maintenance margin");
            maintenanceRatio = maintenanceBps / (double) initialBps;
            return null;
        });
    }

    private void marginCycle() {
        if (maintenanceRatio == 0) return;
        for (UUID owner : ledger.owners()) {
            MarginLedger.AccountView account = ledger.account(owner);
            if (account.usedMargin == 0
                || account.equity - account.pendingCredit >= account.usedMargin * maintenanceRatio) continue;
            for (Map.Entry<String, SimulatedInstrument> entry : instruments.entrySet()) {
                SimulatedInstrument instrument = entry.getValue();
                for (OrderBook.Order order : instrument.orders(owner)) instrument.cancel(owner, order.id);
                long quantity = ledger.position(owner, entry.getKey()).quantity;
                if (quantity == 0) continue;
                SimulatedInstrument.Snapshot market = instrument.snapshot(worker.gameTick());
                instrument.submit(
                    owner,
                    quantity > 0 ? OrderBook.Side.SELL : OrderBook.Side.BUY,
                    quantity > 0 ? market.lowerPrice : market.upperPrice,
                    Math.abs(quantity),
                    true,
                    true,
                    worker.gameTick());
            }
        }
    }

    public CompletableFuture<Void> configureOptions(int expiry, int series, double volatility, double premiumMarkup,
        double minMarkup, int cents) {
        return worker.submit(() -> {
            requireReady();
            if (expiry < 1 || series < 1 || cents < 1 || !Double.isFinite(volatility) || volatility <= 0) {
                throw new IllegalArgumentException("Invalid option configuration");
            }
            expiryDays = expiry;
            seriesDays = series;
            iv = volatility;
            markup = premiumMarkup;
            minimumMarkup = minMarkup;
            priceCents = cents;
            worker.schedule(8081, 1000, 0.5, 1, this::hedge);
            return null;
        });
    }

    public CompletableFuture<OptionMarket.Holding> buyOption(UUID epoch, UUID owner, long request, String contract,
        long quote, long quantity, long maximumCost) {
        return worker.submit(() -> {
            requireReady();
            if (!generation.equals(epoch)) throw new IllegalArgumentException("Stale market session");
            Reply reply = requests.execute(owner, request, () -> {
                Reply result = new Reply();
                result.option = options
                    .buy(owner, contract, quote, quantity, maximumCost, worker.gameTick(), worker.marketMillis(), 1000);
                return result;
            });
            if (reply.option == null) throw new IllegalArgumentException("Request ID was used by another command");
            return reply.option;
        });
    }

    private void optionCycle() {
        long now = worker.gameTick();
        for (Map.Entry<String, SimulatedInstrument> entry : instruments.entrySet()) {
            long ticks = entry.getValue()
                .snapshot(now).lastPrice;
            if (ticks <= 0) continue;
            double spot = ticks * priceCents / 100D;
            long latestExpiry = 0;
            for (OptionMarket.Contract contract : options.contracts()) {
                if (!contract.underlying.equals(entry.getKey())) continue;
                latestExpiry = Math.max(latestExpiry, contract.expiryTick);
                if (now >= contract.expiryTick) {
                    Double frozen = options.settlementPrice(contract.id);
                    options.settle(
                        contract.id,
                        now,
                        frozen != null ? frozen : settlementReference(entry.getValue(), contract.expiryTick, spot));
                } else options.quote(contract.id, spot, now, worker.marketMillis(), 1000, iv, markup, minimumMarkup);
            }
            long next = nextSeries
                .getOrDefault(entry.getKey(), Math.max(0, latestExpiry - (expiryDays - seriesDays) * 24000L));
            if (now < next) continue;
            long expiry = Math.addExact(now, expiryDays * 24000L);
            double spacing = Math.max(priceCents / 100D, spot / 4);
            for (int strike = -3; strike <= 3; strike++) {
                double exercise = Math.max(priceCents / 100D, spot + strike * spacing);
                for (com.yuelengm.nhkantan.options.OptionPricing.Kind kind : com.yuelengm.nhkantan.options.OptionPricing.Kind
                    .values()) {
                    String id = entry.getKey() + ":" + now + ":" + strike + ":" + kind;
                    options.add(new OptionMarket.Contract(id, entry.getKey(), kind, exercise, expiry, 1));
                    options.quote(id, spot, now, worker.marketMillis(), 1000, iv, markup, minimumMarkup);
                }
            }
            nextSeries.put(entry.getKey(), now + seriesDays * 24000L);
        }
    }

    private double settlementReference(SimulatedInstrument instrument, long expiry, double fallback) {
        double total = 0;
        long volume = 0;
        double previous = 0;
        SimulatedInstrument.Snapshot data = instrument.snapshot(worker.gameTick());
        for (TradeHistory.Bar bar : data.candles) {
            if (bar.tick + data.bucketTicks > expiry) continue;
            previous = bar.close * priceCents / 100D;
            if (bar.tick >= expiry - 200 && bar.volume > 0) {
                total += (bar.open + (double) bar.high + bar.low + bar.close) / 4 * bar.volume;
                volume = Math.addExact(volume, bar.volume);
            }
        }
        return volume > 0 ? total / volume * priceCents / 100D : previous > 0 ? previous : fallback;
    }

    private void hedge() {
        for (Map.Entry<String, SimulatedInstrument> entry : instruments.entrySet()) {
            SimulatedInstrument.Snapshot market = entry.getValue()
                .snapshot(worker.gameTick());
            if (market.lastPrice <= 0) continue;
            UUID owner = UUID.nameUUIDFromBytes(
                ("nhkantan:hedge:" + entry.getKey()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!ledger.hasAccount(owner)) ledger.importAccount(owner, 100000000L);
            long target = Math.max(
                -1000,
                Math.min(
                    1000,
                    Math.round(
                        options.delta(entry.getKey(), market.lastPrice * priceCents / 100D, worker.gameTick(), iv))));
            long difference = target - ledger.position(owner, entry.getKey()).quantity;
            if (Math.abs(difference) < 2) continue;
            long count = Math.min(20, Math.max(1, (long) (Math.abs(difference) * 0.75)));
            entry.getValue()
                .submit(
                    owner,
                    difference > 0 ? OrderBook.Side.BUY : OrderBook.Side.SELL,
                    difference > 0 ? market.upperPrice : market.lowerPrice,
                    count,
                    true,
                    false,
                    worker.gameTick());
        }
    }

    public CompletableFuture<Void> ensureAccount(UUID owner, long initialCash) {
        return worker.submit(() -> {
            requireReady();
            if (!ledger.hasAccount(owner)) ledger.importAccount(owner, initialCash);
            return null;
        });
    }

    /** Profiles are copied at enqueue time; no mutable configuration object crosses into the worker. */
    public CompletableFuture<Void> startBots(int[] counts, long[] intervals, double[] probabilities, long seed) {
        int[] sizes = counts.clone();
        long[] periods = intervals.clone();
        double[] chances = probabilities.clone();
        int strategies = SimulatedInstrument.Strategy.values().length;
        if (sizes.length != strategies || periods.length != strategies || chances.length != strategies) {
            throw new IllegalArgumentException("Expected one profile per bot strategy");
        }
        int total = 0;
        for (int i = 0; i < strategies; i++) {
            if (sizes[i] < 0 || sizes[i] > 100
                || periods[i] < 1
                || periods[i] > 86400000
                || !Double.isFinite(chances[i])
                || chances[i] < 0
                || chances[i] > 1) {
                throw new IllegalArgumentException("Invalid bot profile");
            }
            total += sizes[i];
        }
        final int configuredTotal = total;
        return worker.submit(() -> {
            requireReady();
            if (botsStarted) throw new IllegalStateException("Bots already registered");
            if ((long) configuredTotal * instruments.size() > 500)
                throw new IllegalArgumentException("Bot budget exceeded");
            botsStarted = true;
            for (Map.Entry<String, SimulatedInstrument> entry : instruments.entrySet()) {
                SimulatedInstrument instrument = entry.getValue();
                worker.schedule(
                    seed ^ entry.getKey()
                        .hashCode(),
                    100,
                    0,
                    1,
                    instrument::updateFairPrice);
                for (int type = 0; type < strategies; type++) {
                    for (int i = 0; i < sizes[type]; i++) {
                        instrument.addBot(
                            worker,
                            SimulatedInstrument.Strategy.values()[type],
                            i,
                            seed,
                            periods[type],
                            chances[type]);
                    }
                }
            }
            return null;
        });
    }

    /** owner must be derived by the authenticated main-thread adapter, never read from a client payload. */
    public CompletableFuture<OrderBook.Result> submit(UUID sessionGeneration, UUID owner, long requestId,
        String instrument, OrderBook.Side side, long price, long quantity, boolean market, boolean reduceOnly) {
        return worker.submit(() -> {
            requireReady();
            if (!generation.equals(sessionGeneration)) throw new IllegalArgumentException("Stale market session");
            Reply reply = requests.execute(owner, requestId, () -> {
                Reply result = new Reply();
                result.order = instrument(instrument)
                    .submit(owner, side, price, quantity, market, reduceOnly, worker.gameTick());
                return result;
            });
            if (reply.order == null) throw new IllegalArgumentException("Request ID was used by a different command");
            return reply.order;
        });
    }

    public CompletableFuture<Boolean> cancel(UUID sessionGeneration, UUID owner, String instrument, long orderId) {
        return worker.submit(() -> {
            requireReady();
            if (!generation.equals(sessionGeneration)) throw new IllegalArgumentException("Stale market session");
            return instrument(instrument).cancel(owner, orderId);
        });
    }

    public CompletableFuture<Void> protect(UUID sessionGeneration, UUID owner, long requestId, String instrument,
        long quantity, long stopPrice, long takePrice) {
        return worker.submit(() -> {
            requireReady();
            if (!generation.equals(sessionGeneration)) throw new IllegalArgumentException("Stale market session");
            requests.execute(owner, requestId, () -> {
                instrument(instrument).protect(owner, quantity, stopPrice, takePrice, worker.gameTick());
                return new Reply();
            });
            return null;
        });
    }

    public CompletableFuture<MarginLedger.AccountView> account(UUID owner) {
        return worker.submit(() -> {
            requireReady();
            return ledger.account(owner);
        });
    }

    public CompletableFuture<PrivateSnapshot> privateSnapshot(UUID authenticatedOwner) {
        return worker.submit(() -> {
            requireReady();
            return new PrivateSnapshot(this, authenticatedOwner);
        });
    }

    public CompletableFuture<Boolean> prepareTransfer(UUID owner, long transferId, long delta) {
        return prepareTransfer(owner, transferId, delta, false);
    }

    public CompletableFuture<Boolean> prepareTransfer(UUID owner, long transferId, long delta, boolean repayment) {
        return worker.submit(() -> {
            requireReady();
            return ledger.prepareTransfer(owner, transferId, delta, repayment);
        });
    }

    public CompletableFuture<Void> finishTransfer(UUID owner, long transferId, boolean complete) {
        return worker.submit(() -> {
            requireReady();
            if (complete) ledger.completeTransfer(owner, transferId);
            else ledger.abortTransfer(owner, transferId);
            return null;
        });
    }

    public CompletableFuture<Map<String, SimulatedInstrument.Snapshot>> publicSnapshot() {
        return worker.submit(() -> {
            requireReady();
            optionCycle();
            marginCycle();
            Map<String, SimulatedInstrument.Snapshot> result = new LinkedHashMap<>();
            for (Map.Entry<String, SimulatedInstrument> entry : instruments.entrySet()) {
                result.put(
                    entry.getKey(),
                    entry.getValue()
                        .snapshot(worker.gameTick()));
            }
            return Collections.unmodifiableMap(result);
        });
    }

    /** This command is the save barrier: all earlier commands are reflected in one detached string. */
    public CompletableFuture<String> checkpoint() {
        return worker.submit(() -> {
            requireReady();
            Checkpoint saved = new Checkpoint();
            saved.version = 1;
            saved.gameTick = worker.gameTick();
            saved.ledger = ledger.checkpoint();
            saved.requests = requests.checkpoint();
            saved.options = options.checkpoint();
            saved.instruments = new LinkedHashMap<>();
            for (Map.Entry<String, SimulatedInstrument> entry : instruments.entrySet()) {
                saved.instruments.put(
                    entry.getKey(),
                    entry.getValue()
                        .checkpoint());
            }
            return new Gson().toJson(saved);
        });
    }

    private SimulatedInstrument instrument(String id) {
        SimulatedInstrument instrument = instruments.get(id);
        if (instrument == null) throw new IllegalArgumentException("Unknown instrument");
        return instrument;
    }

    private void requireReady() {
        if (!ready.isDone() || ready.isCompletedExceptionally()) throw new IllegalStateException("Market is not ready");
    }

    @Override
    public void close() {
        worker.close();
    }
}
