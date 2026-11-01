package com.yuelengm.nhkantan.item;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
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
import com.cleanroommc.modularui.value.sync.InteractionSyncHandler;
import com.cleanroommc.modularui.value.sync.LongSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.ToggleButton;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.yuelengm.nhkantan.NHKantan;
import com.yuelengm.nhkantan.gui.WalletGui;
import com.yuelengm.nhkantan.gui.util.GuiActions;
import com.yuelengm.nhkantan.gui.util.Widgets;
import com.yuelengm.nhkantan.market.view.ClientMarketData;
import com.yuelengm.nhkantan.market.view.PriceFormat;
import com.yuelengm.nhkantan.market.view.StockView;
import com.yuelengm.nhkantan.wallet.CurrencyFormat;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Handheld market terminal. Right-clicking opens the market GUI: a stock list and the K-line chart of the selected
 * stock, both fed by the shared client market data. Trading is not implemented yet.
 */
public class ItemMarketTerminal extends Item implements IGuiHolder<PlayerInventoryGuiData> {

    private static final int PANEL_WIDTH = 330;
    private static final int PANEL_HEIGHT = 190;
    private static final int PADDING = 7;
    private static final int GAP = 4;
    private static final int TITLE_HEIGHT = 14;
    private static final int CLOSE_BUTTON_SPACE = 12;
    private static final int WALLET_BUTTON_WIDTH = 40;
    private static final int LIST_WIDTH = 92;
    private static final int BUTTON_HEIGHT = 22;

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

        ModularPanel panel = ModularPanel.defaultPanel("nhkantan_market_terminal", PANEL_WIDTH, PANEL_HEIGHT);
        Flow column = Flow.column()
            .widthRel(1f)
            .heightRel(1f)
            .padding(PADDING)
            .childPadding(GAP)
            .child(header(balance, openWallet));
        // Market data only exists on the client; the server-side copy of the panel carries no market widgets
        if (data.getPlayer().worldObj.isRemote) {
            column.child(marketView());
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
    private static Flow header(LongSyncValue balance, InteractionSyncHandler openWallet) {
        int width = PANEL_WIDTH - PADDING * 2 - CLOSE_BUTTON_SPACE;
        int textWidth = (width - WALLET_BUTTON_WIDTH - GAP * 2) / 2;
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
                new ButtonWidget<>().size(WALLET_BUTTON_WIDTH, TITLE_HEIGHT)
                    .overlay(IKey.lang("gui.nhkantan.wallet.button"))
                    .syncHandler(openWallet));
    }

    /** Stock list on the left, chart of the selected stock on the right. */
    private static Flow marketView() {
        int contentWidth = PANEL_WIDTH - PADDING * 2;
        int contentHeight = PANEL_HEIGHT - PADDING * 2 - TITLE_HEIGHT - GAP;
        Selection selection = new Selection();
        StockView first = ClientMarketData.INSTANCE.first();
        selection.stockId = first == null ? "" : first.id();

        ListWidget<IWidget, ?> list = new ListWidget<>().width(LIST_WIDTH)
            .heightRel(1f)
            .childSeparator(IIcon.EMPTY_2PX);
        for (StockView stock : ClientMarketData.INSTANCE.stocks()) {
            list.child(stockButton(stock.id(), stock.name(), selection));
        }
        return Flow.row()
            .width(contentWidth)
            .height(contentHeight)
            .childPadding(GAP)
            .child(list)
            .child(
                NHKantan.proxy.createStockChart(() -> ClientMarketData.INSTANCE.get(selection.stockId))
                    .width(contentWidth - LIST_WIDTH - GAP)
                    .heightRel(1f));
    }

    /** Selectable entry showing the stock name and its live price and change. */
    private static ToggleButton stockButton(String stockId, String name, Selection selection) {
        return new ToggleButton().widthRel(1f)
            .height(BUTTON_HEIGHT)
            .value(
                new BoolValue.Dynamic(
                    () -> stockId.equals(selection.stockId),
                    selected -> { if (selected) selection.stockId = stockId; }))
            .overlay(IKey.dynamic(() -> name + "\n" + formatPrice(ClientMarketData.INSTANCE.get(stockId))));
    }

    private static String formatPrice(StockView stock) {
        if (stock == null) return "-";
        double change = stock.windowChange();
        EnumChatFormatting color = change > 0.0D ? EnumChatFormatting.DARK_GREEN
            : change < 0.0D ? EnumChatFormatting.RED : EnumChatFormatting.DARK_GRAY;
        return PriceFormat.price(stock.price()) + " " + color + PriceFormat.change(change);
    }

    /** Client-side selection state of one open terminal GUI. */
    private static class Selection {

        private String stockId;
    }
}
