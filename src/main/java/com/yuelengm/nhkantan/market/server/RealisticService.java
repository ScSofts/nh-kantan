package com.yuelengm.nhkantan.market.server;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import com.google.gson.Gson;
import com.yuelengm.nhkantan.NHKantan;
import com.yuelengm.nhkantan.config.ConfigPaths;
import com.yuelengm.nhkantan.config.CurrencyConfig;
import com.yuelengm.nhkantan.config.MarketConfig;
import com.yuelengm.nhkantan.config.RealisticConfig;
import com.yuelengm.nhkantan.market.definition.MarketDefinitionLoader;
import com.yuelengm.nhkantan.market.definition.StockDefinition;
import com.yuelengm.nhkantan.market.order.ExchangeRuntime;
import com.yuelengm.nhkantan.market.order.OrderBook;
import com.yuelengm.nhkantan.market.order.SimulatedInstrument;
import com.yuelengm.nhkantan.market.view.ClientExchangeData;
import com.yuelengm.nhkantan.network.ExchangeFrameMessage;
import com.yuelengm.nhkantan.network.ModNetwork;

/** Minecraft main-thread owner of lifecycle, networking and disk/physical inventory operations. */
public final class RealisticService {

    private static volatile RealisticService instance;
    private static volatile Throwable startupFailure;
    private static volatile ClientExchangeData.Frame unavailableFrame;
    private static final ArrayBlockingQueue<UUID> unavailableRequests = new ArrayBlockingQueue<>(128);

    private static final class Saved {

        private int version;
        private String exchange;
        private Map<UUID, InventoryTransfers.Escrow> escrows;
    }

    private final MinecraftServer server;
    private final ExchangeRuntime runtime;
    private final AtomicMarketStore store;
    private final RealisticSavedData data;
    private final InventoryTransfers transfers;
    private final ExchangeWallet wallet;
    private final Map<String, String> names = new LinkedHashMap<>();
    private final Map<UUID, String> privateViews = new HashMap<>();
    private final Map<UUID, CompletableFuture<ExchangeRuntime.PrivateSnapshot>> privatePending = new HashMap<>();
    private CompletableFuture<Map<String, SimulatedInstrument.Snapshot>> publicPending;
    private final Map<UUID, Long> noticeResendTick = new HashMap<>();
    private final Map<UUID, Long> lastNoticeSent = new HashMap<>();
    private long gameTick;
    private long revision;
    private boolean accepting = true;
    private final ArrayBlockingQueue<UUID> openRequests = new ArrayBlockingQueue<>(128);

    public void requestOpen(UUID authenticatedOwner) {
        openRequests.offer(authenticatedOwner);
    }

