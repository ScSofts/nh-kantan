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
import com.cleanroommc.modularui.drawable.GuiTextures;
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
import com.yuelengm.nhkantan.client.gui.RealLifeWarningGui;
import com.yuelengm.nhkantan.config.ClientConfig;
import com.yuelengm.nhkantan.gui.WalletGui;
import com.yuelengm.nhkantan.gui.util.GuiActions;
import com.yuelengm.nhkantan.gui.util.Widgets;
import com.yuelengm.nhkantan.market.LeverageRange;
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
 * Handheld market terminal. Right-clicking opens the market GUI: the K-line chart of the selected stock with the
 * position list below it, and on the right the order form with the scrollable stock list below it, all fed by the
 * shared client market data; trading requests are validated and executed by {@link TradingService} on the server.
 */
public class ItemMarketTerminal extends Item implements IGuiHolder<PlayerInventoryGuiData> {

    private static final int PANEL_WIDTH = 560;
    private static final int PANEL_HEIGHT = 240;
    private static final int PADDING = 7;
    private static final int GAP = 4;
    private static final int TITLE_HEIGHT = 14;
    private static final int CLOSE_BUTTON_SPACE = 12;
    private static final int HEADER_BUTTON_WIDTH = 40;
    private static final int FORM_WIDTH = 120;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SMALL_BUTTON_HEIGHT = 14;
    private static final int CHART_HEIGHT = 136;
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
        if (world.isRemote) {
            openTerminalClient();
        }
        return stack;
    }

    @SideOnly(Side.CLIENT)
    private static void openTerminalClient() {
        if (ClientConfig.showLifeWarning) {
            RealLifeWarningGui.openClient(
                () -> GuiFactories.playerInventory()
                    .openFromMainHandClient());
        } else {
            GuiFactories.playerInventory()
                .openFromMainHandClient();
        }
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
        TradingState state = new TradingState(player, syncManager, balance);

        ModularPanel panel = ModularPanel.defaultPanel("nhkantan_market_terminal")
            .sizeRel(0.95f, 0.85f);
        Flow column = Flow.column()
            .full()
            .padding(PADDING)
            .childPadding(GAP)
            .child(header(openWallet, state))
            .child(marketView(state).expanded());

        return panel.child(column);
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
    private static Flow header(InteractionSyncHandler openWallet, TradingState state) {
        return Flow.row()
            .fullWidth()
            .height(TITLE_HEIGHT)
            .childPadding(GAP)
            .child(
                Widgets.text(IKey.lang("gui.nhkantan.market_terminal.title"), Alignment.CenterLeft)
                    .expanded())
            .child(
                Widgets.text(
                    IKey.dynamic(() -> CurrencyFormat.balance(state.balance.getLongValue())),
                    Alignment.CenterRight))
            .child(
                new ButtonWidget<>().size(HEADER_BUTTON_WIDTH, TITLE_HEIGHT)
                    .overlay(IKey.lang("gui.nhkantan.wallet.button"))
                    .syncHandler(openWallet));
    }

    /**
     * Chart and positions on the left, sharing one scroll view; order form and the scrollable stock list below it on
     * the right.
     */
    private static Flow marketView(TradingState state) {
        ListWidget<IWidget, ?> left = new ListWidget<>().expanded()
            .heightRel(1f)
            .childSeparator(IIcon.EMPTY_2PX)
            .child(
                NHKantan.proxy.createStockChart(state::selectedView)
                    .widthRel(1f)
                    .height(CHART_HEIGHT))
            .child(positionBox(state));
        return Flow.row()
            .full()
            .childPadding(GAP)
            .child(left)
            .child(
                Flow.column()
                    .name("right")
                    .width(FORM_WIDTH)
                    .heightRel(1f)
                    .childPadding(GAP)
                    .child(orderForm(state))
                    .child(stockList(state).expanded()));
    }

    /** Every stock as a selectable row; scrolls when there are more than fit. */
    private static ListWidget<IWidget, ?> stockList(TradingState state) {
        ListWidget<IWidget, ?> list = new ListWidget<>().full()
            .expanded()
            .childSeparator(IIcon.EMPTY_2PX);
        for (StockView stock : ClientMarketData.INSTANCE.stocks()) {
            list.child(stockButton(stock.id(), stock.name(), state));
        }
        return list;
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

    /** Open positions below the chart; grows with its rows, the surrounding scroll view scrolls. */
    private static Flow positionBox(TradingState state) {
        Flow rows = Flow.column()
            .widthRel(1f)
            .coverChildrenHeight()
            .childPadding(2);
        state.trackPositions(rows);
        return rows;
    }

    /** Rebuilds the position rows from the latest synced list and re-lays out the scroll view around them. */
    private static void rebuildPositions(TradingState state, Flow rows) {
        rows.removeAll();
        List<Position> owned = state.positions();
        if (owned.isEmpty()) {
            rows.child(Widgets.text(IKey.lang("gui.nhkantan.trading.no_positions")));
        }
        for (Position position : owned) {
            rows.child(positionRow(state, position));
        }
        if (rows.isValid()) rows.scheduleResize();
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
                Widgets
                    .text(
                        IKey.dynamic(
                            () -> state.stockName(position.stockId()) + " "
                                + direction
                                + " "
                                + position.leverage()
                                + "x "
                                + "    "
                                + state.profitText(position)))
                    .fullWidth()
                    .expanded())
            .child(
                new ButtonWidget<>().size(36, SMALL_BUTTON_HEIGHT)
                    .overlay(IKey.lang("gui.nhkantan.trading.close"))
                    .onMousePressed(GuiActions.leftClick(() -> state.requestClose(position.id()))));
    }

    /** Order form for the selected stock: side, margin, leverage slider, previews and two open buttons. */
    private static Flow orderForm(TradingState state) {
        return Widgets.section("tradingForm", FORM_WIDTH)
            .childPadding(2)
            // The leverage slider is disabled for stocks with a fixed leverage
            .collapseDisabledChild()
            .child(
                Flow.row()
                    .fullWidth()
                    .height(18)
                    .child(Widgets.text(IKey.lang("gui.nhkantan.trading.margin")))
                    .child(
                        new TextFieldWidget().expanded()
                            .numbersLong(1, MAX_MARGIN)
                            .value(state.marginValue)
                            .autoUpdateOnChange(true))
                    .child(
                        new ButtonWidget<>().size(30, 18)
                            .overlay(IKey.lang("gui.nhkantan.trading.max_margin"))
                            .onMousePressed(GuiActions.leftClick(state::selectMaxMargin))))
            .child(Widgets.text(IKey.dynamic(state::leverageLine)))
            .child(state.leverageSlider())
            .child(
                Widgets.labeledValue(
                    IKey.lang("gui.nhkantan.trading.notional"),
                    IKey.dynamic(() -> CurrencyFormat.amount(state.notional()))))
            .child(
                Widgets.labeledValue(
                    IKey.lang("gui.nhkantan.trading.fee"),
                    IKey.dynamic(() -> CurrencyFormat.amount(state.fee()))))
            .child(
                Widgets.labeledValue(
                    IKey.lang("gui.nhkantan.trading.liquidation_move"),
                    IKey.dynamic(state::liquidationMove)))
            .child(
                Flow.row()
                    .fullWidth()
                    .height(BUTTON_HEIGHT)
                    .childPadding(GAP)
                    .child(
                        new ButtonWidget<>().height(BUTTON_HEIGHT)
                            .expanded()
                            .overlay(IKey.lang("gui.nhkantan.trading.open_long"))
                            .onMousePressed(GuiActions.leftClick(() -> state.requestOpen(Direction.LONG))))
                    .child(
                        new ButtonWidget<>().height(BUTTON_HEIGHT)
                            .expanded()
                            .overlay(IKey.lang("gui.nhkantan.trading.open_short"))
                            .onMousePressed(GuiActions.leftClick(() -> state.requestOpen(Direction.SHORT)))));
    }

    private static String formatPrice(StockView stock) {
        if (stock == null) return "-";
        double change = stock.latestChange();
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
        private final LongSyncValue balance;
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

        private TradingState(EntityPlayer player, PanelSyncManager syncManager, LongSyncValue balance) {
            this.player = player;
            this.syncManager = syncManager;
            this.balance = balance;
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
        private void trackPositions(Flow rows) {
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

        /** Client: rules of the selected stock, from the synced trading config and the stock's leverage range. */
        private InstrumentRules rules() {
            StockView view = selectedView();
            return InstrumentRuleResolver.resolve(view == null ? LeverageRange.DEFAULT : view.leverage());
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

        /** Client: display name of the stock, or its id when the stock is not shown (e.g. disabled). */
        private String stockName(String stockId) {
            StockView view = ClientMarketData.INSTANCE.get(stockId);
            return view == null ? stockId : view.name();
        }

        /** Client: live profit of the position at the latest synced price, colored by sign. */
        private String profitText(Position position) {
            StockView view = ClientMarketData.INSTANCE.get(position.stockId());
            if (view == null) return "-";
            long profit = position.unrealizedCents(view.price());
            return CurrencyFormat.signColor(profit) + CurrencyFormat.signedAmount(profit) + EnumChatFormatting.RESET;
        }

        /** Client: live profit of the position, or zero when the stock is not shown. */
        private long profitOf(Position position) {
            StockView view = ClientMarketData.INSTANCE.get(position.stockId());
            return view == null ? 0L : position.unrealizedCents(view.price());
        }

        /**
         * Client: leverage slider over the selected stock's range, snapping to whole leverage. Its bounds follow the
         * selection; it is hidden while the range allows a single leverage only.
         */
        private SliderWidget leverageSlider() {
            SliderWidget slider = new SliderWidget().widthRel(1f)
                .height(14)
                .background(GuiTextures.MC_BUTTON_DISABLED)
                .value(
                    new DoubleValue.Dynamic(
                        () -> leverage,
                        value -> leverage = rules().clampLeverage((int) Math.round(value))));
            applyLeverageRange(slider);
            syncManager.onClientTick(() -> applyLeverageRange(slider));
            return slider;
        }

        /** Client: keeps the slider bounds and the chosen leverage inside the selected stock's rules. */
        private void applyLeverageRange(SliderWidget slider) {
            InstrumentRules rules = rules();
            leverage = rules.clampLeverage(leverage);
            boolean adjustable = rules.minLeverage() < rules.maxLeverage();
            if (slider.isEnabled() != adjustable) {
                slider.setEnabled(adjustable);
                if (slider.hasParent()) slider.getParent()
                    .scheduleResize();
            }
            if (adjustable && (slider.getMin() != rules.minLeverage() || slider.getMax() != rules.maxLeverage())) {
                slider.bounds(rules.minLeverage(), rules.maxLeverage());
            }
        }

        private String leverageLine() {
            return StatCollector.translateToLocalFormatted("gui.nhkantan.trading.leverage_value", leverage);
        }

        private long marginCents() {
            return Money.cents(margin);
        }

        /** Client: notional position of the current form. */
        private long notional() {
            return TradingMath.notional(marginCents(), leverage);
        }

        /** Client: opening fee of the current form. */
        private long fee() {
            return TradingMath.openFee(notional(), rules().openFeePercent());
        }

        /** Client: margin plus fee, deducted from the wallet when opening. */
        private long total() {
            return totalFor(margin);
        }

        /** Client: margin plus opening fee for a whole-unit margin at the chosen leverage. */
        private long totalFor(int marginUnits) {
            long marginCents = Money.cents(marginUnits);
            long notional = TradingMath.notional(marginCents, leverage);
            long fee = TradingMath.openFee(notional, rules().openFeePercent());
            return MathUtils.saturatedAdd(marginCents, fee);
        }

        /** Client: fills the margin field with the largest whole-unit margin the balance covers, fee included. */
        private void selectMaxMargin() {
            long balanceCents = balance.getLongValue();
            if (balanceCents <= 0L) return;
            int high = (int) Math.min(MAX_MARGIN, balanceCents / Money.CENTS_PER_UNIT);
            int low = 0;
            while (low < high) {
                int middle = low + (high - low + 1) / 2;
                if (totalFor(middle) <= balanceCents) low = middle;
                else high = middle - 1;
            }
            if (low >= 0) margin = low;
        }

        /** Client: adverse price move that would liquidate the drafted position. */
        private String liquidationMove() {
            double liquidation = rules().liquidationPercent();
            if (liquidation <= 0.0D) return StatCollector.translateToLocal("gui.nhkantan.trading.liquidation_off");
            return String.format("%.2f%%", liquidation / leverage);
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
