package com.yuelengm.nhkantan.gui;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import com.cleanroommc.modularui.api.IGuiHolder;
import com.cleanroommc.modularui.api.drawable.IIcon;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.GuiTextures;
import com.cleanroommc.modularui.factory.GuiData;
import com.cleanroommc.modularui.factory.SimpleGuiFactory;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.utils.item.ItemStackHandler;
import com.cleanroommc.modularui.value.BoolValue;
import com.cleanroommc.modularui.value.IntValue;
import com.cleanroommc.modularui.value.ObjectValue;
import com.cleanroommc.modularui.value.sync.InteractionSyncHandler;
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
import com.yuelengm.nhkantan.config.CurrencyConfig;
import com.yuelengm.nhkantan.item.ItemMarketTerminal;
import com.yuelengm.nhkantan.wallet.CurrencyItem;
import com.yuelengm.nhkantan.wallet.DebtItem;
import com.yuelengm.nhkantan.wallet.ExchangeItems;
import com.yuelengm.nhkantan.wallet.PlayerInventories;
import com.yuelengm.nhkantan.wallet.WalletResult;
import com.yuelengm.nhkantan.wallet.WalletService;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * The wallet window, opened from the market terminal. Left: wallet info, deposit slots and the player inventory.
 * Right: a scrollable list of currency items showing their deposit and withdrawal rates; selecting a withdrawable item
 * lets the player choose a quantity below and exchange it.
 * <p>
 * Built identically on client and server. Actions run on the server through {@link InteractionSyncHandler}s; the
 * balance comes from a server-driven {@link LongSyncValue}; selection and quantity are client-only;
 * withdrawing sends one request with item and quantity, which the server validates.
 * While in debt the deposit slots accept debt items for repayment and the exchange list is locked.
 */
public class WalletGui implements IGuiHolder<GuiData> {

    public static final WalletGui INSTANCE = new WalletGui();

    private static final int PADDING = 7;
    private static final int GAP = 3;
    private static final int COLUMN_GAP = 8;
    private static final int LEFT_WIDTH = 162;
    private static final int RIGHT_WIDTH = 150;
    private static final int WIDTH = PADDING * 2 + LEFT_WIDTH + COLUMN_GAP + RIGHT_WIDTH;
    private static final int HEIGHT = 176;
    private static final int LINE_HEIGHT = 10;
    private static final int CLOSE_BUTTON_SPACE = 14;
    private static final int SLOT_SIZE = 18;
    private static final int DEPOSIT_SLOTS = 6;
    private static final String DEPOSIT_GROUP = "nhkantan_deposit";
    private static final int LIST_HEIGHT = 100;
    private static final int SCROLLBAR_SPACE = 6;
    private static final int ROW_HEIGHT = 22;
    private static final int ROW_PADDING = 3;
    /** Item icon area; the widget insets the item by 1 px on each side, so 18 shows a full 16 px icon. */
    private static final int ITEM_SIZE = 18;
    private static final int STEP_BUTTON_WIDTH = 10;
    private static final int QUANTITY_WIDTH = 28;
    private static final int CHECKOUT_HEIGHT = 16;
    private static final int MAX_QUANTITY = 3 * 9 * 64;
    private static final int NO_SELECTION = -1;
    private static final String WITHDRAW_ACTION = "wallet_withdraw";
    private static final String WITHDRAWN_ACTION = "wallet_withdrawn";
    private static final int SHIFT_STEP = 10;

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

    @Override
    @SideOnly(Side.CLIENT)
    public ModularScreen createScreen(GuiData data, ModularPanel mainPanel) {
        return new ModularScreen(NHKantan.MODID, mainPanel);
    }

    @Override
    public ModularPanel buildUI(GuiData data, PanelSyncManager syncManager, UISettings settings) {
        EntityPlayer player = data.getPlayer();
        settings.canInteractWith(ItemMarketTerminal::isHeldBy);
        WalletState state = new WalletState(player, syncManager);
        return ModularPanel.defaultPanel("nhkantan_wallet").width(WIDTH).coverChildrenHeight()
            .child(
                Flow.row()
                    .coverChildrenHeight()
                    .padding(PADDING)
                    .childPadding(COLUMN_GAP)
                    .child(leftColumn(state))
                    .child(rightColumn(state)));
    }