    private RealisticService(MinecraftServer server) throws IOException {
        this.server = server;
        RealisticConfig.normalize();
        NHKantan.LOG.info(
            "Effective realistic settings: phase {}..{}s, VWAP weight {}, institution capital/order multipliers {}/{}, position limit {}",
            RealisticConfig.regimeMinSeconds,
            RealisticConfig.regimeMaxSeconds,
            RealisticConfig.fairVwapWeight,
            RealisticConfig.institutionalCapitalMultiplier,
            RealisticConfig.institutionalOrderMultiplier,
            RealisticConfig.institutionalPositionLimit);
        if (RealisticConfig.maintenanceMarginBps > RealisticConfig.initialMarginBps) {
            throw new IOException("Maintenance margin cannot exceed initial margin");
        }
        Path world = server.worldServerForDimension(0)
            .getSaveHandler()
            .getWorldDirectory()
            .toPath();
        store = new AtomicMarketStore(world.resolve("nhkantan/realistic.dat"));
        RealisticSavedData loaded = store.load();
        data = loaded == null ? new RealisticSavedData(RealisticSavedData.DATA_NAME) : loaded;
        Saved saved = loaded == null ? null : new Gson().fromJson(loaded.checkpoint(), Saved.class);
        if (saved != null && (saved.version != 1 || saved.exchange == null || saved.escrows == null)) {
            throw new IOException("Invalid realistic coordinator checkpoint");
        }
        Map<String, ExchangeRuntime.InstrumentSpec> specs = new LinkedHashMap<>();
        for (StockDefinition stock : MarketDefinitionLoader.load(ConfigPaths.directory())
            .stocks()) {
            if (!stock.enabled()) continue;
            SimulatedInstrument.Settings settings = new SimulatedInstrument.Settings();
            settings.lowerPrice = ticks(stock.lowerBound(), RoundingMode.CEILING);
            settings.upperPrice = ticks(stock.upperBound(), RoundingMode.FLOOR);
            settings.initialPrice = ticks(stock.initialPrice(), RoundingMode.HALF_UP);
            settings.botCashCents = Math.multiplyExact(RealisticConfig.botInitialCash, 100L);
            settings.depth = RealisticConfig.depth;
            settings.institutionalCapitalCents = Math
                .multiplyExact(settings.botCashCents, RealisticConfig.institutionalCapitalMultiplier);
            settings.institutionalPositionLimit = RealisticConfig.institutionalPositionLimit;
            settings.fairWindowTicks = Math.multiplyExact(RealisticConfig.fairAverageSeconds, 20L);
            settings.baseOrderSize = Math
                .multiplyExact(RealisticConfig.botOrderSize, RealisticConfig.institutionalOrderMultiplier);
            settings.volatility = RealisticConfig.fairVolatilityTicks * RealisticConfig.volatilityMultiplier;
            settings.impact = RealisticConfig.flowImpact;
            settings.anchorFollowRate = RealisticConfig.anchorFollowRate;
            settings.flowFollowRate = RealisticConfig.flowFollowRate;
            settings.maxFlowImpactBps = RealisticConfig.maxFlowImpactBps;
            settings.fairVwapWeight = RealisticConfig.fairVwapWeight;
            settings.vwapDeviationVolMultiplier = RealisticConfig.vwapDeviationVolMultiplier;
            settings.fairNoiseMemory = RealisticConfig.fairNoiseMemory;
            settings.fairNoiseLimitMultiplier = RealisticConfig.fairNoiseLimitMultiplier;

            settings.jitter = RealisticConfig.intervalJitter;
            settings.regimeMinSteps = Math.multiplyExact(RealisticConfig.regimeMinSeconds, 10);
            settings.regimeMaxSteps = Math.multiplyExact(RealisticConfig.regimeMaxSeconds, 10);
            settings.trendMoveBps = RealisticConfig.trendMoveBps;
            settings.breakoutMoveBps = RealisticConfig.breakoutMoveBps;
            settings.historyCapacity = MarketConfig.visibleCandles;
            settings.candleTicks = MarketConfig.updateIntervalTicks;
            specs.put(
                stock.id(),
                new ExchangeRuntime.InstrumentSpec(
                    settings,
                    RealisticConfig.tickValueCents,
                    RealisticConfig.initialMarginBps,
                    RealisticConfig.commissionPpm,
                    RealisticConfig.maxPosition));
            names.put(stock.id(), stock.name());
        }
        runtime = new ExchangeRuntime(
            specs,
            saved == null ? LegacyMigration.calculate(server) : Collections.emptyMap(),
            saved == null ? null : saved.exchange,
            RealisticConfig.seed);
        try {
            InventoryTransfers.await(runtime.ready());
            gameTick = runtime.initialGameTick();
            InventoryTransfers.await(
                runtime.configureMaintenance(RealisticConfig.initialMarginBps, RealisticConfig.maintenanceMarginBps));
            InventoryTransfers.await(
                runtime.configureOptions(
                    RealisticConfig.expiryGameDays,
                    RealisticConfig.seriesEveryGameDays,
                    RealisticConfig.baseIV,
                    RealisticConfig.optionMarkup,
                    RealisticConfig.optionMinimumMarkup,
                    RealisticConfig.tickValueCents));
            transfers = new InventoryTransfers(
                server,
                runtime,
                this::save,
                saved == null ? Collections.emptyMap() : saved.escrows);
            wallet = new ExchangeWallet(runtime, transfers);
            save(transfers.snapshot());
            LegacyMigration.clearRecords(world, store);
            long[] intervals = new long[RealisticConfig.botIntervalsMillis.length];
            for (int i = 0; i < intervals.length; i++) intervals[i] = RealisticConfig.botIntervalsMillis[i];
            InventoryTransfers.await(
                runtime.startBots(
                    RealisticConfig.botBudget(specs.size()),
                    intervals,
                    RealisticConfig.botProbabilities,
                    RealisticConfig.seed));
        } catch (IOException | RuntimeException e) {
            try {
                runtime.close();
            } catch (RuntimeException shutdown) {
                e.addSuppressed(shutdown);
            }
            throw e;
        }
    }

