package com.yuelengm.nhkantan.gui;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import com.cleanroommc.modularui.api.IGuiHolder;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.factory.GuiData;
import com.cleanroommc.modularui.factory.SimpleGuiFactory;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.utils.item.ItemStackHandler;
import com.cleanroommc.modularui.value.BoolValue;
import com.cleanroommc.modularui.value.IntValue;
import com.cleanroommc.modularui.value.ObjectValue;
import com.cleanroommc.modularui.value.sync.LongSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ItemDisplayWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.SlotGroupWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.ToggleButton;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.cleanroommc.modularui.widgets.slot.ItemSlot;
import com.cleanroommc.modularui.widgets.slot.ModularSlot;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.yuelengm.nhkantan.NHKantan;
import com.yuelengm.nhkantan.gui.util.GuiActions;
import com.yuelengm.nhkantan.gui.util.Widgets;
import com.yuelengm.nhkantan.item.ItemMarketTerminal;
import com.yuelengm.nhkantan.util.MathUtils;
import com.yuelengm.nhkantan.util.PlayerInventories;
import com.yuelengm.nhkantan.wallet.CurrencyFormat;
import com.yuelengm.nhkantan.wallet.CurrencyItem;
import com.yuelengm.nhkantan.wallet.ExchangeItems;
import com.yuelengm.nhkantan.wallet.WalletService;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * The wallet window, opened from the market terminal. Left: balance, the withdraw section (selected item, quantity),
 * the deposit section (slots) and the player inventory. Right: a scrollable list of currency items with their deposit
 * and withdrawal rates; selecting a withdrawable row chooses what to withdraw.
 * <p>
 * Built identically on client and server. The balance comes from a server-driven {@link LongSyncValue}; selection and
 * quantity are client-only, and withdrawing sends one request with item and quantity that the server validates. The
 * rules themselves live in {@link WalletService}. While in debt the deposit slots accept debt items for repayment and
 * the server refuses withdrawals.
 */
public class WalletGui implements IGuiHolder<GuiData> {

    public static final WalletGui INSTANCE = new WalletGui();

    /** Sync key of the balance value, shared by every GUI showing the wallet balance. */
    public static final String BALANCE_SYNC_KEY = "wallet_balance";

    private static final int PADDING = 7;
    private static final int GAP = 3;
    private static final int TITLE_GAP = 1;
    private static final int COLUMN_GAP = 8;
    private static final int LEFT_WIDTH = 162;
    private static final int RIGHT_WIDTH = 150;
    private static final int WIDTH = PADDING * 2 + LEFT_WIDTH + COLUMN_GAP + RIGHT_WIDTH;
    private static final int SLOT_SIZE = 18;
    private static final int DEPOSIT_SLOTS = 6;
    private static final String DEPOSIT_GROUP = "nhkantan_deposit";
    private static final int SCROLLBAR_SPACE = 6;
    private static final int ROW_PADDING = 3;
    /** Item icon area; the widget insets the item by 1 px on each side, so 18 shows a full 16 px icon. */
    private static final int ITEM_SIZE = 18;
    private static final int STEP_BUTTON_WIDTH = 11;
    private static final int STEP_BUTTON_HEIGHT = Widgets.LINE_HEIGHT + 2;
    private static final int ACTION_BUTTON_WIDTH = 32;
    /** Upper bound of the quantity field, in base units; the server further limits it to what fits. */
    private static final int MAX_QUANTITY = 999_999;
    private static final int SHIFT_STEP = 10;
    private static final int NO_SELECTION = -1;
    private static final String WITHDRAW_ACTION = "wallet_withdraw";

    @Nullable
    private static SimpleGuiFactory factory;

    private WalletGui() {}

    /** Registers the GUI factory; must run on both sides during mod loading. */
    public static void register() {
        factory = new SimpleGuiFactory(NHKantan.MODID + ":wallet", () -> INSTANCE);
    }

    public static void open(EntityPlayerMP player) {
        if (factory != null) factory.open(player);
    }

    /** The wallet balance, read on the server and synced to the client; register under {@link #BALANCE_SYNC_KEY}. */
    public static LongSyncValue balanceValue(EntityPlayer player) {
        return new LongSyncValue(() -> 0L, () -> WalletService.balanceOf(player));
    }

    @Override
    @SideOnly(Side.CLIENT)
    public ModularScreen createScreen(GuiData data, ModularPanel mainPanel) {
        return new ModularScreen(NHKantan.MODID, mainPanel);
    }