    /** Balance with currency name; red with a debt marker when negative. */
    public static String formatBalance(long balance) {
        String text = formatAmount(balance);
        if (balance >= 0) return text;
        return EnumChatFormatting.RED + text + " " + StatCollector.translateToLocal("gui.nhkantan.wallet.in_debt");
    }

    private static String formatAmount(long amount) {
        return String.format("%,d %s", amount, CurrencyConfig.name);
    }

    private static Flow leftColumn(WalletState state) {
        Flow inv = Flow.column()
            .width(LEFT_WIDTH)
            .coverChildrenHeight()
            .child(text(IKey.lang("container.inventory")).fullWidth())
            .child(SlotGroupWidget.playerInventory((index, slot) -> slot));

        return Flow.column()
            .width(LEFT_WIDTH)
            .coverChildrenHeight()
            .childPadding(GAP)
            .child(infoBox(state))
            .child(withdrawRow(state))
            .child(depositRow(state))
            .child(inv);
    }

    private static Flow infoBox(WalletState state) {
        return Flow.column()
            .name("balanceWidget")
            .background(GuiTextures.SLOT_ITEM)
            .width(LEFT_WIDTH)
            .coverChildrenHeight()
            .padding(4)
            .child(
                text(IKey.lang("gui.nhkantan.wallet.balance", () -> new Object[] { formatBalance(state.balance()) })));
    }

    private static Flow withdrawRow(WalletState state) {
        TextWidget<?> title = text(
            IKey.dynamic(
                () -> titleWithAmount(
                    "gui.nhkantan.wallet.withdraw",
                    state.cost(),
                    state.cost() > state.balance() ? EnumChatFormatting.RED : null))).widthRel(1f);

        return Flow.column()
            .name("withdrawWidget")
            .background(GuiTextures.SLOT_ITEM)
            .width(LEFT_WIDTH)
            .coverChildrenHeight()
            .padding(4)
            .child(title)
            .child(
                Flow.row()
                    .fullWidth()
                    .height(18)
                    .childPadding(2)
                    .child(
                        new ItemDisplayWidget()
                            .item(new ObjectValue.Dynamic<>(ItemStack.class, state::selectedStack, null))
                            .size(SLOT_SIZE))
                    .child(stepButton("-", state.quantityValue, -1))
                    .child(
                        new TextFieldWidget().expanded()
                            .numbersLong(1, MAX_QUANTITY)
                            .value(state.quantityValue)
                            .autoUpdateOnChange(true))

                    .child(stepButton("+", state.quantityValue, 1))
                    .child(
                        new ButtonWidget<>().size(32, 18)
                            .overlay(IKey.lang("gui.nhkantan.wallet.withdraw_max"))
                            .onMousePressed(mouseButton -> {
                                if (mouseButton != 0) return false;
                                state.selectMaxQuantity();
                                return true;
                            }))
                    .child(
                        new ButtonWidget<>().size(32, 18)
                            .overlay(IKey.lang("gui.nhkantan.wallet.withdraw"))
                            .onMousePressed(mouseButton -> {
                                if (mouseButton != 0) return false;
                                state.requestWithdraw();
                                return true;
                            })));
    }

