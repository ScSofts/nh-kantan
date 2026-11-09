package com.yuelengm.nhkantan.market.server;

import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * FML bus hooks driving the market service: ticking and initial sync of joining players.
 */
public class MarketEventHandler {

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        RealisticService realistic = RealisticService.get();
        if (realistic != null) {
            realistic.tick();
            return;
        }
        MarketService service = MarketService.get();
        if (service != null) service.tick();
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        RealisticService realistic = RealisticService.get();
        if (realistic != null && event.player instanceof EntityPlayerMP player) {
            try {
                realistic.login(player);
            } catch (java.io.IOException e) {
                com.yuelengm.nhkantan.NHKantan.LOG
                    .error("Unable to initialize exchange account or reconcile inventory", e);
            }
            return;
        }
        MarketService service = MarketService.get();
        if (service != null && event.player instanceof EntityPlayerMP player) {
            service.sendSnapshot(player);
        }
    }

    @SubscribeEvent
    public void onWorldSave(net.minecraftforge.event.world.WorldEvent.Save event) {
        if (event.world.isRemote || event.world.provider.dimensionId != 0) return;
        RealisticService service = RealisticService.get();
        if (service == null) return;
        try {
            service.save();
        } catch (java.io.IOException e) {
            com.yuelengm.nhkantan.NHKantan.LOG.error("Unable to checkpoint realistic exchange", e);
        }
    }

    @SubscribeEvent
    public void onWorldUnload(net.minecraftforge.event.world.WorldEvent.Unload event) {
        if (!event.world.isRemote && event.world.provider.dimensionId == 0) RealisticService.stop();
    }
}
