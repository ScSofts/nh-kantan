package com.yuelengm.nhkantan.util;

import java.util.Map;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;

/**
 * Space checks and insertion for a player's main inventory (hotbar included, armor excluded).
 */
public class PlayerInventories {

    private PlayerInventories() {}

    /**
     * The largest amount of units, at most {@code requested}, whose split into {@code family} fits into the inventory.
     * Splits are not monotone in size (more units can need fewer items), so this is the largest fitting value found by
     * binary search, which is exact for single-item families.
     */
    public static int largestFitting(InventoryPlayer inventory, Denominations family, int requested) {
        int low = 0;
        int high = requested;
        while (low < high) {
            int mid = low + (high - low + 1) / 2;
            if (canFit(inventory, family.split(mid))) low = mid;
            else high = mid - 1;
        }
        return low;
    }

    /** Whether all items fit at once; different item types compete for the same empty slots. */
    public static boolean canFit(InventoryPlayer inventory, Map<ItemSpec, Integer> items) {
        int emptySlots = 0;
        for (ItemStack stack : inventory.mainInventory) {
            if (stack == null) emptySlots++;
        }
        for (Map.Entry<ItemSpec, Integer> entry : items.entrySet()) {
            ItemStack template = entry.getKey()
                .createStack(1);
            int max = maxStackSize(inventory, template);
            long remaining = entry.getValue();
            for (ItemStack stack : inventory.mainInventory) {
                if (stack != null && stack.isItemEqual(template) && !stack.hasTagCompound()) {
                    remaining -= Math.max(0, max - stack.stackSize);
                }
            }
            if (remaining > 0) emptySlots -= (int) ((remaining + max - 1) / max);
            if (emptySlots < 0) return false;
        }
        return true;
    }

    /** Inserts all items; callers check {@link #canFit} first, anything left over is dropped. */
    public static void give(EntityPlayer player, Map<ItemSpec, Integer> items) {
        for (Map.Entry<ItemSpec, Integer> entry : items.entrySet()) give(player, entry.getKey(), entry.getValue());
    }

    /** Inserts {@code amount} items; callers check {@link #canFit} first, anything left over is dropped. */
    public static void give(EntityPlayer player, ItemSpec item, int amount) {
        InventoryPlayer inventory = player.inventory;
        int max = maxStackSize(inventory, item.createStack(1));
        int remaining = amount;
        while (remaining > 0) {
            int size = Math.min(max, remaining);
            remaining -= size;
            returnToPlayer(player, item.createStack(size));
        }
        inventory.markDirty();
    }

    /** Puts a stack into the inventory, dropping whatever does not fit at the player's feet. */
    public static void returnToPlayer(EntityPlayer player, ItemStack stack) {
        if (stack == null || stack.stackSize <= 0) return;
        player.inventory.addItemStackToInventory(stack);
        if (stack.stackSize > 0) player.dropPlayerItemWithRandomChoice(stack, false);
    }

    /**
     * Server: sends the whole player inventory to the client. Needed after changing it while a custom container closes:
     * the inventory container's change tracking was not updated while the other container was open, so it may see no
     * difference and never send the changed slots.
     */
    public static void resync(EntityPlayer player) {
        if (player instanceof EntityPlayerMP serverPlayer) {
            serverPlayer.sendContainerToPlayer(player.inventoryContainer);
        }
    }

    private static int maxStackSize(InventoryPlayer inventory, ItemStack template) {
        return Math.min(template.getMaxStackSize(), inventory.getInventoryStackLimit());
    }
}