    @Override
    public ModularPanel buildUI(GuiData data, PanelSyncManager syncManager, UISettings settings) {
        settings.canInteractWith(ItemMarketTerminal::isHeldBy);
        WalletState state = new WalletState(data.getPlayer(), syncManager);
        return ModularPanel.defaultPanel("nhkantan_wallet")
            .width(WIDTH)
            .coverChildrenHeight()
            .child(
                Flow.row()
                    .coverChildrenHeight()
                    .padding(PADDING)
                    .childPadding(COLUMN_GAP)
                    .child(leftColumn(state))
                    .child(rightColumn(state)));
    }

    private static Flow leftColumn(WalletState state) {
        Flow inventory = Flow.column()
            .width(LEFT_WIDTH)
            .coverChildrenHeight()
            .childPadding(TITLE_GAP)
            .child(
                Widgets.text(IKey.lang("container.inventory"))
                    .fullWidth())
            .child(SlotGroupWidget.playerInventory((index, slot) -> slot));
        return Flow.column()
            .width(LEFT_WIDTH)
            .coverChildrenHeight()
            .childPadding(GAP)
            .child(balanceSection(state))
            .child(withdrawSection(state))
            .child(depositSection(state))
            .child(inventory);
    }

    private static Flow balanceSection(WalletState state) {
        return Widgets.section("balanceWidget", LEFT_WIDTH)
            .child(
                Widgets.text(
                    IKey.lang(
                        "gui.nhkantan.wallet.balance",
                        () -> new Object[] { CurrencyFormat.balance(state.balance()) })));
    }

    /** Title with the total cost, then: selected item, quantity selector, Max and Withdraw buttons. */
    private static Flow withdrawSection(WalletState state) {
        TextWidget<?> title = Widgets
            .text(
                IKey.dynamic(
                    () -> titleWithAmount(
                        "gui.nhkantan.wallet.withdraw",
                        state.cost(),
                        state.cost() > state.balance() ? EnumChatFormatting.RED : null)))
            .widthRel(1f);
        IKey stepHint = IKey.lang("gui.nhkantan.wallet.step_hint", SHIFT_STEP);
        return Widgets.section("withdrawWidget", LEFT_WIDTH)
            .childPadding(TITLE_GAP)
            .child(title)
            .child(
                Flow.row()
                    .fullWidth()
                    .height(SLOT_SIZE)
                    .childPadding(2)
                    .child(
                        new ItemDisplayWidget()
                            .item(new ObjectValue.Dynamic<>(ItemStack.class, state::selectedStack, null))
                            .size(SLOT_SIZE))
                    .child(
                        Widgets.stepButton("-", state.quantityValue, -1, -SHIFT_STEP, 1, MAX_QUANTITY, stepHint)
                            .size(STEP_BUTTON_WIDTH, STEP_BUTTON_HEIGHT))
                    .child(
                        new TextFieldWidget().expanded()
                            .numbersLong(1, MAX_QUANTITY)
                            .value(state.quantityValue)
                            .autoUpdateOnChange(true))
                    .child(
                        Widgets.stepButton("+", state.quantityValue, 1, SHIFT_STEP, 1, MAX_QUANTITY, stepHint)
                            .size(STEP_BUTTON_WIDTH, STEP_BUTTON_HEIGHT))
                    .child(
                        new ButtonWidget<>().size(ACTION_BUTTON_WIDTH, SLOT_SIZE)
                            .overlay(IKey.lang("gui.nhkantan.wallet.withdraw_max"))
                            .onMousePressed(GuiActions.leftClick(state::selectMaxQuantity)))
                    .child(
                        new ButtonWidget<>().size(ACTION_BUTTON_WIDTH, SLOT_SIZE)
                            .overlay(IKey.lang("gui.nhkantan.wallet.withdraw"))
                            .onMousePressed(GuiActions.leftClick(state::requestWithdraw))));
    }