    public static RealisticService get() {
        return instance;
    }

    public static void clearStartupFailure() {
        startupFailure = null;
        unavailableFrame = null;
        unavailableRequests.clear();
    }

    public static boolean unavailable() {
        return startupFailure != null;
    }

    /** A failed optional market must not prevent the Minecraft world from loading. Never fall back to legacy cash. */
    public static void failStartup(Throwable error) {
        startupFailure = error;
        ClientExchangeData.Frame frame = new ClientExchangeData.Frame();
        frame.version = 1;
        frame.generation = UUID.randomUUID();
        frame.revision = 1;
        frame.tickValueCents = 1;
        frame.marginBps = 1000;
        frame.unavailable = true;
        unavailableFrame = frame;
        NHKantan.LOG.error(
            "Market startup disabled for this world; world loading continues. Check config and preserve finance files.",
            error);
    }

    public static void notifyUnavailable(EntityPlayerMP player) {
        ClientExchangeData.Frame frame = unavailableFrame;
        if (frame != null) ModNetwork.CHANNEL.sendTo(new ExchangeFrameMessage(frame), player);
        player.addChatMessage(new net.minecraft.util.ChatComponentTranslation("message.nhkantan.market_unavailable"));
    }

    public static void requestUnavailable(UUID owner) {
        unavailableRequests.offer(owner);
    }

