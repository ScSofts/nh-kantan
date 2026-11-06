package com.yuelengm.nhkantan.gui.util;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.value.IBoolValue;
import com.cleanroommc.modularui.drawable.GuiTextures;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.BoolValue;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.ToggleButton;
import com.cleanroommc.modularui.widgets.layout.Flow;

/**
 * A traditional checkbox row: a disabled-button-style square followed by text. The entire row toggles the value.
 */
public class CheckboxWidget extends ToggleButton {

    private static final int BOX_SIZE = 16;
    private static final int ROW_HEIGHT = 16;
    private static final int BOX_GAP = 4;

    private IBoolValue<?> boolValue = new BoolValue(false);

    public CheckboxWidget(IKey label) {
        widthRel(1f);
        height(ROW_HEIGHT);
        disableThemeBackground(true);
        disableHoverThemeBackground(true);
        child(false, row(label, false));
        child(true, row(label, true));
        value(boolValue);
    }

    @Override
    public CheckboxWidget value(IBoolValue<?> value) {
        this.boolValue = value;
        super.value(value);
        return this;
    }

    public boolean isChecked() {
        return boolValue.getBoolValue();
    }

    public CheckboxWidget setChecked(boolean checked) {
        boolValue.setBoolValue(checked);
        return this;
    }

    private static Flow row(IKey label, boolean checked) {
        Widget<?> box = new Widget<>().size(BOX_SIZE)
            .background(GuiTextures.MC_BUTTON_DISABLED);
        if (checked) box.overlay(Assets.CHECKMARK);
        TextWidget<?> text = label.asWidget()
            .height(ROW_HEIGHT)
            .textAlign(Alignment.CenterLeft)
            .expanded();
        return Flow.row()
            .fullWidth()
            .height(ROW_HEIGHT)
            .childPadding(BOX_GAP)
            .child(box)
            .child(text);
    }
}
