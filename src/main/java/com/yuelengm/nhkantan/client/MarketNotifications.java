package com.yuelengm.nhkantan.client;

import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
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

/** Forge client tick/render notifications. No financial state or screen is changed by this overlay. */
@SideOnly(Side.CLIENT)
public final class MarketNotifications {

    public static final MarketNotifications INSTANCE = new MarketNotifications();
    private static final long DURATION = 5000;
    private final ConcurrentLinkedQueue<Batch> incoming = new ConcurrentLinkedQueue<>();
    private final Map<Long, Entry> pending = new LinkedHashMap<>();
    private final LinkedHashSet<Long> completed = new LinkedHashSet<>();
    private final RenderItem itemRenderer = new RenderItem();
    private volatile boolean reset;
    private UUID generation;
    private Entry active;
    private long shownAt;
    private WeakReference<ModularPanel> panel = new WeakReference<>(null);
    private IntSupplier page;
    private Supplier<String> instrument;
    private IntSupplier expiryDay;

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

    public void track(ModularPanel panel, IntSupplier page, Supplier<String> instrument, IntSupplier expiryDay) {
        this.panel = new WeakReference<>(panel);
        this.page = page;
        this.instrument = instrument;
        this.expiryDay = expiryDay;
    }

    /** Network disconnect callbacks request a main-thread reset rather than mutating the render queue. */
    public void disconnect() {
        incoming.clear();
        reset = true;
    }

    private boolean visible(MarketNotices.Notice notice) {
        ModularScreen current = ModularScreen.getCurrent();
        ModularPanel tracked = panel.get();
        if (current == null || tracked == null
            || !tracked.isOpen()
            || current.getMainPanel() != tracked
            || current.getPanelManager()
                .getTopMostPanel() != tracked
            || page == null
            || instrument == null
            || !notice.instrument.equals(instrument.get())) return false;
        if (notice.kind != MarketNotices.Kind.OPTION_EXPIRED) return page.getAsInt() == 0;
        // Expiry belongs to the near-expiry tab, including the instant the series crosses its expiry boundary.
        return page.getAsInt() == 2 && expiryDay != null && expiryDay.getAsInt() == 1;
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
            active = null;
            generation = null;
            panel.clear();
            page = null;
            instrument = null;
            expiryDay = null;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        ClientExchangeData.Frame frame = ClientExchangeData.get();
        if (minecraft.theWorld == null || minecraft.thePlayer == null || frame == null) return;
        List<Long> receipts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Batch batch = incoming.poll();
            if (batch == null) break;
            if (!frame.generation.equals(batch.generation)) continue;
            if (!batch.generation.equals(generation)) {
                generation = batch.generation;
                pending.clear();
                completed.clear();
                active = null;
            }
            for (MarketNotices.Notice notice : batch.notices) {
                if (completed.contains(notice.id)) receipts.add(notice.id);
                else if (pending.size() < 64) pending.putIfAbsent(notice.id, new Entry(notice, batch.tickValue));
            }
        }
        Iterator<Map.Entry<Long, Entry>> iterator = pending.entrySet()
            .iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next()
                .getValue();
            if (visible(entry.notice)) {
                finish(entry.notice.id, receipts);
                iterator.remove();
            }
        }
        long now = Minecraft.getSystemTime();
        if (active != null && (now - shownAt >= DURATION || visible(active.notice))) active = null;
        if (active == null && !pending.isEmpty()) {
            Iterator<Entry> next = pending.values()
                .iterator();
            active = next.next();
            next.remove();
            shownAt = now;
            finish(active.notice.id, receipts);
            minecraft.getSoundHandler()
                .playSound(
                    PositionedSoundRecord.func_147674_a(
                        new ResourceLocation("random.orb"),
                        active.notice.kind == MarketNotices.Kind.STOP_TRIGGERED ? 0.65F : 1.2F));
        }
        // Suppressed notices are acknowledged too, so leaving the terminal does not replay them later.
        for (int start = 0; generation != null && start < receipts.size(); start += 16) {
            ModNetwork.CHANNEL.sendToServer(
                new MarketNoticeAckMessage(generation, receipts.subList(start, Math.min(start + 16, receipts.size()))));
        }
    }

    @SubscribeEvent
    public void render(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || active == null || reset) return;
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || minecraft.theWorld == null || visible(active.notice)) return;
        ScaledResolution resolution = new ScaledResolution(minecraft, minecraft.displayWidth, minecraft.displayHeight);
        int width = Math.min(270, resolution.getScaledWidth() - 16);
        if (width < 100) return;
        long age = Minecraft.getSystemTime() - shownAt;
        float animation = Math.min(1F, Math.min(age / 200F, (DURATION - age) / 200F));
        if (animation <= 0) return;
        int x = resolution.getScaledWidth() - width - 8 + Math.round((1 - animation) * (width + 8));
        int y = 8;
        int mode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, resolution.getScaledWidth(), resolution.getScaledHeight(), 0, 1000, 3000);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glTranslatef(0, 0, -2000);
        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_LIGHTING);
            int accent = active.notice.kind == MarketNotices.Kind.STOP_TRIGGERED ? 0xFFFF6C86
                : active.notice.kind == MarketNotices.Kind.TAKE_FILLED ? 0xFF54DFA7 : 0xFFF3C978;
            Gui.drawRect(x, y, x + width, y + 58, 0xF00D192B);
            Gui.drawRect(x, y, x + 3, y + 58, accent);
            Gui.drawRect(x + 3, y, x + width, y + 1, 0xFF385474);
            String prefix = "notification.nhkantan." + active.notice.kind.name()
                .toLowerCase(java.util.Locale.ROOT);
            minecraft.fontRenderer.drawString(StatCollector.translateToLocal(prefix + ".title"), x + 30, y + 7, accent);
            String name = frameName(active.notice.instrument);
            String body = StatCollector.translateToLocalFormatted(prefix + ".body", name, active.notice.quantity);
            minecraft.fontRenderer
                .drawString(minecraft.fontRenderer.trimStringToWidth(body, width - 39), x + 30, y + 23, 0xD8E6F8);
            String value;
            if (active.notice.kind == MarketNotices.Kind.OPTION_EXPIRED)
                value = CurrencyFormat.amount(active.notice.payoutCents);
            else value = BigDecimal.valueOf(active.notice.priceTicks)
                .multiply(BigDecimal.valueOf(active.tickValue))
                .movePointLeft(2)
                .stripTrailingZeros()
                .toPlainString();
            String detail = StatCollector.translateToLocalFormatted(prefix + ".detail", value);
            minecraft.fontRenderer
                .drawString(minecraft.fontRenderer.trimStringToWidth(detail, width - 39), x + 30, y + 39, 0x9EB4D1);
            RenderHelper.enableGUIStandardItemLighting();
            itemRenderer.renderItemAndEffectIntoGUI(
                minecraft.fontRenderer,
                minecraft.getTextureManager(),
                new ItemStack(ModItems.marketTerminal),
                x + 8,
                y + 8);
            RenderHelper.disableStandardItemLighting();
        } finally {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(mode);
            GL11.glPopAttrib();
        }
    }

    private String frameName(String id) {
        ClientExchangeData.Frame frame = ClientExchangeData.get();
        return frame == null ? id : frame.names.getOrDefault(id, id);
    }
}
