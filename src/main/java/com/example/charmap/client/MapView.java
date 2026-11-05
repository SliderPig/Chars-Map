package com.example.charmap.client;

import com.example.charmap.model.Coords;
import com.example.charmap.model.Shapes;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

// Draws the map inside any outline, at any zoom and rotation. Works out which tiles are in view, cuts the outline to each tile, and draws plain textured triangles.
// A tile still on its way never holds things up: the coarser version of the same place gets stretched over it until it's there.
public final class MapView {
    private MapView() {}

    // shape: outline on screen, x,y pairs around centreX,centreY. worldX,worldZ: the block at that centre.
    public static void draw(GuiGraphics g, Textures tex, int layer, float[] shape, float centreX, float centreY, double worldX, double worldZ, float pixelsPerBlock, float rotation, float alpha, int budget) {
        // The outline in blocks, measured from worldX,worldZ. Small numbers stay exact in a float, however far you've walked.
        float[] world = new float[shape.length];
        float cos = (float) Math.cos(-rotation), sin = (float) Math.sin(-rotation);
        float minX = Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        float w = pixelsPerBlock;
        for (int i = 0; i < shape.length; i += 2) {
            float x = (shape[i] * cos - shape[i + 1] * sin) / w, z = (shape[i] * sin + shape[i + 1] * cos) / w;
            world[i] = x;
            world[i + 1] = z;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
        double blocksPerPixel = 1.0 / pixelsPerBlock;
        float pxW = (maxX - minX) * w, pxH = (maxZ - minZ) * w;
        int level = Coords.levelWithin(blocksPerPixel, pxW, pxH, budget);
        boolean linear = pixelsPerBlock < 1f || level > 0;

        int tileBlocks = Coords.tileBlocks(level);
        int tx0 = Coords.tileAt((int) Math.floor(worldX + minX), level), tx1 = Coords.tileAt((int) Math.floor(worldX + maxX), level);
        int tz0 = Coords.tileAt((int) Math.floor(worldZ + minZ), level), tz1 = Coords.tileAt((int) Math.floor(worldZ + maxZ), level);

        // Keep a coarse copy of the whole view handy: it's what shows while the sharp tiles arrive or when zooming fast.
        int coarse = Math.min(Coords.MAX_LEVEL, level + 2);
        if (coarse != level) {
            int cx0 = Coords.tileAt((int) Math.floor(worldX + minX), coarse), cx1 = Coords.tileAt((int) Math.floor(worldX + maxX), coarse);
            int cz0 = Coords.tileAt((int) Math.floor(worldZ + minZ), coarse), cz1 = Coords.tileAt((int) Math.floor(worldZ + maxZ), coarse);
            for (int z = cz0; z <= cz1; z++) for (int x = cx0; x <= cx1; x++) tex.need(Coords.key(layer, coarse, x, z));
        }

        g.flush();
        RenderSystem.enableBlend();
        RenderSystem.disableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        Matrix4f m = g.pose().last().pose();
        for (int tz = tz0; tz <= tz1; tz++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                Textures.Gl gl = tex.need(Coords.key(layer, level, tx, tz));
                double ox = tx * (double) tileBlocks - worldX, oz = tz * (double) tileBlocks - worldZ;
                float x0 = (float) ox, z0 = (float) oz, x1 = x0 + tileBlocks, z1 = z0 + tileBlocks;
                float[] cut = Shapes.clip(world, x0, z0, x1, z1);
                if (cut.length < 6) continue;
                Textures.Gl use = gl;
                int useLevel = level, useX = tx, useZ = tz;
                // Not there yet: look up the pyramid for something that is.
                while (!use.ready() && !use.empty && useLevel < Coords.MAX_LEVEL) {
                    useLevel++;
                    useX >>= 1;
                    useZ >>= 1;
                    use = tex.need(Coords.key(layer, useLevel, useX, useZ));
                }
                if (!use.ready()) continue;
                // Where the tile-sized patch sits inside the texture actually being used.
                int useBlocks = Coords.tileBlocks(useLevel);
                float u0 = (float) ((tx * (double) tileBlocks - useX * (double) useBlocks) / useBlocks);
                float v0 = (float) ((tz * (double) tileBlocks - useZ * (double) useBlocks) / useBlocks);
                float span = (float) tileBlocks / useBlocks;
                tex.useFilter(use, linear || useLevel != level);
                RenderSystem.setShaderTexture(0, use.id());

                BufferBuilder buf = Tesselator.getInstance().getBuilder();
                buf.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_TEX_COLOR);
                int a = Math.round(alpha * 255);
                float cosR = (float) Math.cos(rotation), sinR = (float) Math.sin(rotation);
                for (int i = 0; i < cut.length; i += 2) {
                    float bx = cut[i], bz = cut[i + 1];
                    float sx = centreX + (bx * cosR - bz * sinR) * w, sy = centreY + (bx * sinR + bz * cosR) * w;
                    float u = u0 + (bx - x0) / tileBlocks * span, v = v0 + (bz - z0) / tileBlocks * span;
                    buf.vertex(m, sx, sy, 0).uv(u, v).color(255, 255, 255, a).endVertex();
                }
                BufferUploader.drawWithShader(buf.end());
            }
        }
    }
}
