package com.yuelengm.nhkantan.options;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.yuelengm.nhkantan.market.order.MarginLedger;

/**
 * Writer-only cash-settled options with early exercise and market-maker buyback; shares the exchange cash ledger and
 * save barrier.
 */
public final class OptionMarket {

    public static final class Contract {

        public final String id;
        public final String underlying;
        public final OptionPricing.Kind kind;
        public final double strike;
        public final long expiryTick;
        public final long multiplier;

        public Contract(String id, String underlying, OptionPricing.Kind kind, double strike, long expiryTick,
            long multiplier) {
            if (id == null || id.isEmpty()
                || underlying == null
                || underlying.isEmpty()
                || kind == null
                || !Double.isFinite(strike)
                || strike <= 0
                || expiryTick < 1
                || multiplier < 1
                || multiplier > 1000000) {
                throw new IllegalArgumentException("Invalid option contract");
            }
            this.id = id;
            this.underlying = underlying;
            this.kind = kind;
            this.strike = strike;
            this.expiryTick = expiryTick;
            this.multiplier = multiplier;
        }
    }

    public static final class Quote {

        public final long id;
        public final String contract;
        public final long validUntilMillis;
        public final long unitCents;
        public final long bidUnitCents;
        public final double theoretical;
        public final double delta;

        private Quote(long id, Contract contract, long deadline, long unitCents, long bidUnitCents,
            OptionPricing.Value value) {
            this.id = id;
            this.contract = contract.id;
            validUntilMillis = deadline;
            this.unitCents = unitCents;
            this.bidUnitCents = bidUnitCents;
            theoretical = value.premium;
            delta = value.delta;
        }
    }

    public static final class Holding {

        public final long id;
        public final UUID owner;
        public final String contract;
        public final long quantity;
        public final long costCents;
        private boolean settled;
        private long payoutCents;
        private long closedQuantity;

        private Holding(long id, UUID owner, String contract, long quantity, long costCents) {
            this.id = id;
            this.owner = owner;
            this.contract = contract;
            this.quantity = quantity;
            this.costCents = costCents;
        }

        private Holding(Holding source) {
            this(source.id, source.owner, source.contract, source.quantity, source.costCents);
            settled = source.settled;
            payoutCents = source.payoutCents;
            closedQuantity = source.closedQuantity;
        }

        public long remainingQuantity() {
            return settled ? 0 : quantity - closedQuantity;
        }

        public long remainingCostCents() {
            return java.math.BigInteger.valueOf(costCents)
                .multiply(java.math.BigInteger.valueOf(remainingQuantity()))
                .divide(java.math.BigInteger.valueOf(quantity))
                .longValueExact();
        }

        public boolean settled() {
            return settled;
        }

        public long payoutCents() {
            return payoutCents;
        }
    }

    private static final class State {

        private int version;
        private long nextHolding;
        private long nextQuote;
        private Map<String, Contract> contracts;
        private List<Holding> holdings;
        private Map<String, Double> settlementPrices;
    }

    private final MarginLedger ledger;
    private com.yuelengm.nhkantan.market.order.MarketNotices notices;

    public void notifications(com.yuelengm.nhkantan.market.order.MarketNotices outbox) {
        notices = outbox;
    }

    private final Map<String, Contract> contracts = new LinkedHashMap<>();
    private final Map<String, Quote> quotes = new HashMap<>();
    private final LinkedHashMap<Long, Quote> validQuotes = new LinkedHashMap<>();
    private final List<Holding> holdings = new ArrayList<>();
    private final Map<String, Double> settlementPrices = new HashMap<>();
    private long nextHolding = 1;
    private long nextQuote = 1;

    public OptionMarket(MarginLedger ledger) {
        this.ledger = ledger;
    }

    public void add(Contract contract) {
        if (contracts.containsKey(contract.id)) throw new IllegalArgumentException("Duplicate option contract");
        contracts.put(contract.id, contract);
    }

    public Quote quote(String id, double spot, long gameTick, long marketMillis, long validMillis, double iv,
        double markup, double minimumMarkup) {
        Contract contract = requireContract(id);
        if (gameTick >= contract.expiryTick || marketMillis < 0 || validMillis < 1 || nextQuote == Long.MAX_VALUE) {
            throw new IllegalArgumentException("Option is expired or quote is invalid");
        }
        OptionPricing.Value value = OptionPricing
            .value(contract.kind, spot, contract.strike, contract.expiryTick - gameTick, iv);
        long cents = OptionPricing.askCents(value.premium, markup, minimumMarkup, contract.multiplier, 1);
        double spread = Math.max(value.premium * markup, minimumMarkup);
        long bid = java.math.BigDecimal.valueOf(Math.max(0, value.premium - spread))
            .multiply(java.math.BigDecimal.valueOf(contract.multiplier))
            .movePointRight(2)
            .setScale(0, java.math.RoundingMode.FLOOR)
            .longValueExact();
        Quote quote = new Quote(nextQuote++, contract, Math.addExact(marketMillis, validMillis), cents, bid, value);
        quotes.put(id, quote);
        validQuotes.put(quote.id, quote);
        while (validQuotes.size() > 8192) validQuotes.remove(
            validQuotes.keySet()
                .iterator()
                .next());
        return quote;
    }

