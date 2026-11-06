package com.yuelengm.nhkantan.block;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;
import net.minecraftforge.common.util.ForgeDirection;

import com.cleanroommc.modularui.api.IGuiHolder;
import com.cleanroommc.modularui.api.drawable.IIcon;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.factory.PosGuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.value.BoolValue;
import com.cleanroommc.modularui.value.sync.DoubleSyncValue;
import com.cleanroommc.modularui.value.sync.IntSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.StringSyncValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.SliderWidget;
import com.cleanroommc.modularui.widgets.ToggleButton;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.gtnewhorizon.gtnhlib.geometry.Orientation;
import com.yuelengm.nhkantan.NHKantan;
import com.yuelengm.nhkantan.gui.util.Widgets;
import com.yuelengm.nhkantan.market.view.ClientMarketData;
import com.yuelengm.nhkantan.market.view.PriceFormat;
import com.yuelengm.nhkantan.market.view.StockView;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Tile entity of the market display. The display block itself is always the bottom-left cell of the big screen;
 * this tile stores the big screen size (in blocks), the screen depth (in pixels) and hosts the market GUI.
 */
public class TileMarketDisplay extends TileEntity implements IGuiHolder<PosGuiData> {

    public static final int MIN_SIZE = 1;
    public static final int MAX_SIZE = 16;

    /** Screen depth range in pixels (1/16 block), measured from the mounting surface. */
    public static final double MIN_DEPTH = 0.0D;
    public static final double MAX_DEPTH = 16.0D;
    public static final double DEFAULT_DEPTH = 0.5D;

    private static final String NBT_WIDTH = "ScreenWidth";
    private static final String NBT_HEIGHT = "ScreenHeight";
    private static final String NBT_DEPTH = "ScreenDepth";
    private static final String NBT_STOCK = "SelectedStock";

    private static final int PANEL_WIDTH = 220;
    private static final int PANEL_HEIGHT = 240;
    private static final int STOCK_LIST_HEIGHT = 90;
    private static final int STOCK_ROW_HEIGHT = 20;

    private int screenWidth = MIN_SIZE;
    private int screenHeight = MIN_SIZE;
    private double screenDepth = DEFAULT_DEPTH;
    private String selectedStockId = "";

    public int getScreenWidth() {
        return screenWidth;
    }

    public int getScreenHeight() {
        return screenHeight;
    }

    /** Screen depth in pixels (1/16 block) from the mounting surface. */
    public double getScreenDepth() {
        return screenDepth;
    }

    /** Stable id of the stock shown on the screen; an empty value falls back to the first running stock. */
    public String getSelectedStockId() {
        return selectedStockId;
    }

    public void setScreenWidth(int width) {
        int w = clampSize(width);
        if (w == screenWidth) return;
        screenWidth = w;
        onScreenChanged();
    }

    public void setScreenHeight(int height) {
        int h = clampSize(height);
        if (h == screenHeight) return;
        screenHeight = h;
        onScreenChanged();
    }

    public void setScreenDepth(double depth) {
        double d = clampDepth(depth);
        if (d == screenDepth) return;
        screenDepth = d;
        onScreenChanged();
    }

    public void setSelectedStockId(String stockId) {
        String id = stockId == null ? "" : stockId;
        if (id.equals(selectedStockId)) return;
        selectedStockId = id;
        onScreenChanged();
    }

    public static int clampSize(int size) {
        return MathHelper.clamp_int(size, MIN_SIZE, MAX_SIZE);
    }

    public static double clampDepth(double depth) {
        if (Double.isNaN(depth)) return DEFAULT_DEPTH;
        return Math.max(MIN_DEPTH, Math.min(MAX_DEPTH, depth));
    }

    /**
     * Server: persist and resend the description packet to all watching clients.
     * Client: refresh the render bounds, see {@link #refreshRenderBounds()}.
     */
    private void onScreenChanged() {
        if (worldObj == null) return;
        if (worldObj.isRemote) {
            refreshRenderBounds();
        } else {
            markDirty();
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        }
    }

