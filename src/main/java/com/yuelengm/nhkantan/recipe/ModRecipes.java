package com.yuelengm.nhkantan.recipe;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

import com.yuelengm.nhkantan.block.ModBlocks;
import com.yuelengm.nhkantan.item.ModItems;

import cpw.mods.fml.common.registry.GameRegistry;

/**
 * Crafting recipes for the market display and handheld terminal.
 */
public class ModRecipes {

    private ModRecipes() {}

    public static void register() {
        GameRegistry.addRecipe(
            new ItemStack(ModBlocks.marketDisplay),
            "GEG",
            "GLG",
            "GRG",
            'G',
            Blocks.glass_pane,
            'E',
            Items.emerald,
            'L',
            Blocks.redstone_lamp,
            'R',
            Items.redstone);

        GameRegistry.addRecipe(
            new ItemStack(ModItems.marketTerminal),
            " E ",
            "IBI",
            " R ",
            'E',
            Items.emerald,
            'I',
            Items.iron_ingot,
            'B',
            Items.book,
            'R',
            Items.redstone);
    }
}
