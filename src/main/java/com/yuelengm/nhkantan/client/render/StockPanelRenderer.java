package com.yuelengm.nhkantan.client.render;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import com.yuelengm.nhkantan.market.view.Candle;
import com.yuelengm.nhkantan.market.view.PriceFormat;
import com.yuelengm.nhkantan.market.view.StockView;

/**
 * Draws a stock panel (header with name, price and change, plus a K-line chart) into the current GL coordinate
 * system, which must have +X to the right and +Y downward.
 * <p>
 * Shared by the in-world display (block units) and the terminal GUI (GUI pixels): callers only choose the size of one
 * font pixel ({@code unit}) and the depth step between layers. The horizontal axis is fixed to the window capacity, so
 * candles fill from the right and older history is never shown.
 */
public class StockPanelRenderer {

    private static final int COLOR_BACKGROUND = 0x111720;
    private static final int COLOR_GRID = 0x2D3847;
    private static final int COLOR_TITLE = 0xEDF2FA;
    private static final int COLOR_TEXT = 0x95A4B8;
    private static final int COLOR_UP = 0x65D7BF;
    private static final int COLOR_DOWN = 0xFF7777;
    private static final int COLOR_PRICE_LINE = 0x9EACBF;
    private static final int COLOR_PRICE_TAG_TEXT = 0x0A0E14;

    private static final int GRID_LEVELS = 5;
    private static final float PADDING = 4.0F;
    private static final float LINE_GAP = 2.0F;
    private static final float BODY_RATIO = 0.6F;
    private static final float DASH = 3.0F;
    private static final float DASH_GAP = 2.0F;

    private StockPanelRenderer() {}

    /**
     * @param stock the stock to draw, or {@code null} to show a "no data" notice
     * @param unit  size of one font pixel in the current coordinate system
     * @param z     depth of the background; later layers are drawn {@code zStep} closer to the viewer each
     */
    public static void draw(FontRenderer font, @Nullable StockView stock, float x, float y, float width, float height,
        float unit, float z, float zStep) {
        Tessellator tessellator = Tessellator.instance;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        tessellator.startDrawingQuads();
        quad(tessellator, x, y, x + width, y + height, z, COLOR_BACKGROUND);
        tessellator.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);

        float pad = PADDING * unit;
        float lineHeight = (font.FONT_HEIGHT + LINE_GAP) * unit;
        float textZ = z + zStep * 3.0F;
        if (stock == null) {
            text(
                font,
                StatCollector.translateToLocal("gui.nhkantan.market.no_data"),
                x + pad,
                y + pad,
                unit,
                textZ,
                COLOR_TEXT);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            return;
        }

        double change = stock.latestChange();
        String priceText = PriceFormat.price(stock.price());
        String changeText = PriceFormat.change(change);
        float right = x + width - pad;
        text(font, stock.name(), x + pad, y + pad, unit, textZ, COLOR_TITLE);
        text(font, priceText, right - font.getStringWidth(priceText) * unit, y + pad, unit, textZ, COLOR_TITLE);
        text(
            font,
            changeText,
            right - font.getStringWidth(changeText) * unit,
            y + pad + lineHeight,
            unit,
            textZ,
            colorOf(change));