    /** Title with the expected credit, then the deposit slots and the Deposit button. */
    private static Flow depositSection(WalletState state) {
        Flow slots = Flow.row()
            .fullWidth()
            .height(SLOT_SIZE);
        for (int i = 0; i < DEPOSIT_SLOTS; i++) {
            slots.child(
                new ItemSlot().slot(
                    new ModularSlot(state.depositSlots, i).slotGroup(DEPOSIT_GROUP)
                        .filter(stack -> WalletService.acceptsStack(state.balance(), stack))));
        }
        slots.child(
            new ButtonWidget<>().expanded()
                .overlay(IKey.lang("gui.nhkantan.wallet.deposit"))
                .syncHandler(GuiActions.serverClick(state::settleDeposit)));
        TextWidget<?> title = Widgets
            .text(IKey.dynamic(() -> titleWithAmount("gui.nhkantan.wallet.deposit", state.depositPreview(), null)))
            .widthRel(1f);
        return Widgets.section("depositWidget", LEFT_WIDTH)
            .childPadding(TITLE_GAP)
            .child(title)
            .child(slots);
    }

    private static Flow rightColumn(WalletState state) {
        ListWidget<IWidget, ?> list = new ListWidget<>().fullWidth();
        state.trackItemList(list);
        return Flow.column()
            .width(RIGHT_WIDTH)
            .fullHeight()
            .childPadding(1)
            .child(
                Widgets.text(IKey.dynamic(state::itemListTitle))
                    .fullWidth())
            .child(list.expanded());
    }

    /**
     * A selectable row: icon and the exchange rates, {@code +credit} for deposits and {@code -cost} for withdrawals.
     * Only withdrawable items can be selected.
     */
    private static ToggleButton exchangeRow(WalletState state, int index, CurrencyItem item) {
        return new ToggleButton().fullWidth()
            .height(ITEM_SIZE)
            .value(
                new BoolValue.Dynamic(
                    () -> state.selection == index,
                    selected -> { if (selected && item.canWithdraw()) state.selection = index; }))
            .child(
                Flow.row()
                    .sizeRel(1f)
                    .padding(2, 0)
                    .childPadding(ROW_PADDING)
                    .child(
                        Widgets.itemIcon(item.displayStack())
                            .size(ITEM_SIZE))
                    .child(new TextWidget<>(rates(item)).height(ITEM_SIZE)));
    }

    /** The section title alone, or followed by the amount when it is not zero, e.g. {@code Deposit 6,000 NHC}. */
    private static String titleWithAmount(String langKey, long amount, @Nullable EnumChatFormatting color) {
        String title = StatCollector.translateToLocal(langKey);
        if (amount == 0) return title;
        return title + " " + (color == null ? "" : color) + CurrencyFormat.amount(amount);
    }

    /** {@code +credit} in green when depositable, {@code -cost} in red when withdrawable, per item. */
    private static String rates(CurrencyItem item) {
        StringBuilder text = new StringBuilder();
        if (item.isDebtItem()) {
            if (item.canRepay()) {
                text.append(EnumChatFormatting.DARK_GREEN)
                    .append('+')
                    .append(CurrencyFormat.amount(item.repayment(1)));
            }
            return text.toString();
        }
        if (item.canDeposit()) {
            text.append(EnumChatFormatting.DARK_GREEN)
                .append('+')
                .append(CurrencyFormat.amount(item.depositCredit(1)));
        }
        if (item.canWithdraw()) {
            if (text.length() > 0) text.append(' ');
            text.append(EnumChatFormatting.RED)
                .append('-')
                .append(CurrencyFormat.amount(item.withdrawCost(1)));
        }
        return text.toString();
    }

    /**
     * State of one open wallet window on one side. Selection and quantity are only used on the client; deposit slots
     * and the synced actions exist on both sides.
     */
    private static class WalletState {

        private final EntityPlayer player;
        private final PanelSyncManager syncManager;
        private final LongSyncValue balance;
        private final ItemStackHandler depositSlots = new ItemStackHandler(DEPOSIT_SLOTS);
        /** Client-only: index of the selected currency item, or {@link #NO_SELECTION}. */
        private int selection = NO_SELECTION;
        /** Client-only: whether the right list currently shows debt entries. */
        private boolean renderedDebt;
        private boolean itemListBuilt;
        /** Client-only: quantity to withdraw. */
        private int quantity = 1;
        private final IntValue.Dynamic quantityValue = new IntValue.Dynamic(
            () -> quantity,
            value -> quantity = MathUtils.clamp(value, 1, MAX_QUANTITY));

        private WalletState(EntityPlayer player, PanelSyncManager syncManager) {
            this.player = player;
            this.syncManager = syncManager;
            this.balance = balanceValue(player);
            syncManager.syncValue(BALANCE_SYNC_KEY, balance);
            syncManager.registerSyncedAction(
                WITHDRAW_ACTION,
                Side.SERVER,
                packet -> withdraw(packet.readVarIntFromBuffer(), packet.readVarIntFromBuffer()));
            syncManager.registerSlotGroup(DEPOSIT_GROUP, DEPOSIT_SLOTS);
            syncManager.addCloseListener(closing -> { if (!closing.worldObj.isRemote) returnDepositSlots(); });
        }