    private static Flow depositRow(WalletState state) {
        Flow depositSlotRow = Flow.row()
            .fullWidth()
            .height(SLOT_SIZE);
        for (int i = 0; i < DEPOSIT_SLOTS; i++) {
            depositSlotRow.child(
                new ItemSlot().slot(
                    new ModularSlot(state.depositSlots, i).slotGroup(DEPOSIT_GROUP)
                        .filter(state::acceptsDeposit)));
        }

        depositSlotRow.child(
            new ButtonWidget<>().expanded()
                .overlay(IKey.lang("gui.nhkantan.wallet.deposit"))
                .syncHandler(serverAction(state::settleDeposit)));

        TextWidget<?> title = text(
            IKey.dynamic(() -> titleWithAmount("gui.nhkantan.wallet.deposit", state.depositPreview(), null)))
                .widthRel(1f);

        return Flow.column()
            .name("depositWidget")
            .background(GuiTextures.SLOT_ITEM)
            .width(LEFT_WIDTH)
            .coverChildrenHeight()
            .padding(4)
            .child(title)
            .child(depositSlotRow);
    }

    private static Flow rightColumn(WalletState state) {
        ListWidget<IWidget, ?> list = new ListWidget<>().fullWidth()
            .childSeparator(IIcon.EMPTY_2PX);
        if (state.currencyItems.isEmpty()) {
            list.child(text(IKey.lang("gui.nhkantan.wallet.no_currency_items"), RIGHT_WIDTH - SCROLLBAR_SPACE));
        }
        for (int i = 0; i < state.currencyItems.size(); i++) {
            list.child(exchangeRow(state, i));
        }
        return Flow.column()
            .width(RIGHT_WIDTH)
            .fullHeight()
            .childPadding(GAP)
            .child(text(IKey.lang("gui.nhkantan.wallet.exchange_list")).fullWidth())
            .child(list.expanded());
    }

    /**
     * A selectable row: icon, name and the exchange rates, {@code +credit} for deposits and {@code -cost} for
     * withdrawals. Only withdrawable items can be selected.
     */
    private static ToggleButton exchangeRow(WalletState state, int index) {
        CurrencyItem item = state.currencyItems.get(index);
        ItemStack stack = item.item()
            .createStack(1);
        return new ToggleButton().fullWidth().height(ITEM_SIZE)
            .value(
                new BoolValue.Dynamic(
                    () -> state.selection == index,
                    selected -> { if (selected && item.canWithdraw()) state.select(index); }))
            .child(
                Flow.row()
                    .sizeRel(1f).padding(2,0)
                    .childPadding(ROW_PADDING)
                    .child(
                        new ItemDisplayWidget().item(stack)
                            .size(ITEM_SIZE)
                            .disableThemeBackground(true)
                            .disableHoverThemeBackground(true))
                    .child(new TextWidget<>(rates(item)).height(ITEM_SIZE)));
    }

    /** The section title alone, or followed by the amount when it is not zero, e.g. {@code Deposit 6,000 NHC}. */
    private static String titleWithAmount(String langKey, long amount, @Nullable EnumChatFormatting color) {
        String title = StatCollector.translateToLocal(langKey);
        if (amount == 0) return title;
        return title + " " + (color == null ? "" : color) + formatAmount(amount);
    }

    /** {@code +credit} in green when depositable, {@code -cost} in red when withdrawable, per item. */
    private static String rates(CurrencyItem item) {
        StringBuilder text = new StringBuilder();
        if (item.canDeposit()) {
            text.append(EnumChatFormatting.DARK_GREEN)
                .append('+')
                .append(formatAmount(item.depositCredit(1)));
        }
        if (item.canWithdraw()) {
            if (text.length() > 0) text.append(' ');
            text.append(EnumChatFormatting.RED)
                .append('-')
                .append(formatAmount(item.withdrawCost(1)));
        }
        return text.toString();
    }

    /** Changes the client-only quantity; Shift multiplies the step. */
    private static ButtonWidget<?> stepButton(String label, IntValue.Dynamic quantity, int direction) {
        return new ButtonWidget<>().size(STEP_BUTTON_WIDTH + 1, LINE_HEIGHT + 2)
            .overlay(IKey.str(label))
            .addTooltipLine(IKey.lang("gui.nhkantan.wallet.step_hint", SHIFT_STEP))
            .onMousePressed(mouseButton -> {
                if (mouseButton != 0) return false;
                int step = Interactable.hasShiftDown() ? SHIFT_STEP : 1;
                quantity.setIntValue(clampQuantity(quantity.getIntValue() + step * direction));
                return true;
            });
    }

