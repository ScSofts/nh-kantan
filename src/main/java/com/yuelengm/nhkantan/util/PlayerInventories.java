package com.yuelengm.nhkantan.util;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;

/**
 * Space checks and insertion for a player's main inventory (hotbar included, armor excluded).
 */
public class PlayerInventories {

    private PlayerInventories() {}

    /** How many items of this type still fit: free room in matching stacks plus empty slots. */
    public static int freeSpace(InventoryPlayer inventory, ItemSpec item) {
        ItemStack template = item.createStack(1);
        int max = maxStackSize(inventory, template);
        long space = 0;
        for (ItemStack stack : inventory.mainInventory) {
            if (stack == null) {
                space += max;
            } else if (stack.isItemEqual(template) && !stack.hasTagCompound()) {
                space += Math.max(0, max - stack.stackSize);
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, space);
    }

    /** Inserts {@code amount} items; callers check {@link #freeSpace} first, anything left over is dropped. */
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