    /** Main-thread dispatch for terminal requests received on the network thread. */
    public static void tickUnavailable() {
        if (!unavailable()) return;
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) return;
        for (int i = 0; i < 16; i++) {
            UUID owner = unavailableRequests.poll();
            if (owner == null) return;
            for (Object value : server.getConfigurationManager().playerEntityList) {
                EntityPlayerMP player = (EntityPlayerMP) value;
                if (player.getUniqueID()
                    .equals(owner)) notifyUnavailable(player);
            }
        }
    }

    public static void start(MinecraftServer server) {
        if (instance != null) throw new IllegalStateException("Realistic exchange is already running");
        clearStartupFailure();
        try {
            instance = new RealisticService(server);
            NHKantan.LOG.info("Realistic exchange ready; checkpoint restored or net-equity migration completed");
        } catch (IOException | RuntimeException error) {
            failStartup(error);
        }
    }

    public void tick() {
        if (!accepting) return;
        try {
            gameTick = Math.addExact(gameTick, 1);
            for (int i = 0; i < 16; i++) {
                UUID owner = openRequests.poll();
                if (owner == null) break;
                for (Object value : server.getConfigurationManager().playerEntityList) {
                    EntityPlayerMP player = (EntityPlayerMP) value;
                    if (player.getUniqueID()
                        .equals(owner) && com.yuelengm.nhkantan.item.ItemMarketTerminal.isHeldBy(player)) {
                        com.yuelengm.nhkantan.gui.ExchangeGui.open(player);
                    }
                }
            }
            runtime.clock(true, gameTick);
            if (publicPending != null && publicPending.isDone()) {
                ModNetwork.CHANNEL.sendToAll(new ExchangeFrameMessage(frame(InventoryTransfers.await(publicPending))));
                publicPending = null;
            }
            for (Map.Entry<UUID, CompletableFuture<ExchangeRuntime.PrivateSnapshot>> entry : new HashMap<>(
                privatePending).entrySet()) {
                if (!entry.getValue()
                    .isDone()) continue;
                ExchangeRuntime.PrivateSnapshot view = InventoryTransfers.await(entry.getValue());
                privateViews.put(entry.getKey(), new Gson().toJson(view));
                for (Object value : server.getConfigurationManager().playerEntityList) {
                    EntityPlayerMP player = (EntityPlayerMP) value;
                    if (player.getUniqueID()
                        .equals(entry.getKey())) sendNotices(player, view);
                }
                privatePending.remove(entry.getKey());
            }
            if (gameTick % 4 == 0) {
                if (publicPending == null) publicPending = runtime.publicSnapshot();
                for (Object value : server.getConfigurationManager().playerEntityList) {
                    EntityPlayerMP player = (EntityPlayerMP) value;
                    if (!privatePending.containsKey(player.getUniqueID())) {
                        privatePending.put(player.getUniqueID(), runtime.privateSnapshot(player.getUniqueID()));
                    }
                }
            }
            if (gameTick % 200 == 0) save(transfers.snapshot());
        } catch (Exception e) {
            accepting = false;
            runtime.clock(false, gameTick);
            NHKantan.LOG.error("Realistic exchange suspended; preserve checkpoint and escrow records", e);
        }
    }

    public void login(EntityPlayerMP player) throws IOException {
        requireActive();
        InventoryTransfers.await(
            runtime.ensureAccount(player.getUniqueID(), Math.multiplyExact(CurrencyConfig.initialBalance, 100L)));
        transfers.reconcile(player);
        ModNetwork.CHANNEL
            .sendTo(new ExchangeFrameMessage(frame(InventoryTransfers.await(runtime.publicSnapshot()))), player);
        ExchangeRuntime.PrivateSnapshot view = InventoryTransfers.await(runtime.privateSnapshot(player.getUniqueID()));
        privateViews.put(player.getUniqueID(), new Gson().toJson(view));
        noticeResendTick.remove(player.getUniqueID());
        lastNoticeSent.remove(player.getUniqueID());
        sendNotices(player, view);
    }

    private void sendNotices(EntityPlayerMP player, ExchangeRuntime.PrivateSnapshot view) {
        if (view.notifications.isEmpty()) return;
        UUID owner = player.getUniqueID();
        long newest = view.notifications.get(view.notifications.size() - 1).id;
        if (newest <= lastNoticeSent.getOrDefault(owner, 0L) && gameTick < noticeResendTick.getOrDefault(owner, 0L))
            return;
        ModNetwork.CHANNEL.sendTo(
            new com.yuelengm.nhkantan.network.MarketNoticeMessage(
                runtime.generation(),
                RealisticConfig.tickValueCents,
                view.notifications),
            player);
        lastNoticeSent.put(owner, newest);
        noticeResendTick.put(owner, gameTick + 100);
    }

    public void acknowledgeNotices(UUID generation, UUID authenticatedOwner, java.util.List<Long> ids) {
        runtime.acknowledgeNotices(generation, authenticatedOwner, ids);
    }

    private ClientExchangeData.Frame frame(Map<String, SimulatedInstrument.Snapshot> instruments) {
        ClientExchangeData.Frame frame = new ClientExchangeData.Frame();
        frame.version = 1;
        frame.generation = runtime.generation();
        frame.gameTick = gameTick;
        frame.revision = ++revision;
        frame.tickValueCents = RealisticConfig.tickValueCents;
        frame.marginBps = RealisticConfig.initialMarginBps;
        frame.commissionPpm = RealisticConfig.commissionPpm;
        frame.names = names;
        frame.instruments = instruments;
        if (gameTick % 200 < 5) {
            long volume = 0;
            for (SimulatedInstrument.Snapshot instrument : instruments.values()) volume += instrument.volume;
            NHKantan.LOG.info(
                "Realistic exchange snapshot: tick={}, instruments={}, cumulative matched volume={}",
                gameTick,
                instruments.size(),
                volume);
        }
        return frame;
    }

    /** Keep ModularUI's bounded string packet small: send only the selected expiry chain. */
    public String privateView(EntityPlayerMP player, String underlying, int day) {
        return com.yuelengm.nhkantan.market.view.OptionChainView.filter(privateView(player), underlying, day, gameTick);
    }

    public long balance(EntityPlayerMP player) {
        com.google.gson.JsonObject cached = new com.google.gson.JsonParser().parse(privateView(player))
            .getAsJsonObject();
        return cached.has("account") ? cached.getAsJsonObject("account")
            .get("cash")
            .getAsLong() : 0;
    }

    public String privateView(EntityPlayerMP player) {
        return privateViews.getOrDefault(player.getUniqueID(), "{}");
    }

    public CompletableFuture<OrderBook.Result> order(EntityPlayerMP player, UUID generation, long requestId,
        String instrument, OrderBook.Side side, long price, long quantity, boolean market, boolean reduceOnly) {
        requireActive();
        return runtime
            .submit(generation, player.getUniqueID(), requestId, instrument, side, price, quantity, market, reduceOnly);
    }

    public CompletableFuture<Boolean> closePosition(EntityPlayerMP player, UUID generation, long request, String id) {
        requireActive();
        return runtime.closePosition(generation, player.getUniqueID(), request, id);
    }

    public CompletableFuture<Boolean> cancel(EntityPlayerMP player, UUID generation, String instrument, long id) {
        requireActive();
        return runtime.cancel(generation, player.getUniqueID(), instrument, id);
    }

    public CompletableFuture<com.yuelengm.nhkantan.options.OptionMarket.Holding> buyOption(EntityPlayerMP player,
        UUID epoch, long request, String contract, long quote, long quantity, long maximumCost) {
        requireActive();
        return runtime.buyOption(epoch, player.getUniqueID(), request, contract, quote, quantity, maximumCost);
    }

    public CompletableFuture<com.yuelengm.nhkantan.options.OptionMarket.Holding> exitOption(EntityPlayerMP player,
        UUID generation, long request, long holding, long quantity, boolean exercise, long quote, long minimumPayout) {
        requireActive();
        return runtime
            .exitOption(generation, player.getUniqueID(), request, holding, quantity, exercise, quote, minimumPayout);
    }

    public CompletableFuture<Void> protect(EntityPlayerMP player, UUID generation, long requestId, String instrument,
        long quantity, long stop, long take) {
        requireActive();
        return runtime.protect(generation, player.getUniqueID(), requestId, instrument, quantity, stop, take);
    }

    public boolean exchange(EntityPlayerMP player, int item, int quantity, boolean withdraw) throws IOException {
        requireActive();
        boolean result = wallet.exchange(player, item, quantity, withdraw);
        privateViews.put(
            player.getUniqueID(),
            new Gson().toJson(InventoryTransfers.await(runtime.privateSnapshot(player.getUniqueID()))));
        return result;
    }

    private void requireActive() {
        if (!accepting) throw new IllegalStateException("Market is suspended");
    }

    public void save() throws IOException {
        save(transfers.snapshot());
    }

    private void save(Map<UUID, InventoryTransfers.Escrow> escrows) throws IOException {
        Saved saved = new Saved();
        saved.version = 1;
        saved.exchange = InventoryTransfers.await(runtime.checkpoint());
        saved.escrows = escrows;
        data.replace(new Gson().toJson(saved), true);
        store.save(data);
        RealisticSavedData verified = store.load();
        if (verified == null || verified.revision() != data.revision()
            || !verified.checkpoint()
                .equals(data.checkpoint())) {
            throw new IOException("Market checkpoint read-back failed");
        }
    }

    public static void stop() {
        RealisticService service = instance;
        instance = null;
        clearStartupFailure();
        if (service == null) return;
        service.accepting = false;
        service.runtime.clock(false, service.gameTick);
        try {
            service.save();
        } catch (IOException | RuntimeException e) {
            NHKantan.LOG.error("Unable to save realistic exchange at shutdown; checkpoint retained", e);
        } finally {
            try {
                service.runtime.close();
            } catch (RuntimeException e) {
                NHKantan.LOG.error("Unable to stop realistic worker cleanly", e);
            }
        }
    }

    private static long ticks(double price, RoundingMode rounding) {
        return BigDecimal.valueOf(price)
            .movePointRight(2)
            .divide(BigDecimal.valueOf(RealisticConfig.tickValueCents), 0, rounding)
            .longValueExact();
    }
}
