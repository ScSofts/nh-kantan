package com.yuelengm.nhkantan.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import com.cleanroommc.modularui.api.IGuiHolder;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.factory.GuiData;
import com.cleanroommc.modularui.factory.SimpleGuiFactory;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.utils.item.ItemStackHandler;
import com.cleanroommc.modularui.value.sync.IntSyncValue;
import com.cleanroommc.modularui.value.sync.InteractionSyncHandler;
import com.cleanroommc.modularui.value.sync.LongSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ItemDisplayWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.SlotGroupWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
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
import com.yuelengm.nhkantan.wallet.ItemSpec;
import com.yuelengm.nhkantan.wallet.PlayerInventories;
import com.yuelengm.nhkantan.wallet.WalletResult;
import com.yuelengm.nhkantan.wallet.WalletService;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * The wallet window, opened from the market terminal. Left: wallet info, deposit slots and the player inventory.
 * Right: a scrollable withdrawal list where a quantity is chosen per currency item, like a shopping cart, and the
 * checkout bar below it.
 * <p>
 * Built identically on client and server. Actions run on the server through {@link InteractionSyncHandler}s; the
 * balance comes from a server-driven {@link LongSyncValue} and cart quantities are synced client to server. While in
 * debt the deposit slots accept debt items for repayment and the withdrawal list is locked.
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
    private static final int DEPOSIT_SLOTS = 9;
    private static final String DEPOSIT_GROUP = "nhkantan_deposit";
    private static final int LIST_HEIGHT = 115;
    private static final int SCROLLBAR_SPACE = 6;
    private static final int ROW_HEIGHT = 20;
    private static final int ITEM_SIZE = 16;
    private static final int STEP_BUTTON_WIDTH = 10;
    private static final int QUANTITY_WIDTH = 28;
    private static final int CHECKOUT_HEIGHT = 16;
    private static final int MAX_QUANTITY = 9999;
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
        return ModularPanel.defaultPanel("nhkantan_wallet", WIDTH, HEIGHT)
            .child(
                Flow.row()
                    .sizeRel(1f)
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
        return Flow.column()
            .width(LEFT_WIDTH)
            .heightRel(1f)
            .childPadding(GAP)
            .child(infoBox(state))
            .child(text(IKey.lang("gui.nhkantan.wallet.deposit_slots"), LEFT_WIDTH))
            .child(depositRow(state))
            .child(text(IKey.lang("container.inventory"), LEFT_WIDTH))
            .child(SlotGroupWidget.playerInventory((index, slot) -> slot));
    }

    private static Flow infoBox(WalletState state) {
        return Flow.column()
            .width(LEFT_WIDTH)
            .coverChildrenHeight()
            .childPadding(2)
            .child(text(IKey.lang("gui.nhkantan.wallet.title"), LEFT_WIDTH - CLOSE_BUTTON_SPACE))
            .child(
                text(
                    IKey.lang("gui.nhkantan.wallet.balance", () -> new Object[] { formatBalance(state.balance()) }),
                    LEFT_WIDTH));
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

        Flow depositConfirmRow = Flow.row()
            .fullWidth()
            .height(SLOT_SIZE)
            .child(
                new TextWidget<>(
                    IKey.lang(
                        "gui.nhkantan.wallet.deposit_preview",
                        () -> new Object[] { formatAmount(state.depositPreview()) })).expanded())
            .child(
                new ButtonWidget<>().size(48, 16)
                    .overlay(IKey.lang("gui.nhkantan.wallet.deposit"))
                    .syncHandler(serverAction(state::settleDeposit)));

        return Flow.column()
            .fullWidth()
            .coverChildrenHeight()
            .child(depositSlotRow)
            .child(depositConfirmRow);
    }

    private static Flow rightColumn(WalletState state) {
        ListWidget<IWidget, ?> list = new ListWidget<>().width(RIGHT_WIDTH)
            .height(LIST_HEIGHT)
            .setEnabledIf(w -> !state.inDebt());
        if (state.currencyItems.isEmpty()) {
            list.child(text(IKey.lang("gui.nhkantan.wallet.no_currency_items"), RIGHT_WIDTH - SCROLLBAR_SPACE));
        }
        for (int i = 0; i < state.currencyItems.size(); i++) {
            list.child(withdrawRow(state.currencyItems.get(i), state.quantities.get(i)));
        }
        return Flow.column()
            .width(RIGHT_WIDTH)
            .heightRel(1f)
            .childPadding(GAP)
            .collapseDisabledChild()
            .child(text(IKey.lang("gui.nhkantan.wallet.withdraw_list"), RIGHT_WIDTH - CLOSE_BUTTON_SPACE))
            .child(list)
            .child(
                text(IKey.lang("gui.nhkantan.wallet.withdraw_locked"), RIGHT_WIDTH).setEnabledIf(w -> state.inDebt()))
            .child(
                text(
                    IKey.dynamic(
                        () -> StatCollector.translateToLocalFormatted(
                            "gui.nhkantan.wallet.total",
                            (state.orderCost() > state.balance() ? EnumChatFormatting.RED : "")
                                + formatAmount(state.orderCost()))),
                    RIGHT_WIDTH))
            .child(
                new ButtonWidget<>().size(RIGHT_WIDTH, CHECKOUT_HEIGHT)
                    .overlay(IKey.lang("gui.nhkantan.wallet.withdraw"))
                    .syncHandler(serverAction(state::checkout)));
    }

    /** Item icon, name over unit cost, and the quantity selector. */
    private static Flow withdrawRow(CurrencyItem item, IntSyncValue quantity) {
        int width = RIGHT_WIDTH - SCROLLBAR_SPACE;
        int textWidth = width - ITEM_SIZE - STEP_BUTTON_WIDTH * 2 - QUANTITY_WIDTH - 2 * 4;
        ItemStack stack = item.item()
            .createStack(1);
        return Flow.row()
            .width(width)
            .height(ROW_HEIGHT)
            .childPadding(2)
            .child(
                new ItemDisplayWidget().item(stack)
                    .size(ITEM_SIZE))
            .child(
                Flow.column()
                    .width(textWidth)
                    .height(ROW_HEIGHT)
                    .child(text(IKey.dynamic(stack::getDisplayName), textWidth))
                    .child(text(IKey.lang("gui.nhkantan.wallet.unit_cost", item.withdrawCost(1)), textWidth)))
            .child(stepButton("-", quantity, -1))
            .child(
                new TextFieldWidget().size(QUANTITY_WIDTH, LINE_HEIGHT + 2)
                    .numbersLong(0, MAX_QUANTITY)
                    .value(quantity))
            .child(stepButton("+", quantity, 1));
    }

    /** Changes the quantity on the client; the synced value forwards it to the server. Shift multiplies the step. */
    private static ButtonWidget<?> stepButton(String label, IntSyncValue quantity, int direction) {
        return new ButtonWidget<>().size(STEP_BUTTON_WIDTH, LINE_HEIGHT + 2)
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
        return Math.max(0, Math.min(MAX_QUANTITY, quantity));
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
     * State of one open wallet window on one side: deposit slots, cart quantities and the synced balance. Server-only
     * methods are only invoked from {@link #serverAction} callbacks.
     */
    private static class WalletState {

        private final EntityPlayer player;
        private final LongSyncValue balance;
        private final ItemStackHandler depositSlots = new ItemStackHandler(DEPOSIT_SLOTS);
        private final List<CurrencyItem> currencyItems = ExchangeItems.currencyItems();
        private final List<DebtItem> debtItems = ExchangeItems.debtItems();
        private final List<IntSyncValue> quantities = new ArrayList<>();

        private WalletState(EntityPlayer player, PanelSyncManager syncManager) {
            this.player = player;
            // The server reads the wallet; the client only shows the synced value
            this.balance = new LongSyncValue(() -> 0L, () -> {
                WalletService service = WalletService.get();
                return service == null ? 0L : service.balance(player.getUniqueID());
            });
            syncManager.syncValue("wallet_balance", balance);
            for (int i = 0; i < currencyItems.size(); i++) {
                int[] holder = new int[1];
                IntSyncValue quantity = new IntSyncValue(() -> holder[0], value -> holder[0] = clampQuantity(value))
                    .allowC2S();
                syncManager.syncValue("wallet_quantity", i, quantity);
                quantities.add(quantity);
            }
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
            return inDebt() ? findDebtItem(stack) != null : findCurrencyItem(stack) != null;
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
                    CurrencyItem item = findCurrencyItem(stack);
                    if (item != null) total += item.depositCredit(stack.stackSize);
                }
            }
            return debt > 0 ? Math.min(total, debt) : total;
        }

        private Map<CurrencyItem, Integer> order() {
            Map<CurrencyItem, Integer> order = new LinkedHashMap<>();
            for (int i = 0; i < currencyItems.size(); i++) {
                int amount = quantities.get(i)
                    .getIntValue();
                if (amount > 0) order.put(currencyItems.get(i), amount);
            }
            return order;
        }

        private long orderCost() {
            return WalletService.orderCost(order());
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
            CurrencyItem item = findCurrencyItem(stack);
            return item == null ? null : service.deposit(id, item, stack.stackSize);
        }

        /** Server: charges the whole cart, hands out the items and clears the cart. */
        private void checkout() {
            WalletService service = WalletService.get();
            if (service == null) return;
            UUID id = player.getUniqueID();
            Map<CurrencyItem, Integer> order = order();
            Map<ItemSpec, Integer> items = new LinkedHashMap<>();
            for (Map.Entry<CurrencyItem, Integer> line : order.entrySet()) {
                items.put(
                    line.getKey()
                        .item(),
                    line.getValue());
            }
            if (!PlayerInventories.canFit(player.inventory, items)) return;
            if (!service.withdraw(id, order)
                .status()
                .isSuccess()) return;
            for (Map.Entry<ItemSpec, Integer> entry : items.entrySet()) {
                PlayerInventories.give(player, entry.getKey(), entry.getValue());
            }
            for (IntSyncValue quantity : quantities) quantity.setIntValue(0);
        }

        private void returnDepositSlots() {
            for (int i = 0; i < DEPOSIT_SLOTS; i++) {
                PlayerInventories.returnToPlayer(player, depositSlots.getStackInSlot(i));
                depositSlots.setStackInSlot(i, null);
            }
        }
    }
}
