package com.yuelengm.nhkantan;

import java.util.function.Supplier;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.widget.Widget;
import com.yuelengm.nhkantan.block.ModBlocks;
import com.yuelengm.nhkantan.compat.GtnhCoinProvider;
import com.yuelengm.nhkantan.config.ModConfigs;
import com.yuelengm.nhkantan.config.RealisticConfig;
import com.yuelengm.nhkantan.gui.ExchangeGui;
import com.yuelengm.nhkantan.gui.WalletGui;
import com.yuelengm.nhkantan.item.ModItems;
import com.yuelengm.nhkantan.market.server.MarketEventHandler;
import com.yuelengm.nhkantan.market.server.MarketService;
import com.yuelengm.nhkantan.market.server.RealisticService;
import com.yuelengm.nhkantan.market.view.StockView;
import com.yuelengm.nhkantan.network.ModNetwork;
import com.yuelengm.nhkantan.recipe.ModRecipes;
import com.yuelengm.nhkantan.trading.TradingService;
import com.yuelengm.nhkantan.wallet.WalletService;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        ModConfigs.register();
        ModBlocks.register();
        ModItems.register();
        ModRecipes.register();
        ModNetwork.register();
        WalletGui.register();
        ExchangeGui.register();
        FMLCommonHandler.instance()
            .bus()
            .register(new MarketEventHandler());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new MarketEventHandler());
        NHKantan.LOG.info("NH kantan version " + Tags.VERSION + " initializing.");
    }

    public void init(FMLInitializationEvent event) {}

    public void postInit(FMLPostInitializationEvent event) {
        GtnhCoinProvider.registerIfLoaded();
    }

    public void serverStarting(FMLServerStartingEvent event) {
        RealisticService.clearStartupFailure();
        try {
            java.nio.file.Path checkpoint = event.getServer()
                .worldServerForDimension(0)
                .getSaveHandler()
                .getWorldDirectory()
                .toPath()
                .resolve("nhkantan/realistic.dat");
            if (RealisticConfig.enabled) {
                RealisticService.start(event.getServer());
                return;
            }
            if (java.nio.file.Files.exists(checkpoint)) {
                RealisticService.failStartup(
                    new IllegalStateException(
                        "This world has migrated to realistic trading; re-enable realistic.enabled. Legacy trading was not started."));
                return;
            }
            // Market definitions are read here, on the server only; clients only ever receive public snapshots
            MarketService.start(event.getServer());
            WalletService.start(event.getServer());
            TradingService.start(event.getServer());
        } catch (RuntimeException error) {
            RealisticService.failStartup(error);
        }
    }

    public void serverStopped(FMLServerStoppedEvent event) {
        RealisticService.stop();
        MarketService.stop();
        WalletService.stop();
        TradingService.stop();
    }

    /**
     * Creates the K-line chart widget used by market GUIs. The chart draws with client-only classes, so the common
     * proxy returns an empty placeholder and only the client proxy creates the real widget.
     */
    public Widget<?> createStockChart(Supplier<StockView> stock,
        Supplier<java.util.List<com.yuelengm.nhkantan.market.view.ChartLine>> lines) {
        return IKey.EMPTY.asWidget();
    }

    public Widget<?> createStockChart(Supplier<StockView> stock) {
        return IKey.EMPTY.asWidget();
    }
}
