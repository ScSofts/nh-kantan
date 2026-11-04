package com.yuelengm.nhkantan.trading;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.storage.MapStorage;

import com.yuelengm.nhkantan.config.TradingConfig;
import com.yuelengm.nhkantan.market.LeverageRange;
import com.yuelengm.nhkantan.market.StockSnapshot;
import com.yuelengm.nhkantan.market.definition.StockDefinition;
import com.yuelengm.nhkantan.market.engine.StockMarket;
import com.yuelengm.nhkantan.market.server.MarketService;
import com.yuelengm.nhkantan.trading.TradeResult.Status;
import com.yuelengm.nhkantan.util.MathUtils;
import com.yuelengm.nhkantan.wallet.WalletService;

/**
 * Server-side owner of all margin positions. Every operation runs on the server thread and settles the wallet and the
 * positions together, so concurrent open requests cannot pass the position limits.
 * <p>
 * Cash-settled contracts: opening locks margin plus the opening fee, closing returns margin plus profit or loss.
 * Liquidation settles at the checked price and may push the balance negative; debt is handled by the wallet.
 */
public class TradingService {

    @Nullable
    private static TradingService instance;

    private final PositionSavedData data;

    private TradingService(PositionSavedData data) {
        this.data = data;
    }

    /** The running service, or {@code null} when no server is running. */
    @Nullable
    public static TradingService get() {
        return instance;
    }

    public static void start(MinecraftServer server) {
        // The overworld map storage is global and shared by every dimension
        MapStorage storage = server.worldServerForDimension(0).mapStorage;
        PositionSavedData data = (PositionSavedData) storage
            .loadData(PositionSavedData.class, PositionSavedData.DATA_NAME);
        if (data == null) {
            data = new PositionSavedData(PositionSavedData.DATA_NAME);
            storage.setData(PositionSavedData.DATA_NAME, data);
        }
        instance = new TradingService(data);
    }

    public static void stop() {
        instance = null;
    }

    /** The player's open positions, or none when no server is running (e.g. on a remote client). */
    public static List<Position> positionsOf(EntityPlayer player) {
        TradingService service = instance;
        return service == null ? Collections.emptyList() : service.positions(player.getUniqueID());
    }

    /** Opens a position for the player if a server is running; see {@link #open}. */
    public static void openFor(EntityPlayer player, String stockId, Direction direction, long marginCents,
        int leverage) {
        TradingService service = instance;
        if (service != null) service.open(player.getUniqueID(), stockId, direction, marginCents, leverage);
    }

    /** Closes the player's position if a server is running; see {@link #close}. */
    public static void closeFor(EntityPlayer player, long positionId) {
        TradingService service = instance;
        if (service != null) service.close(player.getUniqueID(), positionId);
    }

    /**
     * @return the current price of the enabled stock, or {@code NaN} when it is not tradable right now
     */
    public double priceOf(String stockId) {
        StockMarket market = market();
        if (market == null) return Double.NaN;
        for (StockSnapshot snapshot : market.snapshots()) {
            if (snapshot.id()
                .equals(stockId)) {
                float[] prices = snapshot.prices();
                return prices.length == 0 ? Double.NaN : prices[prices.length - 1];
            }
        }
        return Double.NaN;
    }

    /** All open positions of the player, in id order. */
    public List<Position> positions(UUID player) {
        return data.owned(player);
    }

