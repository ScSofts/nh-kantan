package com.yuelengm.nhkantan.client.render;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import com.yuelengm.nhkantan.market.view.Candle;
import com.yuelengm.nhkantan.market.view.ChartLine;
import com.yuelengm.nhkantan.market.view.PriceFormat;
import com.yuelengm.nhkantan.market.view.StockView;

/**
 * Draws a stock panel (header with name, price and change, plus a K-line chart) into the current GL coordinate
 * system, which must have +X to the right and +Y downward.
 * <p>
 * Shared by the in-world display (block units) and the terminal GUI (GUI pixels): callers only choose the size of one
 * font pixel ({@code unit}) and the depth step between layers. Terminals may select a recent viewport and private
 * annotations; world displays retain the full anonymous public window.
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
    private static final float BODY_RATIO = 0.72F;
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
        draw(
            font,
            stock,
            x,
            y,
            width,
            height,
            unit,
            z,
            zStep,
            stock == null ? 30 : stock.candleSlots(),
            0,
            Collections.emptyList());
    }

    public static void draw(FontRenderer font, @Nullable StockView stock, float x, float y, float width, float height,
        float unit, float z, float zStep, int visibleSlots, int offset, List<ChartLine> lines) {
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
        String priceText = PriceFormat.price(stock.price(), stock.priceDecimals());
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
        drawChart(
            font,
            stock,
            x + pad,
            chartTop,
            right,
            y + height - pad,
            unit,
            z + zStep,
            zStep,
            visibleSlots,
            offset,
            lines);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void drawChart(FontRenderer font, StockView stock, float x0, float y0, float x1, float y1,
        float unit, float z, float zStep, int slots, int offset, List<ChartLine> lines) {
        List<Candle> all = stock.candles();
        slots = Math.max(1, slots);
        int candleEnd = Math.max(0, all.size() - Math.max(0, offset));
        List<Candle> candles = all.subList(Math.max(0, candleEnd - slots), candleEnd);
        double min = stock.price();
        double max = stock.price();
        for (Candle candle : candles) {
            min = Math.min(min, candle.low());
            max = Math.max(max, candle.high());
        }
        double padding = Math.max(
            (max - min) * 0.12D,
            stock.isExchange() ? Math.max(0.01D, Math.abs(max) * 0.00005D) : Math.abs(max) * 0.005D);
        min -= padding;
        max += padding;

        int decimals = stock.isExchange() ? stock.priceDecimals() : PriceFormat.decimalsFor(max);
        float labelWidth = font.getStringWidth(PriceFormat.price(max, decimals)) * unit + PADDING * unit;
        float plotRight = x1 - labelWidth;
        if (plotRight - x0 < 10.0F * unit || y1 - y0 < 10.0F * unit) return;

        float priceBottom = stock.isExchange() ? y1 - (y1 - y0) * 0.18F : y1;
        PriceScale scale = new PriceScale(min, max, y0, priceBottom);
        float textZ = z + zStep * 2.0F;
        float labelX = plotRight + PADDING * unit * 0.5F;
        float halfLine = font.FONT_HEIGHT * unit * 0.5F;

        Tessellator tessellator = Tessellator.instance;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        tessellator.startDrawingQuads();
        for (int i = 0; i < GRID_LEVELS; i++) {
            float gy = y0 + (priceBottom - y0) * i / (GRID_LEVELS - 1);
            quad(tessellator, x0, gy, plotRight, gy + unit * 0.5F, z, COLOR_GRID);
        }
        drawCandles(tessellator, candles, slots, scale, x0, plotRight, unit, z + zStep);
        if (stock.isExchange()) {
            long maxVolume = 1;
            for (Candle candle : candles) maxVolume = Math.max(maxVolume, candle.volume());
            float slot = (plotRight - x0) / slots;
            int first = slots - candles.size();
            for (int i = 0; i < candles.size(); i++) {
                Candle candle = candles.get(i);
                float left = x0 + (first + i) * slot;
                float height = (y1 - priceBottom) * 0.8F * candle.volume() / maxVolume;
                quad(
                    tessellator,
                    left,
                    y1 - height,
                    left + slot * BODY_RATIO,
                    y1,
                    z,
                    candle.isRising() ? COLOR_UP : COLOR_DOWN);
            }
        }
        for (ChartLine line : lines) {
            float ly = Math.max(y0 + halfLine, Math.min(priceBottom - halfLine, scale.y(line.price)));
            for (float dx = x0; dx < plotRight; dx += 7 * unit) {
                quad(tessellator, dx, ly, Math.min(dx + 5 * unit, plotRight), ly + unit * 0.6F, z + zStep, line.color);
            }
        }
        float priceY = scale.y(stock.price());
        int priceColor = colorOf(stock.latestChange());
        for (float dx = x0; dx < plotRight; dx += (DASH + DASH_GAP) * unit) {
            float end = Math.min(dx + DASH * unit, plotRight);
            quad(tessellator, dx, priceY - unit * 0.25F, end, priceY + unit * 0.25F, z + zStep, COLOR_PRICE_LINE);
        }
        quad(tessellator, plotRight, priceY - halfLine - unit, x1, priceY + halfLine, z + zStep, priceColor);
        tessellator.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);

        java.util.List<float[]> occupied = new java.util.ArrayList<>();
        for (ChartLine line : lines) {
            float actual = scale.y(line.price);
            float ly = Math.max(y0 + halfLine, Math.min(priceBottom - halfLine, actual));
            for (float[] used : occupied) {
                if (Math.abs(ly - used[0]) < (font.FONT_HEIGHT + 2) * unit)
                    ly = used[0] + (font.FONT_HEIGHT + 2) * unit;
            }
            ly = Math.min(priceBottom - halfLine, ly);
            occupied.add(new float[] { ly });
            String edge = actual < y0 ? "^ " : actual > priceBottom ? "v " : "";
            String label = edge + line.label + " " + PriceFormat.price(line.price, decimals);
            if (font.getStringWidth(label) * unit > plotRight - x0)
                label = font.trimStringToWidth(label, Math.max(1, (int) ((plotRight - x0) / unit)));
            text(font, label, x0 + 2 * unit, ly - halfLine, unit, textZ, line.color);
        }
        for (int i = 0; i < GRID_LEVELS; i++) {
            float gy = y0 + (priceBottom - y0) * i / (GRID_LEVELS - 1);
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
     * Draws right-aligned OHLC bodies and high/low wicks in the chosen viewport.
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
            float wickTop = scale.y(candle.high());
            float wickBottom = scale.y(candle.low());
            float top = scale.y(Math.max(candle.open(), candle.close()));
            float bottom = scale.y(Math.min(candle.open(), candle.close()));
            float normalWick = Math.max(unit * 4, (bottom - top) * 2);
            drawWick(tessellator, cx, top, wickTop, normalWick, unit, z, color);
            drawWick(tessellator, cx, bottom, wickBottom, normalWick, unit, z, color);
            if (bottom - top < minBody) {
                float center = (top + bottom) * 0.5F;
                top = center - minBody * 0.5F;
                bottom = center + minBody * 0.5F;
            }
            quad(tessellator, cx - bodyHalf, top, cx + bodyHalf, bottom, z, color);
        }
    }

    /** Keep true high/low endpoints; only fade unusually long tails into the background. */
    private static void drawWick(Tessellator tessellator, float cx, float body, float tip, float normalLength,
        float unit, float z, int color) {
        float length = Math.abs(tip - body);
        if (length == 0) return;
        float direction = Math.signum(tip - body);
        float core = Math.min(length, normalLength);
        float edge = body + direction * core;
        quad(tessellator, cx - unit * 0.25F, Math.min(body, edge), cx + unit * 0.25F, Math.max(body, edge), z, color);
        for (int i = 0; i < 4 && core < length; i++) {
            float start = edge + direction * (length - core) * i / 4;
            float end = edge + direction * (length - core) * (i + 1) / 4;
            float strength = 0.5F - i * 0.1F;
            int red = Math.round(((COLOR_BACKGROUND >> 16) & 255) * (1 - strength) + ((color >> 16) & 255) * strength);
            int green = Math.round(((COLOR_BACKGROUND >> 8) & 255) * (1 - strength) + ((color >> 8) & 255) * strength);
            int blue = Math.round((COLOR_BACKGROUND & 255) * (1 - strength) + (color & 255) * strength);
            quad(
                tessellator,
                cx - unit * 0.25F,
                Math.min(start, end),
                cx + unit * 0.25F,
                Math.max(start, end),
                z,
                (red << 16) | (green << 8) | blue);
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
