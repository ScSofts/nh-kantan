package com.yuelengm.nhkantan.client;

import com.yuelengm.nhkantan.market.view.ClientExchangeData;
import com.yuelengm.nhkantan.market.view.ClientMarketData;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

/**
 * Drops client market data when leaving a server so stale prices never leak into the next session.
 */
public class ClientMarketEventHandler {

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        ClientMarketData.INSTANCE.clear();
        ClientExchangeData.clear();
        MarketNotifications.INSTANCE.disconnect();
    }
}
