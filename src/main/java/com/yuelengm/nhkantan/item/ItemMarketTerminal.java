package com.yuelengm.nhkantan.item;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import com.cleanroommc.modularui.api.IGuiHolder;
import com.cleanroommc.modularui.api.drawable.IIcon;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.factory.GuiFactories;
import com.cleanroommc.modularui.factory.PlayerInventoryGuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.BoolValue;
import com.cleanroommc.modularui.value.DoubleValue;
import com.cleanroommc.modularui.value.IntValue;
import com.cleanroommc.modularui.value.sync.InteractionSyncHandler;
import com.cleanroommc.modularui.value.sync.LongSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.StringSyncValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.SliderWidget;
import com.cleanroommc.modularui.widgets.ToggleButton;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.yuelengm.nhkantan.NHKantan;
import com.yuelengm.nhkantan.gui.WalletGui;
import com.yuelengm.nhkantan.gui.util.GuiActions;
import com.yuelengm.nhkantan.gui.util.Widgets;
import com.yuelengm.nhkantan.market.view.ClientMarketData;
import com.yuelengm.nhkantan.market.view.PriceFormat;
import com.yuelengm.nhkantan.market.view.StockView;
import com.yuelengm.nhkantan.trading.Direction;
import com.yuelengm.nhkantan.trading.InstrumentRuleResolver;
import com.yuelengm.nhkantan.trading.InstrumentRules;
import com.yuelengm.nhkantan.trading.Position;
import com.yuelengm.nhkantan.trading.PositionCodec;
import com.yuelengm.nhkantan.trading.TradingMath;
import com.yuelengm.nhkantan.trading.TradingService;
import com.yuelengm.nhkantan.util.MathUtils;
import com.yuelengm.nhkantan.wallet.CurrencyFormat;
import com.yuelengm.nhkantan.wallet.Money;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Handheld market terminal. Right-clicking opens the market GUI: a stock list and the K-line chart of the selected
 * stock, both fed by the shared client market data. The selected stock also drives the order form on the right and the
 * position list below the chart; trading requests are validated and executed by {@link TradingService} on the server.
 */
public class ItemMarketTerminal extends Item implements IGuiHolder<PlayerInventoryGuiData> {

    private static final int PANEL_WIDTH = 560;
    private static final int PANEL_HEIGHT = 240;
    private static final int PADDING = 7;
    private static final int GAP = 4;
    private static final int TITLE_HEIGHT = 14;
    private static final int CLOSE_BUTTON_SPACE = 12;
    private static final int HEADER_BUTTON_WIDTH = 40;
    private static final int LIST_WIDTH = 92;
    private static final int FORM_WIDTH = 170;
    private static final int BUTTON_HEIGHT = 22;
    private static final int SMALL_BUTTON_HEIGHT = 14;
    private static final int STEP_BUTTON_WIDTH = 14;
    private static final int MAX_MARGIN = 999_999_999;
    private static final int SHIFT_STEP = 10;
    private static final String OPEN_ACTION = "trading_open";
    private static final String CLOSE_ACTION = "trading_close";

    public ItemMarketTerminal() {
        this.setUnlocalizedName("nhkantan.market_terminal");
        this.setTextureName("nhkantan:market_terminal");
        this.setCreativeTab(CreativeTabs.tabTools);
        this.setMaxStackSize(1);
    }

    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        if (!world.isRemote) {
            GuiFactories.playerInventory()
                .openFromMainHand(player);
        }
        return stack;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public ModularScreen createScreen(PlayerInventoryGuiData data, ModularPanel mainPanel) {
        return new ModularScreen(NHKantan.MODID, mainPanel);
    }

    @Override
    public ModularPanel buildUI(PlayerInventoryGuiData data, PanelSyncManager syncManager, UISettings settings) {
        EntityPlayer player = data.getPlayer();
        LongSyncValue balance = WalletGui.balanceValue(player);
        syncManager.syncValue(WalletGui.BALANCE_SYNC_KEY, balance);
        InteractionSyncHandler openWallet = GuiActions.serverClick(() -> openWallet(player));
        TradingState state = new TradingState(player, syncManager);

        ModularPanel panel = ModularPanel.defaultPanel("nhkantan_market_terminal", PANEL_WIDTH, PANEL_HEIGHT);
        Flow column = Flow.column()
            .widthRel(1f)
            .heightRel(1f)
            .padding(PADDING)
            .childPadding(GAP)
            .child(header(balance, openWallet, state));
        // Market data only exists on the client; the server-side copy of the panel carries no market widgets
        if (data.getPlayer().worldObj.isRemote) {
            column.child(marketView(state));
        }
        return panel.child(column)
            .child(ButtonWidget.panelCloseButton());
    }

