package com.example.charmap.client;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

// Keeps the minimap's picture ready in one small texture, a good bit bigger than the map itself. Each frame only slides and turns that picture (one draw),
// and it's only remade from the tiles when you get near its edge or the map data under it has changed. That's why flying costs next to nothing.
final class MinimapCache {
    private TextureTarget target;
    private int side;
    private double originX, originZ;
    private float zoom;
    private int layer = -1;
    private long seenChanges = -1, composedAt;
    private boolean wasSmooth;
    int composes;

    int textureId() {
        return target.getColorTextureId();
    }

    int side() {
        return side;
    }

    // The block at the middle of the picture.
    double originX() {
        return originX;
    }

    double originZ() {
        return originZ;
    }

    void close() {
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
        layer = -1;
    }

    // margin: how many pixels the view can slide before running off the picture.
    void update(Textures tex, int layerNow, double x, double z, float ppb, int wantSide, float margin, boolean smooth) {
        long now = System.nanoTime();
        boolean remake = false;
        if (target == null || side != wantSide) {
            if (target != null) target.destroyBuffers();
            target = new TextureTarget(wantSide, wantSide, false, Minecraft.ON_OSX);
            // Parts nobody has seen are the dark bed of the map, part of the picture so there's nothing extra to draw under it.
            target.setClearColor((Ui.PLUM_DARK >> 16 & 255) / 255f, (Ui.PLUM_DARK >> 8 & 255) / 255f, (Ui.PLUM_DARK & 255) / 255f, 1f);
            side = wantSide;
            remake = true;
        }
        if (remake || smooth != wasSmooth) {
            wasSmooth = smooth;
            target.setFilterMode(smooth ? GL11.GL_LINEAR : GL11.GL_NEAREST);
        }
        if (layerNow != layer || ppb != zoom) remake = true;
        if (Math.max(Math.abs(x - originX), Math.abs(z - originZ)) * ppb > margin) remake = true;
        // New pixels under the picture: at most a few times a second, nobody can see it any faster on a map this small.
        if (tex.changes != seenChanges && now - composedAt > 180_000_000L) remake = true;
        if (!remake) return;

        seenChanges = tex.changes;
        composedAt = now;
        layer = layerNow;
        zoom = ppb;
        originX = Math.floor(x);
        originZ = Math.floor(z);
        composes++;

        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, side, side, 0, -1000, 1000), VertexSorting.ORTHOGRAPHIC_Z);
        PoseStack view = RenderSystem.getModelViewStack();
        view.pushPose();
        view.setIdentity();
        RenderSystem.applyModelViewMatrix();
        float half = side / 2f;
        float[] shape = {-half, -half, half, -half, half, half, -half, half};
        MapView.draw(new Matrix4f(), tex, layer, shape, half, half, originX, originZ, ppb, 0f, 1f, tex.budget() / 2);
        view.popPose();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.restoreProjectionMatrix();
        Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
    }
}
