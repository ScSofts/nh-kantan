package com.yuelengm.nhkantan.gui;

import com.cleanroommc.modularui.factory.ClientGUI;
import net.minecraft.client.Minecraft;
import net.minecraft.util.StatCollector;

import org.jetbrains.annotations.NotNull;

import com.cleanroommc.modularui.api.MCHelper;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.screen.CustomModularScreen;
import com.cleanroommc.modularui.screen.GuiScreenWrapper;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.value.BoolValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ToggleButton;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.gtnewhorizon.gtnhlib.config.ConfigurationManager;
import com.yuelengm.nhkantan.NHKantan;
import com.yuelengm.nhkantan.config.ClientConfig;
import com.yuelengm.nhkantan.gui.util.Widgets;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Client-only warning shown before the first terminal use. Confirming optionally disables the warning and invokes the
 * supplied callback, which decides what GUI to open next.
 */
@SideOnly(Side.CLIENT)
public class RealLifeWarningGui extends CustomModularScreen {

    private static final int WIDTH = 320;
    private static final int HEIGHT = 170;
    private static final int PADDING = 10;
    private static final int GAP = 5;

    private final Callback callback;

    private boolean dontShowAgain;

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
        return ModularPanel.defaultPanel("nhkantan_life_warning").coverChildren()
            .child(
                Flow.column()
                    .coverChildren()
                    .padding(PADDING)
                    .childPadding(GAP)
                    .child(
                        Widgets.text(IKey.lang("gui.nhkantan.life_warning.title"))
                            .fullWidth())
                    .child(
                        Widgets.text(IKey.lang("gui.nhkantan.life_warning.body1"))
                            .fullWidth())
                    .child(
                        Widgets.text(IKey.lang("gui.nhkantan.life_warning.body2"))
                            .fullWidth())
                    .child(
                        Widgets.text(IKey.lang("gui.nhkantan.life_warning.body3"))
                            .fullWidth())
                    .child(
                        Widgets.text(IKey.lang("gui.nhkantan.life_warning.body4"))
                            .fullWidth())
                    .child(checkbox())
                    .child(
                        new ButtonWidget<>().widthRel(1f)
                            .height(20)
                            .overlay(IKey.lang("gui.nhkantan.life_warning.confirm"))
                            .onMousePressed(mouseButton -> {
                                if (mouseButton != 0) return false;
                                confirm();
                                return true;
                            })));
    }

    private ToggleButton checkbox() {
        return new ToggleButton().widthRel(1f)
            .height(16)
            .value(new BoolValue.Dynamic(() -> dontShowAgain, value -> dontShowAgain = value))
            .overlay(IKey.dynamic(() -> {
                String mark = dontShowAgain ? "[x] " : "[ ] ";
                return mark + StatCollector.translateToLocal("gui.nhkantan.life_warning.dont_show");
            }));
    }

    private void confirm() {
        if (dontShowAgain) {
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
