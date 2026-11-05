package com.yuelengm.nhkantan.client.render;

import java.nio.FloatBuffer;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.gtnewhorizon.gtnhlib.geometry.Orientation;
import com.yuelengm.nhkantan.block.MarketDisplayOrientation;
import com.yuelengm.nhkantan.block.TileMarketDisplay;
import com.yuelengm.nhkantan.market.view.ClientMarketData;
import com.yuelengm.nhkantan.market.view.StockView;

/**
 * Draws the "big screen" of a market display in the world.
 * <p>
 * The renderer sets up a screen-local coordinate system whose origin is the top-left corner of the screen surface:
 * <ul>
 * <li>+X points to the screen's right</li>
 * <li>+Y points down along the screen (matching GUI / font conventions)</li>
 * <li>+Z points out of the screen toward the viewer</li>
 * </ul>
 * One unit equals one block, so the screen covers {@code [0, width] x [0, height]}. Content comes from the shared
 * {@link ClientMarketData}; displays never request data on their own.
 */
public class MarketDisplayRenderer extends TileEntitySpecialRenderer {

    /** Pixels per block, used to convert the configured screen depth (in pixels) to blocks. */
    private static final double PIXELS_PER_BLOCK = 16.0D;
    /** Width of the screen bezel in blocks. */
    private static final float BEZEL = 1.0F / 32.0F;
    /** Depth step between the bezel and the panel layers, avoiding z-fighting. */
    private static final float LAYER_STEP = 0.001F;
    /** Font pixels per block for screen text. */
    private static final float TEXT_PIXELS_PER_BLOCK = 64.0F;

    private static final int COLOR_BEZEL = 0x2B3038;

    private final FloatBuffer basis = BufferUtils.createFloatBuffer(16);

    @Override
    public void renderTileEntityAt(TileEntity tile, double x, double y, double z, float partialTicks) {
        if (!(tile instanceof TileMarketDisplay display)) return;

        Orientation orientation = display.getOrientation();
        ForgeDirection normal = MarketDisplayOrientation.facing(orientation);
        ForgeDirection up = MarketDisplayOrientation.up(orientation);
        ForgeDirection right = MarketDisplayOrientation.right(orientation);
        if (right == ForgeDirection.UNKNOWN) return;

        int width = display.getScreenWidth();
        int height = display.getScreenHeight();

        // Top-left corner of the screen surface, relative to the block origin.
        // surfaceCenter = blockCenter - normal * (0.5 - offset); bottomLeft = surfaceCenter - right/2 - up/2
        // Depth 0 sits flush against the mounting surface; 16 px reaches the opposite face of the block cell
        double back = 0.5D - display.getScreenDepth() / PIXELS_PER_BLOCK;
        double ox = 0.5D - normal.offsetX * back - right.offsetX * 0.5D + up.offsetX * (height - 0.5D);
        double oy = 0.5D - normal.offsetY * back - right.offsetY * 0.5D + up.offsetY * (height - 0.5D);
        double oz = 0.5D - normal.offsetZ * back - right.offsetZ * 0.5D + up.offsetZ * (height - 0.5D);

        GL11.glPushMatrix();
        GL11.glTranslated(x + ox, y + oy, z + oz);
        applyScreenBasis(right, up, normal);

        float lastBrightnessX = OpenGlHelper.lastBrightnessX;
        float lastBrightnessY = OpenGlHelper.lastBrightnessY;
        // Screens are self-illuminated
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);

        drawScreen(width, height, display.getSelectedStockId());

        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_LIGHTING);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lastBrightnessX, lastBrightnessY);
        GL11.glPopMatrix();
    }

    /** Multiplies the current matrix by the basis (right, -up, normal) so screen-local coordinates can be used. */
    private void applyScreenBasis(ForgeDirection right, ForgeDirection up, ForgeDirection normal) {
        basis.clear();
        // Column-major: column 0 = local X, column 1 = local Y, column 2 = local Z, column 3 = translation
        basis.put(right.offsetX)
            .put(right.offsetY)
            .put(right.offsetZ)
            .put(0.0F);
        basis.put(-up.offsetX)
            .put(-up.offsetY)
            .put(-up.offsetZ)
            .put(0.0F);
        basis.put(normal.offsetX)
            .put(normal.offsetY)
            .put(normal.offsetZ)
            .put(0.0F);
        basis.put(0.0F)
            .put(0.0F)
            .put(0.0F)
            .put(1.0F);
        basis.flip();
        GL11.glMultMatrix(basis);
    }

    /** Bezel, then the selected stock's shared panel inside it. */
    private void drawScreen(int width, int height, String selectedStockId) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.setColorOpaque_I(COLOR_BEZEL);
        tessellator.addVertex(0.0F, 0.0F, 0.0F);
        tessellator.addVertex(0.0F, height, 0.0F);
        tessellator.addVertex(width, height, 0.0F);
        tessellator.addVertex(width, 0.0F, 0.0F);
        tessellator.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);

        FontRenderer font = func_147498_b();
        if (font == null) return;
        StockView selected = selectedStockId == null || selectedStockId.isEmpty() ? null
            : ClientMarketData.INSTANCE.get(selectedStockId);
        if (selected == null) selected = ClientMarketData.INSTANCE.first();
        StockPanelRenderer.draw(
            font,
            selected,
            BEZEL,
            BEZEL,
            width - BEZEL * 2.0F,
            height - BEZEL * 2.0F,
            1.0F / TEXT_PIXELS_PER_BLOCK,
            LAYER_STEP,
            LAYER_STEP);
    }
}
