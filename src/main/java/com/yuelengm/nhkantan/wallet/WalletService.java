package com.yuelengm.nhkantan.wallet;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.storage.MapStorage;

import com.yuelengm.nhkantan.config.CurrencyConfig;
import com.yuelengm.nhkantan.wallet.WalletResult.Status;

/**
 * Server-side owner of all player wallets. It only changes balances; callers move the items in the same server tick,
 * right after a successful result, so an operation is applied completely or not at all.
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

    /** The balance, creating the account with the configured initial balance on first access. */
    public long balance(UUID player) {
        if (!data.has(player)) data.set(player, CurrencyConfig.initialBalance);
        return data.get(player);
    }

    public void setBalance(UUID player, long balance) {
        data.set(player, balance);
    }

    /** Credits {@code count} deposited items. The caller removes exactly that many items on success. */
    public WalletResult deposit(UUID player, CurrencyItem item, int count) {
        long balance = balance(player);
        if (balance < 0) return WalletResult.failure(Status.IN_DEBT, balance);
        if (!item.canDeposit()) return WalletResult.failure(Status.NOT_EXCHANGEABLE, balance);
        if (count <= 0) return WalletResult.failure(Status.NO_ITEMS, balance);
        long credit = item.depositCredit(count);
        long updated = saturatedAdd(balance, credit);
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
        long updated = Math.min(0L, saturatedAdd(balance, item.repayment(count)));
        data.set(player, updated);
        return new WalletResult(Status.REPAID, count, updated - balance, updated);
    }

    /**
     * Charges the withdrawal of {@code count} items, or nothing if the balance does not cover it. The caller checks
     * that the items fit before calling and hands them out on success.
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

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        // Overflow happens only when both operands share a sign that the sum does not
        if (((a ^ sum) & (b ^ sum)) < 0) return a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        return sum;
    }
}