    private static int clampQuantity(int quantity) {
        return Math.max(1, Math.min(MAX_QUANTITY, quantity));
    }

    private static TextWidget<?> text(IKey key) {
        return key.asWidget()
            .height(LINE_HEIGHT)
            .textAlign(Alignment.CenterLeft);
    }

    /** A single left-aligned line of text with a fixed width. */
    private static TextWidget<?> text(IKey key, int width) {
        return key.asWidget()
            .width(width)
            .height(LINE_HEIGHT)
            .textAlign(Alignment.CenterLeft);
    }

    /**
     * Runs {@code action} for left clicks on the server only. The handler also fires locally on the client before
     * syncing; running the action there too would apply it twice in single player, where both sides share services.
     */
    private static InteractionSyncHandler serverAction(ServerAction action) {
        return new InteractionSyncHandler()
            .setOnMousePressed(mouse -> { if (!mouse.isClient() && mouse.mouseButton == 0) action.run(); });
    }

    @FunctionalInterface
    private interface ServerAction {

        void run();
    }

    /**
     * State of one open wallet window on one side: deposit slots, selection, quantity and the synced balance.
     * Server-only
     * methods are only invoked from {@link #serverAction} callbacks.
     */
    private static class WalletState {

        private final EntityPlayer player;
        private final LongSyncValue balance;
        private final ItemStackHandler depositSlots = new ItemStackHandler(DEPOSIT_SLOTS);
        private final List<CurrencyItem> currencyItems = ExchangeItems.currencyItems();
        private final List<DebtItem> debtItems = ExchangeItems.debtItems();
        /** Index of the selected currency item, or {@link #NO_SELECTION}; synced client to server. */
        private final PanelSyncManager syncManager;
        /** Client-only: index of the selected currency item, or {@link #NO_SELECTION}. */
        private int selection = NO_SELECTION;
        /** Client-only: quantity to withdraw. */
        private int quantity = 1;
        private final IntValue.Dynamic quantityValue = new IntValue.Dynamic(
            () -> quantity,
            value -> quantity = clampQuantity(value));

        private WalletState(EntityPlayer player, PanelSyncManager syncManager) {
            this.player = player;
            this.syncManager = syncManager;
            // The server reads the wallet; the client only shows the synced value
            this.balance = new LongSyncValue(() -> 0L, () -> {
                WalletService service = WalletService.get();
                return service == null ? 0L : service.balance(player.getUniqueID());
            });
            syncManager.syncValue("wallet_balance", balance);
            syncManager.registerSyncedAction(
                WITHDRAW_ACTION,
                Side.SERVER,
                packet -> withdraw(packet.readVarIntFromBuffer(), packet.readVarIntFromBuffer()));
            syncManager.registerSyncedAction(WITHDRAWN_ACTION, Side.CLIENT, packet -> selection = NO_SELECTION);
            syncManager.registerSlotGroup(DEPOSIT_GROUP, DEPOSIT_SLOTS);
            syncManager.addCloseListener(closing -> { if (!closing.worldObj.isRemote) returnDepositSlots(); });
        }

        private long balance() {
            return balance.getLongValue();
        }

        private boolean inDebt() {
            return balance() < 0;
        }

        private boolean acceptsDeposit(ItemStack stack) {
            return inDebt() ? findDebtItem(stack) != null : findDepositItem(stack) != null;
        }

        /** Changes the selected item; the chosen quantity is kept. */
        private void select(int index) {
            selection = index;
        }

        /** Client: sets the quantity to the most the balance affords for the selected item. */
        private void selectMaxQuantity() {
            CurrencyItem item = selected();
            if (item != null) quantity = clampQuantity(item.affordable(balance(), MAX_QUANTITY));
        }

        /** The selected item for the preview slot, or {@code null} for an empty slot. */
        @Nullable
        private ItemStack selectedStack() {
            CurrencyItem item = selected();
            return item == null ? null
                : item.item()
                    .createStack(1);
        }

