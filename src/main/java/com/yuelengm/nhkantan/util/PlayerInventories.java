package com.yuelengm.nhkantan.util;

import java.util.Map;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;

/**
 * Space checks and insertion for a player's main inventory (hotbar included, armor excluded).
 */
public class PlayerInventories {

    private PlayerInventories() {}

    /** Whether all items of the order fit at once; different item types compete for the same empty slots. */
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

    private static int maxStackSize(InventoryPlayer inventory, ItemStack template) {
        return Math.min(template.getMaxStackSize(), inventory.getInventoryStackLimit());
    }
}