    /**
     * Client only. Makes renderers pick up a changed {@link #getRenderBoundingBox()}.
     * <ul>
     * <li>Angelica caches the box per tile and only recomputes it when position, block or {@code blockMetadata}
     * changes. {@link #updateContainingBlockInfo()} resets {@code blockMetadata} to -1, which invalidates that cache;
     * without this the per-frame frustum test keeps using the old (smaller) box.</li>
     * <li>Angelica decides "culled with its section" vs "global" only when the section is meshed, so a rebuild is
     * requested as well.</li>
     * </ul>
     */
    private void refreshRenderBounds() {
        updateContainingBlockInfo();
        worldObj.markBlockRangeForRenderUpdate(xCoord, yCoord, zCoord, xCoord, yCoord, zCoord);
    }

    public Orientation getOrientation() {
        return MarketDisplayOrientation.fromMeta(getBlockMetadata());
    }

    @Override
    public boolean canUpdate() {
        return false;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Persistence & sync
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        writeScreenData(tag);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        readScreenData(tag);
    }

    private void writeScreenData(NBTTagCompound tag) {
        tag.setInteger(NBT_WIDTH, screenWidth);
        tag.setInteger(NBT_HEIGHT, screenHeight);
        tag.setDouble(NBT_DEPTH, screenDepth);
        tag.setString(NBT_STOCK, selectedStockId);
    }

