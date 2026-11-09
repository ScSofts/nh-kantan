package com.yuelengm.nhkantan.market.server;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagList;

import com.yuelengm.nhkantan.market.order.ExchangeRuntime;
import com.yuelengm.nhkantan.market.order.MarginLedger;
import com.yuelengm.nhkantan.util.ItemSpec;
import com.yuelengm.nhkantan.util.PlayerInventories;
import com.yuelengm.nhkantan.wallet.CurrencyItem;
import com.yuelengm.nhkantan.wallet.ExchangeItems;

/** Server-side wallet adapter operating directly on the player's inventory, never on unpersisted GUI slots. */
public final class ExchangeWallet {

    private final ExchangeRuntime runtime;
    private final InventoryTransfers transfers;

    public ExchangeWallet(ExchangeRuntime runtime, InventoryTransfers transfers) {
        this.runtime = runtime;
        this.transfers = transfers;
    }

    public boolean exchange(EntityPlayerMP player, int index, int requested, boolean withdrawal) throws IOException {
        if (requested < 1 || requested > 1000000) throw new IllegalArgumentException("Invalid exchange quantity");
        MarginLedger.AccountView account = InventoryTransfers.await(runtime.account(player.getUniqueID()));
        boolean debt = account.cash < 0;
        List<CurrencyItem> items = debt ? ExchangeItems.debtItems() : ExchangeItems.currencyItems();
        if (index < 0 || index >= items.size()) throw new IllegalArgumentException("Invalid exchange item");
        CurrencyItem item = items.get(index);
        InventoryPlayer copy = new InventoryPlayer(player);
        copy.readFromNBT(player.inventory.writeToNBT(new NBTTagList()));
        long delta;
        if (withdrawal) {
            if (debt || !item.canWithdraw()) throw new IllegalArgumentException("Withdrawal is unavailable");
            BigDecimal value = cents(item.withdrawValue());
            long funds = Math.max(0, Math.min(account.cash, account.available));
            int count = BigDecimal.valueOf(funds)
                .divide(value, 0, RoundingMode.FLOOR)
                .min(BigDecimal.valueOf(requested))
                .intValueExact();
            if (count < 1) throw new IllegalArgumentException("Insufficient available funds");
            insert(
                copy,
                item.items()
                    .split(count));
            delta = -value.multiply(BigDecimal.valueOf(count))
                .setScale(0, RoundingMode.CEILING)
                .longValueExact();
        } else {
            if (debt ? !item.canRepay() : !item.canDeposit())
                throw new IllegalArgumentException("Deposit is unavailable");
            long available = 0;
            for (ItemStack stack : copy.mainInventory) {
                if (stack != null) available = Math.addExact(
                    available,
                    item.items()
                        .unitsOf(stack));
            }
            int count = (int) Math.min(requested, available);
            BigDecimal value = cents(item.depositValue());
            if (debt) {
                int debtUnits = BigDecimal.valueOf(account.cash)
                    .negate()
                    .divide(value, 0, RoundingMode.CEILING)
                    .min(BigDecimal.valueOf(count))
                    .intValueExact();
                count = Math.min(count, debtUnits);
            }
            if (count < 1) throw new IllegalArgumentException("No matching inventory items");
            long removed = 0;
            for (int slot = 0; slot < copy.mainInventory.length && removed < count; slot++) {
                ItemStack stack = copy.mainInventory[slot];
                int units = item.items()
                    .unitsPer(stack);
                if (units <= 0) continue;
                int take = (int) Math.min(stack.stackSize, (count - removed + units - 1) / units);
                removed += (long) take * units;
                stack.stackSize -= take;
                if (stack.stackSize == 0) copy.mainInventory[slot] = null;
            }
            if (removed > count) insert(
                copy,
                item.items()
                    .split(Math.toIntExact(removed - count)));
            BigDecimal credit = value.multiply(BigDecimal.valueOf(count))
                .setScale(0, RoundingMode.FLOOR);
            if (debt) credit = credit.min(
                BigDecimal.valueOf(account.cash)
                    .negate());
            delta = credit.longValueExact();
        }
        if (delta == 0) throw new IllegalArgumentException("Exchange amount rounds to zero");
        return transfers.transfer(player, PlayerInventoryStore.inventory(copy), delta, debt);
    }

    private static BigDecimal cents(double value) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException("Invalid exchange valuation");
        return BigDecimal.valueOf(value)
            .movePointRight(2);
    }

    private static void insert(InventoryPlayer inventory, Map<ItemSpec, Integer> items) {
        if (!PlayerInventories.canFit(inventory, items))
            throw new IllegalArgumentException("Inventory cannot fit exchange items");
        for (Map.Entry<ItemSpec, Integer> item : items.entrySet()) {
            int remaining = item.getValue();
            while (remaining > 0) {
                ItemStack stack = item.getKey()
                    .createStack(
                        Math.min(
                            remaining,
                            item.getKey()
                                .createStack(1)
                                .getMaxStackSize()));
                remaining -= stack.stackSize;
                if (!inventory.addItemStackToInventory(stack) || stack.stackSize > 0) {
                    throw new IllegalArgumentException("Inventory cannot fit the exchange or denomination change");
                }
            }
        }
    }
}