    /**
     * Opens a position at the current price: checks limits, leverage, margin and fee coverage, then locks margin plus
     * fee from the wallet.
     */
    public synchronized TradeResult open(UUID id, String stockId, Direction direction, long marginCents, int leverage) {
        WalletService wallets = WalletService.get();
        if (market() == null || wallets == null) return TradeResult.failure(Status.MARKET_UNAVAILABLE, 0L);
        long balance = wallets.balance(id);
        // Only enabled, running stocks have a price
        double price = priceOf(stockId);
        if (Double.isNaN(price) || price <= 0.0D) return TradeResult.failure(Status.NOT_TRADABLE, balance);
        if (balance < 0L) return TradeResult.failure(Status.IN_DEBT, balance);
        if (marginCents <= 0L) return TradeResult.failure(Status.INVALID_MARGIN, balance);
        InstrumentRules rules = InstrumentRuleResolver.resolve(leverageOf(stockId));
        if (leverage < rules.minLeverage() || leverage > rules.maxLeverage()) {
            return TradeResult.failure(Status.INVALID_LEVERAGE, balance);
        }
        long notional = TradingMath.notional(marginCents, leverage);
        if (notional <= 0L) return TradeResult.failure(Status.INVALID_MARGIN, balance);
        long fee = TradingMath.openFee(notional, rules.openFeePercent());
        long total = MathUtils.saturatedAdd(marginCents, fee);
        if (total <= 0L) return TradeResult.failure(Status.INVALID_MARGIN, balance);
        List<Position> owned = data.owned(id);
        if (owned.size() >= TradingConfig.maxOpenPositionsPerPlayer) {
            return TradeResult.failure(Status.POSITION_LIMIT, balance);
        }
        int onStock = 0;
        for (Position position : owned) {
            if (position.stockId()
                .equals(stockId)) onStock++;
        }
        if (onStock >= rules.maxOpenPositions()) return TradeResult.failure(Status.STOCK_LIMIT, balance);
        if (total > balance) return TradeResult.failure(Status.INSUFFICIENT_BALANCE, balance);
        Position position = new Position(
            data.claimId(),
            stockId,
            direction,
            marginCents,
            leverage,
            price,
            fee,
            rules.liquidationPercent());
        wallets.setBalance(id, balance - total);
        data.add(id, position);
        return new TradeResult(Status.OPENED, position.id(), fee, balance - total);
    }

    /**
     * Closes the player's position at the current price and returns margin plus profit or loss. A missing price or a
     * disabled or removed stock keeps the position open.
     */
    public synchronized TradeResult close(UUID id, long positionId) {
        WalletService wallets = WalletService.get();
        if (wallets == null) return TradeResult.failure(Status.MARKET_UNAVAILABLE, 0L);
        Position position = findOwned(id, positionId);
        if (position == null) return TradeResult.failure(Status.UNKNOWN_POSITION, wallets.balance(id));
        double price = priceOf(position.stockId());
        if (Double.isNaN(price) || price <= 0.0D) {
            return TradeResult.failure(Status.NOT_TRADABLE, wallets.balance(id));
        }
        data.remove(id, positionId);
        long profit = position.unrealizedCents(price);
        long payout = MathUtils.saturatedAdd(position.marginCents(), profit);
        long balance = MathUtils.saturatedAdd(wallets.balance(id), payout);
        wallets.setBalance(id, balance);
        return new TradeResult(Status.CLOSED, positionId, profit, balance);
    }

    /**
     * Checks every open position against the newest prices and settles those past their liquidation threshold, in id
     * order. Runs once per market update so a price jump cannot skip liquidation.
     */
    public synchronized List<TradeResult> liquidate(Map<String, Float> prices) {
        List<TradeResult> settled = new ArrayList<>();
        WalletService wallets = WalletService.get();
        if (wallets == null) return settled;
        for (Map.Entry<UUID, List<Position>> entry : data.snapshot()
            .entrySet()) {
            UUID player = entry.getKey();
            for (Position position : entry.getValue()) {
                Float price = prices.get(position.stockId());
                if (price == null || price.isNaN() || price <= 0.0F || !position.liquidated(price)) continue;
                // Positions and wallets are keyed by UUID and persist offline, so liquidation never needs a live player
                if (data.remove(player, position.id()) == null) continue;
                long profit = position.unrealizedCents(price);
                long payout = MathUtils.saturatedAdd(position.marginCents(), profit);
                long balance = MathUtils.saturatedAdd(wallets.balance(player), payout);
                wallets.setBalance(player, balance);
                settled.add(new TradeResult(Status.LIQUIDATED, position.id(), profit, balance));
            }
        }
        return settled;
    }

    /** Finds the position only when the player owns it; concurrent closes cannot settle someone else's trade. */
    @Nullable
    private Position findOwned(UUID player, long positionId) {
        for (Position position : data.owned(player)) {
            if (position.id() == positionId) return position;
        }
        return null;
    }

    /** Leverage limits of the running stock; stocks that are not running have no price and are refused anyway. */
    private static LeverageRange leverageOf(String stockId) {
        StockMarket market = market();
        StockDefinition definition = market == null ? null : market.definition(stockId);
        return definition == null ? LeverageRange.DEFAULT : definition.leverage();
    }

    @Nullable
    private static StockMarket market() {
        MarketService service = MarketService.get();
        return service == null ? null : service.market();
    }
}
