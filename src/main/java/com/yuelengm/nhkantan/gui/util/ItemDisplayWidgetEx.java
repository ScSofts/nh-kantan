package com.yuelengm.nhkantan.gui.util;

import net.minecraft.item.ItemStack;

import com.cleanroommc.modularui.api.value.IValue;
import com.cleanroommc.modularui.screen.RichTooltip;
import com.cleanroommc.modularui.widgets.ItemDisplayWidget;

/**
 * Item display widget that automatically shows the vanilla item tooltip for the currently displayed stack.
 */
public class ItemDisplayWidgetEx extends ItemDisplayWidget {

    public ItemDisplayWidgetEx() {
        tooltipAutoUpdate(true);
        tooltipDynamic(this::buildItemTooltip);
    }

    private void buildItemTooltip(RichTooltip tooltip) {
        IValue<ItemStack> displayed = getValue();
        if (displayed == null) return;
        ItemStack stack = displayed.getValue();
        if (stack == null) return;
        tooltip.addFromItem(stack);
    }
}
