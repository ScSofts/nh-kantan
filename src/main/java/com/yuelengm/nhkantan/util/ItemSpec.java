package com.yuelengm.nhkantan.util;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

/**
 * An item type identified by item and metadata. {@link OreDictionary#WILDCARD_VALUE} matches every metadata.
 * Stacks carrying NBT never match, so named or enchanted items are not exchanged by accident.
 */
public class ItemSpec {

    private final Item item;
    private final int meta;

    public ItemSpec(Item item, int meta) {
        this.item = item;
        this.meta = meta;
    }

    public Item item() {
        return item;
    }

    public int meta() {
        return meta;
    }

    public boolean matches(ItemStack stack) {
        return stack != null && stack.getItem() == item
            && (meta == OreDictionary.WILDCARD_VALUE || stack.getItemDamage() == meta)
            && !stack.hasTagCompound();
    }

    /** A stack of this item; the wildcard is resolved to metadata 0. */
    public ItemStack createStack(int count) {
        return new ItemStack(item, count, meta == OreDictionary.WILDCARD_VALUE ? 0 : meta);
    }
}