        float chartTop = y + pad + lineHeight * 2.0F + pad;
        drawChart(font, stock, x + pad, chartTop, right, y + height - pad, unit, z + zStep, zStep);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void drawChart(FontRenderer font, StockView stock, float x0, float y0, float x1, float y1,
        float unit, float z, float zStep) {
        List<Candle> candles = stock.candles();
        double min = stock.price();
        double max = stock.price();
        for (Candle candle : candles) {
            min = Math.min(min, candle.low());
            max = Math.max(max, candle.high());
        }
        double padding = Math.max((max - min) * 0.08D, Math.abs(max) * 0.005D);
        min -= padding;
        max += padding;

        int decimals = PriceFormat.decimalsFor(max);
        float labelWidth = font.getStringWidth(PriceFormat.price(max, decimals)) * unit + PADDING * unit;
        float plotRight = x1 - labelWidth;
        if (plotRight - x0 < 10.0F * unit || y1 - y0 < 10.0F * unit) return;

        PriceScale scale = new PriceScale(min, max, y0, y1);
        float textZ = z + zStep * 2.0F;
        float labelX = plotRight + PADDING * unit * 0.5F;
        float halfLine = font.FONT_HEIGHT * unit * 0.5F;

        Tessellator tessellator = Tessellator.instance;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        tessellator.startDrawingQuads();
        for (int i = 0; i < GRID_LEVELS; i++) {
            float gy = y0 + (y1 - y0) * i / (GRID_LEVELS - 1);
            quad(tessellator, x0, gy, plotRight, gy + unit * 0.5F, z, COLOR_GRID);
        }
        drawCandles(tessellator, candles, stock.candleSlots(), scale, x0, plotRight, unit, z + zStep);
        float priceY = scale.y(stock.price());
        int priceColor = colorOf(stock.latestChange());
        for (float dx = x0; dx < plotRight; dx += (DASH + DASH_GAP) * unit) {
            float end = Math.min(dx + DASH * unit, plotRight);
            quad(tessellator, dx, priceY - unit * 0.25F, end, priceY + unit * 0.25F, z + zStep, COLOR_PRICE_LINE);
        }
        quad(tessellator, plotRight, priceY - halfLine - unit, x1, priceY + halfLine, z + zStep, priceColor);
        tessellator.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);

        for (int i = 0; i < GRID_LEVELS; i++) {
            float gy = y0 + (y1 - y0) * i / (GRID_LEVELS - 1);
            // Skip grid labels that would overlap the current price tag
            if (Math.abs(gy - priceY) < halfLine * 2.0F) continue;
            String label = PriceFormat.price(scale.price(gy), decimals);
            text(font, label, labelX, gy - halfLine, unit, textZ, COLOR_TEXT);
        }
        text(
            font,
            PriceFormat.price(stock.price(), decimals),
            labelX,
            priceY - halfLine,
            unit,
            textZ + zStep,
            COLOR_PRICE_TAG_TEXT);
    }

    /**
     * Candles are right-aligned in a fixed number of slots. Each candle spans two consecutive prices, so it has no
     * wicks.
     */
    private static void drawCandles(Tessellator tessellator, List<Candle> candles, int slots, PriceScale scale,
        float x0, float x1, float unit, float z) {
        int first = slots - candles.size();
        float slotWidth = (x1 - x0) / slots;
        float bodyHalf = Math.max(unit, slotWidth * BODY_RATIO) * 0.5F;
        float minBody = unit * 0.5F;
        for (int i = 0; i < candles.size(); i++) {
            Candle candle = candles.get(i);
            float cx = x0 + (first + i + 0.5F) * slotWidth;
            int color = candle.isRising() ? COLOR_UP : COLOR_DOWN;
            float top = scale.y(candle.high());
            float bottom = scale.y(candle.low());
            if (bottom - top < minBody) {
                float center = (top + bottom) * 0.5F;
                top = center - minBody * 0.5F;
                bottom = center + minBody * 0.5F;
            }
            quad(tessellator, cx - bodyHalf, top, cx + bodyHalf, bottom, z, color);
        }
    }

    private static void quad(Tessellator tessellator, float x0, float y0, float x1, float y1, float z, int color) {
        tessellator.setColorOpaque_I(color);
        tessellator.addVertex(x0, y0, z);
        tessellator.addVertex(x0, y1, z);
        tessellator.addVertex(x1, y1, z);
        tessellator.addVertex(x1, y0, z);
    }

    private static void text(FontRenderer font, String text, float x, float y, float unit, float z, int color) {
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, z);
        GL11.glScalef(unit, unit, 1.0F);
        font.drawString(text, 0, 0, color);
        GL11.glPopMatrix();
    }

    private static int colorOf(double delta) {
        return delta > 0.0D ? COLOR_UP : delta < 0.0D ? COLOR_DOWN : COLOR_TEXT;
    }

    /** Linear mapping between prices and vertical positions; higher prices are drawn higher up. */
    private static class PriceScale {

        private final double min;
        private final double span;
        private final float top;
        private final float height;

        private PriceScale(double min, double max, float top, float bottom) {
            this.min = min;
            this.span = max - min;
            this.top = top;
            this.height = bottom - top;
        }

        private float y(double price) {
            return (float) (top + (1.0D - (price - min) / span) * height);
        }

        private double price(float y) {
            return min + (1.0D - (y - top) / height) * span;
        }
    }
}