    /** Server: switches from the terminal to the wallet window. */
    private static void openWallet(EntityPlayer player) {
        if (player instanceof EntityPlayerMP serverPlayer) WalletGui.open(serverPlayer);
    }

    /** Whether the player holds a market terminal in the main hand; required to keep market GUIs open. */
    public static boolean isHeldBy(EntityPlayer player) {
        ItemStack held = player.getHeldItem();
        return held != null && held.getItem() instanceof ItemMarketTerminal;
    }

    /** Title on the left; balance and the wallet button on the right, clear of the close button. */
    private static Flow header(LongSyncValue balance, InteractionSyncHandler openWallet, TradingState state) {
        int width = PANEL_WIDTH - PADDING * 2 - CLOSE_BUTTON_SPACE;
        int textWidth = (width - FORM_WIDTH - HEADER_BUTTON_WIDTH - GAP * 3) / 2;
        return Flow.row()
            .width(width)
            .height(TITLE_HEIGHT)
            .childPadding(GAP)
            .child(
                Widgets.text(IKey.lang("gui.nhkantan.market_terminal.title"), Alignment.CenterLeft)
                    .size(textWidth, TITLE_HEIGHT))
            .child(
                Widgets.text(IKey.dynamic(() -> CurrencyFormat.balance(balance.getLongValue())), Alignment.CenterRight)
                    .size(textWidth, TITLE_HEIGHT))
            .child(
                Widgets.text(IKey.dynamic(state::positionSummary), Alignment.CenterRight)
                    .size(FORM_WIDTH, TITLE_HEIGHT))
            .child(
                new ButtonWidget<>().size(HEADER_BUTTON_WIDTH, TITLE_HEIGHT)
                    .overlay(IKey.lang("gui.nhkantan.wallet.button"))
                    .syncHandler(openWallet));
    }

    /** Stock list on the left, chart plus positions in the middle, order form on the right. */
    private static Flow marketView(TradingState state) {
        int contentWidth = PANEL_WIDTH - PADDING * 2;
        int contentHeight = PANEL_HEIGHT - PADDING * 2 - TITLE_HEIGHT - GAP;
        int middleWidth = contentWidth - LIST_WIDTH - FORM_WIDTH - GAP * 2;
        ListWidget<IWidget, ?> list = new ListWidget<>().width(LIST_WIDTH)
            .heightRel(1f)
            .childSeparator(IIcon.EMPTY_2PX);
        for (StockView stock : ClientMarketData.INSTANCE.stocks()) {
            list.child(stockButton(stock.id(), stock.name(), state));
        }
        return Flow.row()
            .width(contentWidth)
            .height(contentHeight)
            .childPadding(GAP)
            .child(list)
            .child(
                Flow.column()
                    .width(middleWidth)
                    .heightRel(1f)
                    .childPadding(GAP)
                    .child(
                        NHKantan.proxy.createStockChart(state::selectedView)
                            .width(middleWidth)
                            .height(140))
                    .child(positionBox(state)))
            .child(orderForm(state));
    }

    /** Selectable entry showing the stock name and its live price and change. */
    private static ToggleButton stockButton(String stockId, String name, TradingState state) {
        return new ToggleButton().widthRel(1f)
            .height(BUTTON_HEIGHT)
            .value(
                new BoolValue.Dynamic(
                    () -> stockId.equals(state.selectedId()),
                    selected -> { if (selected) state.selectStock(stockId); }))
            .overlay(IKey.dynamic(() -> name + "\n" + formatPrice(ClientMarketData.INSTANCE.get(stockId))));
    }

    /** Open positions below the chart: a scrollable list rebuilt from the synced rows. */
    private static Flow positionBox(TradingState state) {
        ListWidget<IWidget, ?> rows = new ListWidget<>().widthRel(1f)
            .height(46);
        state.trackPositions(rows);
        return Flow.column()
            .widthRel(1f)
            .height(46)
            .child(rows);
    }

    /** Rebuilds the scrollable position rows from the latest synced list. */
    private static void rebuildPositions(TradingState state, ListWidget<IWidget, ?> rows) {
        rows.removeAll();
        List<Position> owned = state.positions();
        if (owned.isEmpty()) {
            rows.child(Widgets.text(IKey.lang("gui.nhkantan.trading.no_positions")));
        }
        for (Position position : owned) {
            rows.child(positionRow(state, position));
        }
    }

    private static Flow positionRow(TradingState state, Position position) {
        String direction = position.direction() == Direction.LONG
            ? StatCollector.translateToLocal("gui.nhkantan.trading.long")
            : StatCollector.translateToLocal("gui.nhkantan.trading.short");
        return Flow.row()
            .fullWidth()
            .height(SMALL_BUTTON_HEIGHT)
            .childPadding(2)
            .child(
                Widgets.text(
                    IKey.dynamic(
                        () -> "#" + position
                            .id() + " " + direction + " " + position.leverage() + "x " + state.profitText(position)))
                    .expanded())
            .child(
                new ButtonWidget<>().size(36, SMALL_BUTTON_HEIGHT)
                    .overlay(IKey.lang("gui.nhkantan.trading.close"))
                    .onMousePressed(GuiActions.leftClick(() -> state.requestClose(position.id()))));
    }

