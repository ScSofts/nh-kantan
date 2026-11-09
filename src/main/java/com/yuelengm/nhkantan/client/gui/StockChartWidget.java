package com.yuelengm.nhkantan.client.gui;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;

import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.yuelengm.nhkantan.client.render.StockPanelRenderer;
import com.yuelengm.nhkantan.market.view.ChartLine;
import com.yuelengm.nhkantan.market.view.StockView;

/** Interactive terminal chart; world displays continue to use the full public window. */
public class StockChartWidget extends Widget<StockChartWidget> implements Interactable {

    private final Supplier<StockView> stock;
    private final Supplier<List<ChartLine>> lines;
    private int offset;
    private int dragX;
    private int dragOffset;
    private boolean dragging;
    private String symbol = "";

    public StockChartWidget(Supplier<StockView> stock) {
        this(stock, Collections::emptyList);
    }

    public StockChartWidget(Supplier<StockView> stock, Supplier<List<ChartLine>> lines) {
        this.stock = stock;
        this.lines = lines;
    }

    private int slots(StockView view) {
        return Math.max(1, Math.min(30, view.candleSlots()));
    }

    @Override
    public Result onMousePressed(int button) {
        if (button == 1) {
            offset = 0;
            return Result.SUCCESS;
        }
        if (button != 0) return Result.IGNORE;
        dragX = getContext().getAbsMouseX();
        dragOffset = offset;
        dragging = true;
        return Result.SUCCESS;
    }

    @Override
    public void onMouseDrag(int button, long elapsedTime) {
        StockView view = stock.get();
        if (!dragging || button != 0 || view == null) return;
        float slotWidth = Math.max(1, (getArea().width - 48F) / slots(view));
        offset = Math.max(
            0,
            Math.min(
                Math.max(
                    0,
                    view.candles()
                        .size() - slots(view)),
                dragOffset + Math.round((getContext().getAbsMouseX() - dragX) / slotWidth)));
    }

    @Override
    public boolean onMouseRelease(int button) {
        dragging = false;
        return button == 0;
    }

    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        StockView view = stock.get();
        if (view != null && !view.id()
            .equals(symbol)) {
            symbol = view.id();
            offset = 0;
        }
        int visible = view == null ? 30 : slots(view);
        if (view != null) offset = Math.min(
            offset,
            Math.max(
                0,
                view.candles()
                    .size() - visible));
        StockPanelRenderer.draw(
            Minecraft.getMinecraft().fontRenderer,
            view,
            0,
            0,
            getArea().width,
            getArea().height,
            1,
            0,
            0,
            visible,
            offset,
            lines.get());
    }
}
