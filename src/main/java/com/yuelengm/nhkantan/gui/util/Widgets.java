package com.yuelengm.nhkantan.gui.util;

import net.minecraft.item.ItemStack;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.value.IIntValue;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.GuiTextures;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ItemDisplayWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.yuelengm.nhkantan.util.MathUtils;

/**
 * Factories for commonly used ModularUI2 widgets. Safe to call on both sides.
 */
public class Widgets {

    /** Height of a single line of text. */
    public static final int LINE_HEIGHT = 10;
    /** Inner padding of a {@link #section}. */
    public static final int SECTION_PADDING = 4;

    private Widgets() {}

    /** A single left-aligned line of text; its width is left to the caller or the parent layout. */
    public static TextWidget<?> text(IKey key) {
        return text(key, Alignment.CenterLeft);
    }

    /** A single line of text with the given alignment; its width is left to the caller or the parent layout. */
    public static TextWidget<?> text(IKey key, Alignment alignment) {
        return key.asWidget()
            .height(LINE_HEIGHT)
            .textAlign(alignment);
    }

    /** A single left-aligned line of text with a fixed width. */
    public static TextWidget<?> text(IKey key, int width) {
        return text(key).width(width);
    }

    /** One full-width line: label on the left, value on the right. */
    public static Flow labeledValue(IKey label, IKey value) {
        return Flow.row()
            .fullWidth()
            .height(LINE_HEIGHT)
            .child(text(label).expanded())
            .child(text(value, Alignment.CenterRight));
    }

    /** A framed column with slot-like background, sized to its children. */
    public static Flow section(String name, int width) {
        return Flow.column()
            .name(name)
            .background(GuiTextures.SLOT_ITEM)
            .width(width)
            .coverChildrenHeight()
            .padding(SECTION_PADDING);
    }

    /** An item icon without the slot background. */
    public static ItemDisplayWidget itemIcon(ItemStack stack) {
        return new ItemDisplayWidgetEx().item(stack)
            .disableThemeBackground(true)
            .disableHoverThemeBackground(true);
    }

    /**
     * A button that changes a client-side integer by {@code step}, or {@code shiftStep} while Shift is held, clamped
     * to {@code [min, max]}.
     *
     * @param tooltip shown on hover, typically explaining the Shift step
     */
    public static ButtonWidget<?> stepButton(String label, IIntValue<?> value, int step, int shiftStep, int min,
        int max, IKey tooltip) {
        return new ButtonWidget<>().overlay(IKey.str(label))
            .addTooltipLine(tooltip)
            .onMousePressed(GuiActions.leftClick(() -> {
                int delta = Interactable.hasShiftDown() ? shiftStep : step;
                value.setIntValue(MathUtils.clamp(value.getIntValue() + delta, min, max));
            }));
    }
}