    /** Order form for the selected stock: side, margin, leverage slider, previews and two open buttons. */
    private static Flow orderForm(TradingState state) {
        IKey stepHint = IKey.lang("gui.nhkantan.wallet.step_hint", SHIFT_STEP);
        return Widgets.section("tradingForm", FORM_WIDTH)
            .childPadding(2)
            .child(Widgets.text(IKey.dynamic(state::selectedName)))
            .child(
                Flow.row()
                    .fullWidth()
                    .height(18)
                    .childPadding(2)
                    .child(Widgets.text(IKey.lang("gui.nhkantan.trading.margin"), 44))
                    .child(
                        Widgets.stepButton("-", state.marginValue, -1, -SHIFT_STEP, 1, MAX_MARGIN, stepHint)
                            .size(STEP_BUTTON_WIDTH, SMALL_BUTTON_HEIGHT))
                    .child(
                        new TextFieldWidget().expanded()
                            .numbersLong(1, MAX_MARGIN)
                            .value(state.marginValue)
                            .autoUpdateOnChange(true))
                    .child(
                        Widgets.stepButton("+", state.marginValue, 1, SHIFT_STEP, 1, MAX_MARGIN, stepHint)
                            .size(STEP_BUTTON_WIDTH, SMALL_BUTTON_HEIGHT)))
            .child(Widgets.text(IKey.dynamic(state::leverageLine)))
            .child(state.leverageSlider())
            .child(Widgets.text(IKey.dynamic(state::costPreview)))
            .child(Widgets.text(IKey.dynamic(state::riskPreview)))
            .child(
                Flow.row()
                    .fullWidth()
                    .height(BUTTON_HEIGHT)
                    .childPadding(GAP)
                    .child(
                        new ButtonWidget<>().size(78, BUTTON_HEIGHT)
                            .overlay(IKey.lang("gui.nhkantan.trading.open_long"))
                            .onMousePressed(GuiActions.leftClick(() -> state.requestOpen(Direction.LONG))))
                    .child(
                        new ButtonWidget<>().size(78, BUTTON_HEIGHT)
                            .overlay(IKey.lang("gui.nhkantan.trading.open_short"))
                            .onMousePressed(GuiActions.leftClick(() -> state.requestOpen(Direction.SHORT)))));
    }

    private static String formatPrice(StockView stock) {
        if (stock == null) return "-";
        double change = stock.windowChange();
        EnumChatFormatting color = change > 0.0D ? EnumChatFormatting.DARK_GREEN
            : change < 0.0D ? EnumChatFormatting.RED : EnumChatFormatting.DARK_GRAY;
        return PriceFormat.price(stock.price()) + " " + color + PriceFormat.change(change);
    }

    /**
     * State of one open terminal GUI. Selection and the order form live on the client; prices come from the shared
     * client market data, rules from the synced trading config, and the player's positions from a server-driven sync
     * value. Every request is validated again by {@link TradingService} on the server.
     */
    private static class TradingState {

        private final EntityPlayer player;
        private final PanelSyncManager syncManager;
        private final StringSyncValue positionLines;
        /** Client-only: positions decoded from {@link #positionLines}, refreshed when the synced text changes. */
        private List<Position> positions = Collections.emptyList();
        private String decodedPositions = "";
        /** Client-only: id of the selected stock, driving both the chart and the order form. */
        private String selection = "";
        /** Client-only: margin in whole currency units. */
        private int margin = 100;
        private final IntValue.Dynamic marginValue = new IntValue.Dynamic(
            () -> margin,
            value -> margin = MathUtils.clamp(value, 1, MAX_MARGIN));
        /** Client-only: chosen leverage, kept inside the rules of the selected stock. */
        private int leverage;

        private TradingState(EntityPlayer player, PanelSyncManager syncManager) {
            this.player = player;
            this.syncManager = syncManager;
            this.leverage = rules().defaultLeverage();
            // The server encodes the player's positions; the client only shows the synced text
            this.positionLines = new StringSyncValue(
                () -> "",
                () -> PositionCodec.encode(TradingService.positionsOf(player)));
            syncManager.syncValue("trading_positions", positionLines);
            syncManager.registerSyncedAction(OPEN_ACTION, Side.SERVER, this::open);
            syncManager.registerSyncedAction(
                CLOSE_ACTION,
                Side.SERVER,
                packet -> TradingService.closeFor(player, packet.readLong()));
        }

