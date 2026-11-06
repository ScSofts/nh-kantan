package com.yuelengm.nhkantan.client.gui;

import net.minecraft.client.Minecraft;

import org.jetbrains.annotations.NotNull;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.factory.ClientGUI;
import com.cleanroommc.modularui.screen.CustomModularScreen;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.gtnewhorizon.gtnhlib.config.ConfigurationManager;
import com.yuelengm.nhkantan.NHKantan;
import com.yuelengm.nhkantan.config.ClientConfig;
import com.yuelengm.nhkantan.gui.util.Assets;
import com.yuelengm.nhkantan.gui.util.CheckboxWidget;
import com.yuelengm.nhkantan.gui.util.Widgets;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Client-only warning shown before the first terminal use. Confirming optionally disables the warning and invokes the
 * supplied callback, which decides what GUI to open next.
 */
@SideOnly(Side.CLIENT)
public class RealLifeWarningGui extends CustomModularScreen {

    private static final int PADDING = 10;
    private static final int GAP = 5;

    private final Callback callback;

    private CheckboxWidget dontShowAgain;

    public RealLifeWarningGui(Callback callback) {
        super(NHKantan.MODID);
        this.callback = callback;
    }

    public static void openClient(Callback callback) {
        ClientGUI.open(new RealLifeWarningGui(callback));
    }

    @NotNull
    @Override
    public ModularPanel buildUI(ModularGuiContext context) {
        dontShowAgain = new CheckboxWidget(IKey.lang("gui.nhkantan.real_life_warning.dont_show_again"));
        return ModularPanel.defaultPanel("nhkantan_real_life_warning")
            .coverChildren()
            .child(
                Flow.column()
                    .coverChildren()
                    .padding(PADDING)
                    .childPadding(GAP)
                    .crossAxisAlignment(Alignment.CrossAxis.START)
                    .child(
                        Flow.row()
                            .coverChildren()
                            .childPadding(GAP)
                            .child(
                                Assets.WARNING.asWidget()
                                    .size(16))
                            .child(
                                Widgets.text(IKey.lang("gui.nhkantan.real_life_warning.title"))))
                    .child(Widgets.text(IKey.lang("gui.nhkantan.real_life_warning.body")))
                    .child(dontShowAgain.marginTop(10))
                    .child(
                        new ButtonWidget<>().fullWidth()
                            .height(20)
                            .overlay(IKey.lang("gui.nhkantan.real_life_warning.confirm"))
                            .onMousePressed(mouseButton -> {
                                if (mouseButton != 0) return false;
                                confirm();
                                return true;
                            })));
    }

    private void confirm() {
        if (dontShowAgain != null && dontShowAgain.isChecked()) {
            ClientConfig.showLifeWarning = false;
            ConfigurationManager.save(ClientConfig.class);
        }

        Minecraft.getMinecraft()
            .displayGuiScreen(null);

        callback.onConfirmed();
    }

    /** Action to run after the player accepts the warning. */
    @FunctionalInterface
    public interface Callback {

        void onConfirmed();
    }
}