        private long balance() {
            return balance.getLongValue();
        }

        private String itemListTitle() {
            return StatCollector.translateToLocal(
                balance() < 0 ? "gui.nhkantan.wallet.debt_list" : "gui.nhkantan.wallet.exchange_list");
        }

        /** Client: swaps the right list between exchange and debt entries when the balance crosses zero. */
        private void trackItemList(ListWidget<IWidget, ?> list) {
            renderItemList(list);
            syncManager.onClientTick(() -> {
                if (itemListBuilt && renderedDebt == (balance() < 0)) return;
                renderItemList(list);
            });
        }

        private void renderItemList(ListWidget<IWidget, ?> list) {
            boolean debt = balance() < 0;
            renderedDebt = debt;
            itemListBuilt = true;
            if (debt) selection = NO_SELECTION;
            list.removeAll();
            List<CurrencyItem> items = debt ? ExchangeItems.debtItems() : ExchangeItems.currencyItems();
            if (items.isEmpty()) {
                list.child(
                    Widgets.text(
                        IKey.lang(debt ? "gui.nhkantan.wallet.no_debt_items" : "gui.nhkantan.wallet.no_currency_items"),
                        RIGHT_WIDTH - SCROLLBAR_SPACE));
                return;
            }
            for (int i = 0; i < items.size(); i++) {
                list.child(exchangeRow(this, i, items.get(i)));
            }
        }

        /** Client: the selected withdrawable item, or {@code null}. */
        @Nullable
        private CurrencyItem selected() {
            return ExchangeItems.withdrawable(selection);
        }

        /** The selected item for the preview slot, or {@code null} for an empty slot. */
        @Nullable
        private ItemStack selectedStack() {
            CurrencyItem item = selected();
            return item == null ? null : item.displayStack();
        }

        /** Client: total cost of the selected item and quantity. */
        private long cost() {
            CurrencyItem item = selected();
            return item == null ? 0L : item.withdrawCost(quantity);
        }

        /** Client: sets the quantity to the most the balance affords for the selected item. */
        private void selectMaxQuantity() {
            CurrencyItem item = selected();
            if (item != null) quantity = MathUtils.clamp(item.affordable(balance(), MAX_QUANTITY), 1, MAX_QUANTITY);
        }

        /** Currency the deposit slots would credit, or repay while in debt. */
        private long depositPreview() {
            List<ItemStack> stacks = new ArrayList<>(DEPOSIT_SLOTS);
            for (int i = 0; i < DEPOSIT_SLOTS; i++) stacks.add(depositSlots.getStackInSlot(i));
            return WalletService.settlementPreview(balance(), stacks);
        }

        /** Client: asks the server to withdraw the selected item and quantity. */
        private void requestWithdraw() {
            if (selected() == null) return;
            int index = selection;
            int count = quantity;
            syncManager.callSyncedAction(WITHDRAW_ACTION, packet -> {
                packet.writeVarIntToBuffer(index);
                packet.writeVarIntToBuffer(count);
            });
        }

        /** Server: validates and performs a withdrawal request; the client keeps its selection. */
        private void withdraw(int index, int count) {
            WalletService service = WalletService.get();
            CurrencyItem item = ExchangeItems.withdrawable(index);
            if (service == null || item == null || count < 1 || count > MAX_QUANTITY) return;
            service.withdrawToInventory(player, item, count);
        }

        /** Server: hands in the slot contents; whatever is not accepted stays in the slots. */
        private void settleDeposit() {
            WalletService service = WalletService.get();
            if (service == null) return;
            for (int i = 0; i < DEPOSIT_SLOTS; i++) {
                ItemStack stack = depositSlots.getStackInSlot(i);
                if (stack == null || service.settleStack(player, stack) == 0) continue;
                depositSlots.setStackInSlot(i, stack.stackSize > 0 ? stack : null);
            }
        }

        private void returnDepositSlots() {
            for (int i = 0; i < DEPOSIT_SLOTS; i++) {
                PlayerInventories.returnToPlayer(player, depositSlots.getStackInSlot(i));
                depositSlots.setStackInSlot(i, null);
            }
            PlayerInventories.resync(player);
        }
    }
}
