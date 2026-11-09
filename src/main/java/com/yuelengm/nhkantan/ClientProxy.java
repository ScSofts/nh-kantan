package com.yuelengm.nhkantan;

import java.util.function.Supplier;

import com.cleanroommc.modularui.widget.Widget;
import com.yuelengm.nhkantan.block.TileMarketDisplay;
import com.yuelengm.nhkantan.client.ClientMarketEventHandler;
import com.yuelengm.nhkantan.client.gui.StockChartWidget;
import com.yuelengm.nhkantan.client.render.MarketDisplayRenderer;
import com.yuelengm.nhkantan.market.view.StockView;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;

public class ClientProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        ClientRegistry.bindTileEntitySpecialRenderer(TileMarketDisplay.class, new MarketDisplayRenderer());
        FMLCommonHandler.instance()
            .bus()
            .register(new ClientMarketEventHandler());
    }

    @Override
    public Widget<?> createStockChart(Supplier<StockView> stock,
        Supplier<java.util.List<com.yuelengm.nhkantan.market.view.ChartLine>> lines) {
        return new StockChartWidget(stock, lines);
    }

    @Override
    public Widget<?> createStockChart(Supplier<StockView> stock) {
        return new StockChartWidget(stock);
    }

}