        /** Client: rebuilds the position rows whenever the synced list changes. */
        private void trackPositions(ListWidget<IWidget, ?> rows) {
            rebuildPositions(this, rows);
            syncManager.onClientTick(() -> {
                String encoded = positionLines.getStringValue();
                if (encoded == null || encoded.equals(decodedPositions)) return;
                decodedPositions = encoded;
                positions = PositionCodec.decode(encoded);
                rebuildPositions(this, rows);
            });
        }

        private List<Position> positions() {
            return positions;
        }

        private String selectedId() {
            return selection;
        }

        /** Client: selects the stock for both the chart and the order form. */
        private void selectStock(String id) {
            selection = id;
            leverage = rules().clampLeverage(leverage);
        }

        /** Client: the selected chart, falling back to the first stock when nothing is selected yet. */
        @Nullable
        private StockView selectedView() {
            StockView view = ClientMarketData.INSTANCE.get(selection);
            if (view != null) return view;
            StockView first = ClientMarketData.INSTANCE.first();
            if (first != null) selection = first.id();
            return first;
        }

        private String selectedName() {
            StockView view = selectedView();
            return view == null ? "" : view.name();
        }

        private InstrumentRules rules() {
            return InstrumentRuleResolver.resolve(selection);
        }

        private String positionSummary() {
            if (positions.isEmpty()) return "";
            long profit = 0L;
            for (Position position : positions) profit = MathUtils.saturatedAdd(profit, profitOf(position));
            return StatCollector.translateToLocalFormatted(
                "gui.nhkantan.trading.summary",
                positions.size(),
                CurrencyFormat.amount(profit));
        }

        /** Client: live profit of the position at the latest synced price. */
        private String profitText(Position position) {
            StockView view = ClientMarketData.INSTANCE.get(position.stockId());
            return view == null ? "-" : CurrencyFormat.amount(position.unrealizedCents(view.price()));
        }

        /** Client: live profit of the position, or zero when the stock is not shown. */
        private long profitOf(Position position) {
            StockView view = ClientMarketData.INSTANCE.get(position.stockId());
            return view == null ? 0L : position.unrealizedCents(view.price());
        }

        /** Client: leverage slider over the configured range, one step per whole leverage. */
        private SliderWidget leverageSlider() {
            InstrumentRules rules = rules();
            return new SliderWidget().widthRel(1f)
                .height(14)
                .bounds(rules.minLeverage(), rules.maxLeverage())
                .stopper(1)
                .value(
                    new DoubleValue.Dynamic(
                        () -> leverage,
                        value -> leverage = rules().clampLeverage((int) Math.round(value))));
        }

        private String leverageLine() {
            return StatCollector.translateToLocalFormatted("gui.nhkantan.trading.leverage_value", leverage);
        }

        /** Client: notional, fee and total of the current form. */
        private String costPreview() {
            long marginCents = Money.cents(margin);
            long notional = TradingMath.notional(marginCents, leverage);
            long fee = TradingMath.openFee(notional, rules().openFeePercent());
            return StatCollector.translateToLocalFormatted(
                "gui.nhkantan.trading.cost",
                CurrencyFormat.amount(notional),
                CurrencyFormat.amount(fee),
                CurrencyFormat.amount(MathUtils.saturatedAdd(marginCents, fee)));
        }

        /** Client: adverse price move that would liquidate the drafted position. */
        private String riskPreview() {
            double liquidation = rules().liquidationPercent();
            if (liquidation <= 0.0D) return StatCollector.translateToLocal("gui.nhkantan.trading.no_liquidation");
            String move = String.format("%.2f%%", liquidation / leverage);
            return StatCollector.translateToLocalFormatted("gui.nhkantan.trading.risk", move);
        }

        /** Client: asks the server to open the drafted position on the selected stock. */
        private void requestOpen(Direction direction) {
            if (selectedView() == null) return;
            String stockId = selection;
            int marginUnits = margin;
            int leverageNow = leverage;
            syncManager.callSyncedAction(OPEN_ACTION, packet -> {
                ByteBufUtils.writeUTF8String(packet, stockId);
                packet.writeByte(direction.ordinal());
                packet.writeInt(marginUnits);
                packet.writeInt(leverageNow);
            });
        }

        /** Client: asks the server to close the position. */
        private void requestClose(long positionId) {
            syncManager.callSyncedAction(CLOSE_ACTION, packet -> packet.writeLong(positionId));
        }

        /** Server: decodes an open request; the service validates everything and the synced views are the feedback. */
        private void open(PacketBuffer packet) {
            String stockId = ByteBufUtils.readUTF8String(packet);
            int side = packet.readByte();
            long marginCents = Money.cents(packet.readInt());
            int leverageNow = packet.readInt();
            if (side < 0 || side >= Direction.values().length) return;
            TradingService.openFor(player, stockId, Direction.values()[side], marginCents, leverageNow);
        }
    }
}
