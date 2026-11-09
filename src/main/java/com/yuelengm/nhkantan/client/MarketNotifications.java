package com.yuelengm.nhkantan.client;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import com.yuelengm.nhkantan.item.ModItems;
import com.yuelengm.nhkantan.market.order.MarketNotices;
import com.yuelengm.nhkantan.market.view.ClientExchangeData;
import com.yuelengm.nhkantan.network.MarketNoticeAckMessage;
import com.yuelengm.nhkantan.network.ModNetwork;
import com.yuelengm.nhkantan.wallet.CurrencyFormat;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Main-thread delivery to GTNH AE's shared notification queue; no financial state is changed here. */
@SideOnly(Side.CLIENT)
public final class MarketNotifications {

    public static final MarketNotifications INSTANCE = new MarketNotifications();
    private static final long DURATION = 5000;
    private final ConcurrentLinkedQueue<Batch> incoming = new ConcurrentLinkedQueue<>();
    private final Map<Long, Entry> pending = new LinkedHashMap<>();
    private final LinkedHashSet<Long> completed = new LinkedHashSet<>();
    private volatile boolean reset;
    private UUID generation;
    private long nextNotificationAt;

    private static final class Batch {

        private final UUID generation;
        private final int tickValue;
        private final List<MarketNotices.Notice> notices;

        private Batch(UUID generation, int tickValue, List<MarketNotices.Notice> notices) {
            this.generation = generation;
            this.tickValue = tickValue;
            this.notices = new ArrayList<>(notices);
        }
    }

    private static final class Entry {

        private final MarketNotices.Notice notice;
        private final int tickValue;

        private Entry(MarketNotices.Notice notice, int tickValue) {
            this.notice = notice;
            this.tickValue = tickValue;
        }
    }

    private MarketNotifications() {}

    public void receive(UUID generation, int tickValue, List<MarketNotices.Notice> notices) {
        if (incoming.size() < 32) incoming.offer(new Batch(generation, tickValue, notices));
    }

    /** Network disconnect callbacks request a main-thread reset rather than mutating the render queue. */
    public void disconnect() {
        incoming.clear();
        reset = true;
    }

    private void finish(long id, List<Long> receipts) {
        completed.add(id);
        while (completed.size() > 512) completed.remove(
            completed.iterator()
                .next());
        receipts.add(id);
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (reset) {
            reset = false;
            pending.clear();
            completed.clear();
            nextNotificationAt = 0;
            generation = null;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.theWorld == null || minecraft.thePlayer == null) return;
        List<Long> receipts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Batch batch = incoming.poll();
            if (batch == null) break;
            if (!batch.generation.equals(generation)) {
                generation = batch.generation;
                pending.clear();
                completed.clear();
                nextNotificationAt = 0;
            }
            for (MarketNotices.Notice notice : batch.notices) {
                if (completed.contains(notice.id)) receipts.add(notice.id);
                else if (pending.size() < 64) pending.putIfAbsent(notice.id, new Entry(notice, batch.tickValue));
            }
        }
        long now = Minecraft.getSystemTime();
        if (now >= nextNotificationAt && !pending.isEmpty()) {
            Iterator<Entry> next = pending.values()
                .iterator();
            Entry entry = next.next();
            show(minecraft, entry);
            next.remove();
            nextNotificationAt = now + DURATION;
            finish(entry.notice.id, receipts);
        }
        // Receipts follow delivery; being on the trading screen never suppresses a notification.
        for (int start = 0; generation != null && start < receipts.size(); start += 16) {
            ModNetwork.CHANNEL.sendToServer(
                new MarketNoticeAckMessage(generation, receipts.subList(start, Math.min(start + 16, receipts.size()))));
        }
    }

    private void show(Minecraft minecraft, Entry entry) {
        String prefix = "notification.nhkantan." + entry.notice.kind.name()
            .toLowerCase(java.util.Locale.ROOT);
        String title = StatCollector.translateToLocal(prefix + ".title");
        String body = StatCollector
            .translateToLocalFormatted(prefix + ".body", frameName(entry.notice.instrument), entry.notice.quantity);
        String value = entry.notice.kind == MarketNotices.Kind.OPTION_EXPIRED
            ? CurrencyFormat.amount(entry.notice.payoutCents)
            : BigDecimal.valueOf(entry.notice.priceTicks)
                .multiply(BigDecimal.valueOf(entry.tickValue))
                .movePointLeft(2)
                .stripTrailingZeros()
                .toPlainString();
        String description = body + " · " + StatCollector.translateToLocalFormatted(prefix + ".detail", value);
        if (!AeMarketNotifications.show(new ItemStack(ModItems.marketTerminal), title, description)) {
            // Standalone installations without GTNH AE still receive the event rather than silently losing it.
            minecraft.thePlayer.addChatMessage(new ChatComponentText(title + ": " + description));
        }
        minecraft.getSoundHandler()
            .playSound(
                PositionedSoundRecord.func_147674_a(
                    new ResourceLocation("random.orb"),
                    entry.notice.kind == MarketNotices.Kind.STOP_TRIGGERED ? 0.65F : 1.2F));
    }

    private String frameName(String id) {
        ClientExchangeData.Frame frame = ClientExchangeData.get();
        return frame == null ? id : frame.names.getOrDefault(id, id);
    }
}