        /** Client: the selected withdrawable item, or {@code null}. */
        @Nullable
        private CurrencyItem selected() {
            return withdrawable(selection);
        }

        /** The currency item at {@code index} if it exists and can be withdrawn; validates indices from the client. */
        @Nullable
        private CurrencyItem withdrawable(int index) {
            if (index < 0 || index >= currencyItems.size()) return null;
            CurrencyItem item = currencyItems.get(index);
            return item.canWithdraw() ? item : null;
        }

        private long cost() {
            CurrencyItem item = selected();
            return item == null ? 0L : item.withdrawCost(quantity);
        }

        @Nullable
        private CurrencyItem findCurrencyItem(ItemStack stack) {
            for (CurrencyItem item : currencyItems) {
                if (item.item()
                    .matches(stack)) return item;
            }
            return null;
        }

        @Nullable
        private DebtItem findDebtItem(ItemStack stack) {
            for (DebtItem item : debtItems) {
                if (item.item()
                    .matches(stack)) return item;
            }
            return null;
        }

        /** Only depositable currency items count; disabled directions are ignored. */
        @Nullable
        private CurrencyItem findDepositItem(ItemStack stack) {
            CurrencyItem item = findCurrencyItem(stack);
            return item != null && item.canDeposit() ? item : null;
        }

        /** Currency the deposit slots would credit, or repay while in debt. */
        private long depositPreview() {
            long total = 0;
            long debt = -balance();
            for (int i = 0; i < DEPOSIT_SLOTS; i++) {
                ItemStack stack = depositSlots.getStackInSlot(i);
                if (stack == null) continue;
                if (debt > 0) {
                    DebtItem item = findDebtItem(stack);
                    if (item != null) total += item.repayment(stack.stackSize);
                } else {
                    CurrencyItem item = findDepositItem(stack);
                    if (item != null) total += item.depositCredit(stack.stackSize);
                }
            }
            return debt > 0 ? Math.min(total, debt) : total;
        }

        /** Server: deposits the slot contents, or repays debt with them while in debt. */
        private void settleDeposit() {
            WalletService service = WalletService.get();
            if (service == null) return;
            UUID id = player.getUniqueID();
            boolean repaying = service.balance(id) < 0;
            for (int i = 0; i < DEPOSIT_SLOTS; i++) {
                ItemStack stack = depositSlots.getStackInSlot(i);
                if (stack == null) continue;
                WalletResult result = settleSlot(service, id, stack, repaying);
                if (result == null || !result.status()
                    .isSuccess()) continue;
                stack.stackSize -= result.itemCount();
                depositSlots.setStackInSlot(i, stack.stackSize > 0 ? stack : null);
            }
        }

        @Nullable
        private WalletResult settleSlot(WalletService service, UUID id, ItemStack stack, boolean repaying) {
            if (repaying) {
                DebtItem item = findDebtItem(stack);
                return item == null ? null : service.repay(id, item, stack.stackSize);
            }
            CurrencyItem item = findDepositItem(stack);
            return item == null ? null : service.deposit(id, item, stack.stackSize);
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

        /** Server: validates and performs a withdrawal request, then tells the client to clear its selection. */
        private void withdraw(int index, int count) {
            WalletService service = WalletService.get();
            CurrencyItem item = withdrawable(index);
            if (service == null || item == null || count < 1 || count > MAX_QUANTITY) return;
            if (!PlayerInventories.canFit(player.inventory, Collections.singletonMap(item.item(), count))) return;
            if (!service.withdraw(player.getUniqueID(), item, count)
                .status()
                .isSuccess()) return;
            PlayerInventories.give(player, item.item(), count);
            syncManager.callSyncedAction(WITHDRAWN_ACTION);
        }

        private void returnDepositSlots() {
            for (int i = 0; i < DEPOSIT_SLOTS; i++) {
                PlayerInventories.returnToPlayer(player, depositSlots.getStackInSlot(i));
                depositSlots.setStackInSlot(i, null);
            }
        }
    }
}
