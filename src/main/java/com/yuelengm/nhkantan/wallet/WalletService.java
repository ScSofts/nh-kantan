package com.yuelengm.nhkantan.wallet;

import java.util.Collections;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.storage.MapStorage;

import com.yuelengm.nhkantan.config.CurrencyConfig;
import com.yuelengm.nhkantan.util.MathUtils;
import com.yuelengm.nhkantan.util.PlayerInventories;
import com.yuelengm.nhkantan.wallet.WalletResult.Status;

/**
 * Server-side owner of all player wallets. Every operation runs on the server thread and changes the balance and the
 * items together, so a request is applied completely or not at all.
 * <p>
 * Rules: deposits and withdrawals are refused while the balance is negative; then only debt items are accepted, and
 * repaying never raises the balance above zero.
 */
public class WalletService {

    @Nullable
    private static WalletService instance;

    private final WalletSavedData data;

    private WalletService(WalletSavedData data) {
        this.data = data;
    }

    /** The running service, or {@code null} when no server is running. */
    @Nullable
    public static WalletService get() {
        return instance;
    }

    public static void start(MinecraftServer server) {
        // The overworld map storage is global and shared by every dimension
        MapStorage storage = server.worldServerForDimension(0).mapStorage;
        WalletSavedData data = (WalletSavedData) storage.loadData(WalletSavedData.class, WalletSavedData.DATA_NAME);
        if (data == null) {
            data = new WalletSavedData(WalletSavedData.DATA_NAME);
            storage.setData(WalletSavedData.DATA_NAME, data);
        }
        instance = new WalletService(data);
    }

    public static void stop() {
        instance = null;
    }

    /** The player's balance, or 0 when no server is running (e.g. on a remote client). */
    public static long balanceOf(EntityPlayer player) {
        WalletService service = instance;
        return service == null ? 0L : service.balance(player.getUniqueID());
    }

    /**
     * Currency the given stacks would credit, or repay while {@code balance} is negative; repayment is capped at the
     * debt. Stacks that are not accepted in the current state are ignored. Shared by the GUI preview and
     * {@link #settleStack}.
     */
    public static long settlementPreview(long balance, Iterable<ItemStack> stacks) {
        long total = 0;
        for (ItemStack stack : stacks) {
            if (stack == null) continue;
            if (balance < 0) {
                DebtItem item = ExchangeItems.findDebt(stack);
                if (item != null) total = MathUtils.saturatedAdd(total, item.repayment(stack.stackSize));
            } else {
                CurrencyItem item = ExchangeItems.findDeposit(stack);
                if (item != null) total = MathUtils.saturatedAdd(total, item.depositCredit(stack.stackSize));
            }
        }
        return balance < 0 ? Math.min(total, -balance) : total;
    }

    /** Whether a stack can be handed in at the given balance: deposit items normally, debt items while in debt. */
    public static boolean acceptsStack(long balance, ItemStack stack) {
        return balance < 0 ? ExchangeItems.findDebt(stack) != null : ExchangeItems.findDeposit(stack) != null;
    }

    /** The balance, creating the account with the configured initial balance on first access. */
    public long balance(UUID player) {
        if (!data.has(player)) data.set(player, CurrencyConfig.initialBalance);
        return data.get(player);
    }

    public void setBalance(UUID player, long balance) {
        data.set(player, balance);
    }

    /**
     * Hands in a stack: deposits it, or repays debt with it while in debt. The stack size is reduced by the items
     * used; repaying never uses more items than needed.
     *
     * @return items consumed from the stack
     */
    public int settleStack(EntityPlayer player, ItemStack stack) {
        UUID id = player.getUniqueID();
        WalletResult result;
        if (balance(id) < 0) {
            DebtItem item = ExchangeItems.findDebt(stack);
            if (item == null) return 0;
            result = repay(id, item, stack.stackSize);
        } else {
            CurrencyItem item = ExchangeItems.findDeposit(stack);
            if (item == null) return 0;
            result = deposit(id, item, stack.stackSize);
        }
        if (!result.status()
            .isSuccess()) return 0;
        stack.stackSize -= result.itemCount();
        return result.itemCount();
    }

    /** Charges a withdrawal and puts the items into the player's inventory; refused if they would not all fit. */
    public WalletResult withdrawToInventory(EntityPlayer player, CurrencyItem item, int count) {
        UUID id = player.getUniqueID();
        if (!PlayerInventories.canFit(player.inventory, Collections.singletonMap(item.item(), count))) {
            return WalletResult.failure(Status.INVENTORY_FULL, balance(id));
        }
        WalletResult result = withdraw(id, item, count);
        if (result.status()
            .isSuccess()) PlayerInventories.give(player, item.item(), count);
        return result;
    }

    /** Credits {@code count} deposited items. The caller removes exactly that many items on success. */
    public WalletResult deposit(UUID player, CurrencyItem item, int count) {
        long balance = balance(player);
        if (balance < 0) return WalletResult.failure(Status.IN_DEBT, balance);
        if (!item.canDeposit()) return WalletResult.failure(Status.NOT_EXCHANGEABLE, balance);
        if (count <= 0) return WalletResult.failure(Status.NO_ITEMS, balance);
        long credit = item.depositCredit(count);
        long updated = MathUtils.saturatedAdd(balance, credit);
        data.set(player, updated);
        return new WalletResult(Status.DEPOSITED, count, credit, updated);
    }

    /**
     * Repays debt with up to {@code offered} items, never using more than needed to reach zero. The caller removes
     * {@link WalletResult#itemCount()} items on success.
     */
    public WalletResult repay(UUID player, DebtItem item, int offered) {
        long balance = balance(player);
        if (balance >= 0) return WalletResult.failure(Status.NOT_IN_DEBT, balance);
        int count = Math.min(offered, item.itemsToClear(-balance));
        if (count <= 0) return WalletResult.failure(Status.NO_ITEMS, balance);
        long updated = Math.min(0L, MathUtils.saturatedAdd(balance, item.repayment(count)));
        data.set(player, updated);
        return new WalletResult(Status.REPAID, count, updated - balance, updated);
    }

    /**
     * Charges the withdrawal of {@code count} items, or nothing if the balance does not cover it. The caller hands
     * out the items on success; prefer {@link #withdrawToInventory}.
     */
    public WalletResult withdraw(UUID player, CurrencyItem item, int count) {
        long balance = balance(player);
        if (balance < 0) return WalletResult.failure(Status.IN_DEBT, balance);
        if (!item.canWithdraw()) return WalletResult.failure(Status.NOT_EXCHANGEABLE, balance);
        if (count <= 0) return WalletResult.failure(Status.NO_ITEMS, balance);
        long cost = item.withdrawCost(count);
        if (cost > balance) return WalletResult.failure(Status.INSUFFICIENT_BALANCE, balance);
        long updated = balance - cost;
        data.set(player, updated);
        return new WalletResult(Status.WITHDRAWN, count, cost, updated);
    }
}