    public Holding buy(UUID owner, String id, long quoteId, long quantity, long maximumCost, long gameTick,
        long marketMillis, long accountLimit) {
        Contract contract = requireContract(id);
        Quote quote = validQuotes.get(quoteId);
        if (quote == null || !quote.contract.equals(id)
            || marketMillis >= quote.validUntilMillis
            || gameTick >= contract.expiryTick
            || quantity < 1
            || quantity > accountLimit
            || nextHolding == Long.MAX_VALUE) {
            throw new IllegalArgumentException("Option quote expired or quantity invalid");
        }
        long owned = 0;
        for (Holding holding : holdings) {
            if (!holding.settled && holding.owner.equals(owner))
                owned = Math.addExact(owned, holding.remainingQuantity());
        }
        if (Math.addExact(owned, quantity) > accountLimit) throw new IllegalArgumentException("Option position limit");
        long cost = Math.multiplyExact(quote.unitCents, quantity);
        if (cost > maximumCost) throw new IllegalArgumentException("Option quote exceeds accepted price");
        Holding holding = new Holding(nextHolding, owner, id, quantity, cost);
        if (!ledger.charge(owner, cost)) throw new IllegalArgumentException("Insufficient available cash");
        nextHolding++;
        holdings.add(holding);
        return new Holding(holding);
    }

    public Holding holding(UUID owner, long id) {
        for (Holding holding : holdings)
            if (holding.id == id && holding.owner.equals(owner)) return new Holding(holding);
        throw new IllegalArgumentException("Unknown option holding");
    }

    private Holding requireOpen(UUID owner, long id, long quantity, long gameTick) {
        for (Holding holding : holdings) if (holding.id == id && holding.owner.equals(owner)) {
            if (quantity < 1 || quantity > holding.remainingQuantity()
                || gameTick >= requireContract(holding.contract).expiryTick)
                throw new IllegalArgumentException("Option is closed, expired or quantity invalid");
            return holding;
        }
        throw new IllegalArgumentException("Unknown option holding");
    }

    public Holding close(UUID owner, long holdingId, long quantity, long quoteId, long minimumPayout, long gameTick,
        long marketMillis) {
        Holding holding = requireOpen(owner, holdingId, quantity, gameTick);
        Quote quote = validQuotes.get(quoteId);
        if (quote == null || !quote.contract.equals(holding.contract) || marketMillis >= quote.validUntilMillis)
            throw new IllegalArgumentException("Buyback quote expired");
        return finishExit(holding, quantity, Math.multiplyExact(quote.bidUnitCents, quantity), minimumPayout);
    }

    public Holding exercise(UUID owner, long holdingId, long quantity, double spot, long minimumPayout, long gameTick) {
        Holding holding = requireOpen(owner, holdingId, quantity, gameTick);
        long payout = intrinsicCents(requireContract(holding.contract), spot, quantity);
        if (payout == 0) throw new IllegalArgumentException("Exercise has no intrinsic value");
        return finishExit(holding, quantity, payout, minimumPayout);
    }

    private Holding finishExit(Holding holding, long quantity, long payout, long minimumPayout) {
        if (minimumPayout < 0 || payout < minimumPayout)
            throw new IllegalArgumentException("Payout below accepted amount");
        long total = Math.addExact(holding.payoutCents, payout);
        long closed = Math.addExact(holding.closedQuantity, quantity);
        ledger.credit(holding.owner, payout);
        holding.payoutCents = total;
        holding.closedQuantity = closed;
        holding.settled = closed == holding.quantity;
        return new Holding(holding);
    }

    public static long intrinsicCents(Contract contract, double spot, long quantity) {
        if (!Double.isFinite(spot) || spot <= 0 || quantity < 1) throw new IllegalArgumentException("Invalid exercise");
        java.math.BigDecimal intrinsic = java.math.BigDecimal.valueOf(spot)
            .subtract(java.math.BigDecimal.valueOf(contract.strike));
        if (contract.kind == OptionPricing.Kind.PUT) intrinsic = intrinsic.negate();
        return intrinsic.max(java.math.BigDecimal.ZERO)
            .multiply(java.math.BigDecimal.valueOf(contract.multiplier))
            .multiply(java.math.BigDecimal.valueOf(quantity))
            .movePointRight(2)
            .setScale(0, java.math.RoundingMode.FLOOR)
            .longValueExact();
    }