    private void readScreenData(NBTTagCompound tag) {
        screenWidth = tag.hasKey(NBT_WIDTH) ? clampSize(tag.getInteger(NBT_WIDTH)) : MIN_SIZE;
        screenHeight = tag.hasKey(NBT_HEIGHT) ? clampSize(tag.getInteger(NBT_HEIGHT)) : MIN_SIZE;
        screenDepth = tag.hasKey(NBT_DEPTH) ? clampDepth(tag.getDouble(NBT_DEPTH)) : DEFAULT_DEPTH;
        selectedStockId = tag.hasKey(NBT_STOCK) ? tag.getString(NBT_STOCK) : "";
    }

    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound tag = new NBTTagCompound();
        writeScreenData(tag);
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, tag);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        readScreenData(pkt.func_148857_g());
        // The packet may arrive after the section was meshed with stale bounds; rebuild so culling is re-evaluated
        if (worldObj != null) {
            refreshRenderBounds();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Rendering helpers
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Exact bounds of the big screen: from this block along "right" by (width - 1) blocks and along "up" by
     * (height - 1) blocks. Screen depth stays within this block's cell, so no extra padding is needed.
     * When these bounds leave the 16^3 chunk section, Angelica puts the TESR on its global list, which is not
     * dropped with the origin section.
     */
    @Override
    @SideOnly(Side.CLIENT)
    public AxisAlignedBB getRenderBoundingBox() {
        Orientation orientation = getOrientation();
        ForgeDirection right = MarketDisplayOrientation.right(orientation);
        ForgeDirection up = MarketDisplayOrientation.up(orientation);
        int ex = right.offsetX * (screenWidth - 1) + up.offsetX * (screenHeight - 1);
        int ey = right.offsetY * (screenWidth - 1) + up.offsetY * (screenHeight - 1);
        int ez = right.offsetZ * (screenWidth - 1) + up.offsetZ * (screenHeight - 1);
        return AxisAlignedBB.getBoundingBox(
            xCoord + Math.min(0, ex),
            yCoord + Math.min(0, ey),
            zCoord + Math.min(0, ez),
            xCoord + 1 + Math.max(0, ex),
            yCoord + 1 + Math.max(0, ey),
            zCoord + 1 + Math.max(0, ez));
    }

    @Override
    @SideOnly(Side.CLIENT)
    public double getMaxRenderDistanceSquared() {
        return 128.0D * 128.0D;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // GUI
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    @SideOnly(Side.CLIENT)
    public ModularScreen createScreen(PosGuiData data, ModularPanel mainPanel) {
        return new ModularScreen(NHKantan.MODID, mainPanel);
    }

    @Override
    public ModularPanel buildUI(PosGuiData data, PanelSyncManager syncManager, UISettings settings) {
        // allowC2S is required: MUI2 drops client-to-server updates by default, which left the server value at 1
        IntSyncValue widthSync = new IntSyncValue(this::getScreenWidth, this::setScreenWidth).allowC2S();
        IntSyncValue heightSync = new IntSyncValue(this::getScreenHeight, this::setScreenHeight).allowC2S();
        DoubleSyncValue depthSync = new DoubleSyncValue(this::getScreenDepth, this::setScreenDepth).allowC2S();
        StringSyncValue stockSync = new StringSyncValue(this::getSelectedStockId, this::setSelectedStockId).allowC2S();
        syncManager.syncValue("screen_width", 0, widthSync);
        syncManager.syncValue("screen_height", 0, heightSync);
        syncManager.syncValue("screen_depth", 0, depthSync);
        syncManager.syncValue("screen_stock", 0, stockSync);

        ModularPanel panel = ModularPanel.defaultPanel("nhkantan_market_display", PANEL_WIDTH, PANEL_HEIGHT);
        panel.child(
            Flow.column()
                .widthRel(1f)
                .heightRel(1f)
                .padding(7)
                .childPadding(4)
                .child(
                    IKey.lang("gui.nhkantan.market_display.title")
                        .asWidget()
                        .left(0))
                .child(sizeSlider("gui.nhkantan.market_display.width", widthSync))
                .child(sizeSlider("gui.nhkantan.market_display.height", heightSync))
                .child(depthField(depthSync))
                .child(
                    IKey.lang("gui.nhkantan.market_display.stock")
                        .asWidget()
                        .left(0))
                .child(stockList(stockSync).expanded()))
            .child(ButtonWidget.panelCloseButton())
            .padding(Widgets.PADDING);
        return panel;
    }

    /** Scrollable stock list selecting the stock rendered by the in-world screen. */
    private static ListWidget<IWidget, ?> stockList(StringSyncValue selected) {
        ListWidget<IWidget, ?> list = new ListWidget<>().widthRel(1f)
            .height(STOCK_LIST_HEIGHT)
            .childSeparator(IIcon.EMPTY_2PX);
        if (ClientMarketData.INSTANCE.stocks()
            .isEmpty()) {
            list.child(
                IKey.lang("gui.nhkantan.market.no_data")
                    .asWidget()
                    .widthRel(1f));
            return list;
        }
        for (StockView stock : ClientMarketData.INSTANCE.stocks()) {
            list.child(stockButton(stock, selected));
        }
        return list;
    }

    private static ToggleButton stockButton(StockView stock, StringSyncValue selected) {
        return new ToggleButton().widthRel(1f)
            .height(STOCK_ROW_HEIGHT)
            .value(
                new BoolValue.Dynamic(
                    () -> stockSelected(stock.id(), selected.getStringValue()),
                    value -> { if (value) selected.setStringValue(stock.id(), true, true); }))
            .overlay(IKey.str(stock.name()));
    }

    private static boolean stockSelected(String stockId, String selectedId) {
        if (selectedId != null && !selectedId.isEmpty()) {
            StockView selected = ClientMarketData.INSTANCE.get(selectedId);
            if (selected != null) return stockId.equals(selected.id());
        }
        StockView first = ClientMarketData.INSTANCE.first();
        return first != null && stockId.equals(first.id());
    }

    private static String formatPrice(StockView stock) {
        double change = stock.latestChange();
        EnumChatFormatting color = change > 0.0D ? EnumChatFormatting.DARK_GREEN
            : change < 0.0D ? EnumChatFormatting.RED : EnumChatFormatting.DARK_GRAY;
        return PriceFormat.price(stock.price()) + " " + color + PriceFormat.change(change);
    }

    /** Label with the current value, followed by a 1-step slider over [MIN_SIZE, MAX_SIZE]. */
    private static Flow sizeSlider(String langKey, IntSyncValue value) {
        return Flow.column()
            .widthRel(1f)
            .coverChildrenHeight()
            .childPadding(2)
            .child(
                IKey.lang(langKey, () -> new Object[] { value.getIntValue() })
                    .asWidget()
                    .left(0))
            .child(
                new SliderWidget().widthRel(1f)
                    .height(14)
                    .bounds(MIN_SIZE, MAX_SIZE)
                    .stopper(1)
                    .value(value));
    }

    /** Label followed by a free-form number field; committed on Enter or focus loss. */
    private static Flow depthField(DoubleSyncValue value) {
        return Flow.row()
            .widthRel(1f)
            .height(16)
            .childPadding(4)
            .child(
                IKey.lang("gui.nhkantan.market_display.depth")
                    .asWidget()
                    .widthRel(0.55f))
            .child(
                new TextFieldWidget().widthRel(0.4f)
                    .height(16)
                    .numbersDouble(MIN_DEPTH, MAX_DEPTH)
                    .defaultNumber(DEFAULT_DEPTH)
                    .value(value));
    }
}
