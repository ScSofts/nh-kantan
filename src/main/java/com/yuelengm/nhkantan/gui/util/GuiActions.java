package com.yuelengm.nhkantan.gui.util;

import com.cleanroommc.modularui.api.widget.IGuiAction;
import com.cleanroommc.modularui.value.sync.InteractionSyncHandler;

/**
 * Click handlers for ModularUI2 widgets.
 */
public class GuiActions {

    private GuiActions() {}

    /** A client-side press handler that runs {@code action} for left clicks only and consumes them. */
    public static IGuiAction.MousePressed leftClick(Runnable action) {
        return mouseButton -> {
            if (mouseButton != 0) return false;
            action.run();
            return true;
        };
    }

    /**
     * A sync handler that runs {@code action} on the server for left clicks. The handler also fires locally on the
     * client before syncing; running the action there too would apply it twice in single player, where both sides
     * share services, so the client side is skipped.
     */
    public static InteractionSyncHandler serverClick(Runnable action) {
        return new InteractionSyncHandler()
            .setOnMousePressed(mouse -> { if (!mouse.isClient() && mouse.mouseButton == 0) action.run(); });
    }
}