    /** Adapter supplies an already-frozen expiry-window reference, never a post-expiry current price. */
    public void settle(String id, long gameTick, double frozenExpiryPrice) {
        Contract contract = requireContract(id);
        if (gameTick < contract.expiryTick || !Double.isFinite(frozenExpiryPrice) || frozenExpiryPrice <= 0) {
            throw new IllegalArgumentException("Invalid expiry settlement");
        }
        Double existing = settlementPrices.get(id);
        if (existing != null && Double.compare(existing, frozenExpiryPrice) != 0) {
            throw new IllegalArgumentException("Expiry price is already frozen");
        }
        settlementPrices.put(id, frozenExpiryPrice);
        for (Holding holding : holdings) {
            if (!holding.contract.equals(id) || holding.settled) continue;
            long quantity = holding.remainingQuantity();
            long payout = intrinsicCents(contract, frozenExpiryPrice, quantity);
            finishExit(holding, quantity, payout, 0);
            if (notices != null) notices.record(
                holding.owner,
                com.yuelengm.nhkantan.market.order.MarketNotices.Kind.OPTION_EXPIRED,
                contract.underlying,
                gameTick,
                quantity,
                0,
                payout,
                contract.expiryTick);
        }
        quotes.remove(id);
    }

    public Double settlementPrice(String id) {
        return settlementPrices.get(id);
    }

    public List<Contract> contracts() {
        return Collections.unmodifiableList(new ArrayList<>(contracts.values()));
    }

    public Map<String, Quote> quotes() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(quotes));
    }

    public List<Holding> holdings(UUID owner) {
        List<Holding> result = new ArrayList<>();
        for (Holding holding : holdings) if (holding.owner.equals(owner)) result.add(new Holding(holding));
        return Collections.unmodifiableList(result);
    }

    public double delta(String underlying, double spot, long gameTick, double iv) {
        double delta = 0;
        for (Holding holding : holdings) {
            Contract contract = requireContract(holding.contract);
            if (holding.settled || !contract.underlying.equals(underlying) || gameTick >= contract.expiryTick) continue;
            delta += holding.remainingQuantity() * contract.multiplier
                * OptionPricing.value(contract.kind, spot, contract.strike, contract.expiryTick - gameTick, iv).delta;
        }
        if (!Double.isFinite(delta)) throw new IllegalArgumentException("Delta exceeds numeric limits");
        return delta;
    }

    private Contract requireContract(String id) {
        Contract contract = contracts.get(id);
        if (contract == null) throw new IllegalArgumentException("Unknown option contract");
        return contract;
    }

    public String checkpoint() {
        State state = new State();
        state.version = 1;
        state.nextHolding = nextHolding;
        state.nextQuote = nextQuote;
        state.contracts = contracts;
        state.holdings = holdings;
        state.settlementPrices = settlementPrices;
        return new Gson().toJson(state);
    }

    public static OptionMarket restore(String json, MarginLedger ledger) {
        State state = new Gson().fromJson(json, State.class);
        if (state == null || state.version != 1
            || state.nextHolding < 1
            || state.nextQuote < 1
            || state.contracts == null
            || state.holdings == null
            || state.settlementPrices == null) {
            throw new IllegalArgumentException("Invalid option checkpoint");
        }
        OptionMarket market = new OptionMarket(ledger);
        for (Contract contract : state.contracts.values()) market.add(
            new Contract(
                contract.id,
                contract.underlying,
                contract.kind,
                contract.strike,
                contract.expiryTick,
                contract.multiplier));
        long previous = 0;
        for (Holding holding : state.holdings) {
            if (holding == null || holding.id <= previous
                || holding.id >= state.nextHolding
                || holding.quantity <= 0
                || holding.costCents < 0
                || holding.payoutCents < 0
                || holding.closedQuantity < 0
                || holding.closedQuantity > holding.quantity
                || (!holding.settled && holding.closedQuantity == holding.quantity)
                || !ledger.hasAccount(holding.owner)
                || !market.contracts.containsKey(holding.contract))
                throw new IllegalArgumentException("Invalid option holding");
            // Old settled records had no partial-close counter.
            if (holding.settled) holding.closedQuantity = holding.quantity;
            market.holdings.add(new Holding(holding));
            previous = holding.id;
        }
        for (Map.Entry<String, Double> price : state.settlementPrices.entrySet()) {
            if (!market.contracts.containsKey(price.getKey()) || price.getValue() == null
                || !Double.isFinite(price.getValue())
                || price.getValue() <= 0) throw new IllegalArgumentException("Invalid expiry price");
        }
        market.settlementPrices.putAll(state.settlementPrices);
        market.nextHolding = state.nextHolding;
        market.nextQuote = state.nextQuote;
        return market;
    }
}
