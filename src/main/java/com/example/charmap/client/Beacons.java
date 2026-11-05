package com.example.charmap.client;

import com.example.charmap.model.Waypoint;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

// A thin beam of light in the world at each waypoint, with its name and how far it is.
final class Beacons {
    private Beacons() {}

    static void render(RenderLevelStageEvent e) {
        Minecraft mc = Minecraft.getInstance();
        MapClient.Session s = MapClient.session;
        if (s == null || MapClient.WAYPOINTS.isEmpty() || mc.level == null) return;
        Camera camera = e.getCamera();
        Vec3 cam = camera.getPosition();
        PoseStack pose = e.getPoseStack();
        for (Waypoint w : MapClient.WAYPOINTS) {
            if (!w.shown) continue;
            int[] at = w.placeIn(s.dimension);
            if (at == null) continue;
            double dx = at[0] + 0.5 - cam.x, dz = at[2] + 0.5 - cam.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 3) continue;
            // Far ones are drawn at the same direction but closer, so they still show past the render distance.
            double reach = Math.min(dist, 96);
            double scale = reach / dist;
            float bx = (float) (dx * scale), bz = (float) (dz * scale);
            beam(pose, bx, bz, (float) (mc.level.getMinBuildHeight() - cam.y), (float) (mc.level.getMaxBuildHeight() - cam.y), w.colour);
            label(mc, camera, pose, bx, (float) Math.min(at[1] + 2.0 - cam.y, 24), bz, w.name + "  " + Math.round(dist) + "m", reach);
        }
    }

    private static void beam(PoseStack pose, float x, float z, float y0, float y1, int colour) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        Matrix4f m = pose.last().pose();
        int r = (colour >> 16) & 255, g = (colour >> 8) & 255, b = colour & 255;
        float w = 0.16f;
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (float[] d : new float[][] {{w, w}, {w, -w}}) {
            buf.vertex(m, x - d[0], y0, z - d[1]).color(r, g, b, 90).endVertex();
            buf.vertex(m, x + d[0], y0, z + d[1]).color(r, g, b, 90).endVertex();
            buf.vertex(m, x + d[0], y1, z + d[1]).color(r, g, b, 8).endVertex();
            buf.vertex(m, x - d[0], y1, z - d[1]).color(r, g, b, 8).endVertex();
        }
        BufferUploader.drawWithShader(buf.end());
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
    }

    private static void label(Minecraft mc, Camera camera, PoseStack pose, float x, float y, float z, String text, double reach) {
        pose.pushPose();
        pose.translate(x, y, z);
        pose.mulPose(camera.rotation());
        float k = 0.025f * (float) Math.max(1, reach / 7);
        pose.scale(-k, -k, k);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Font font = mc.font;
        var line = Ui.styled(text);
        font.drawInBatch(line, -font.width(line) / 2f, 0, 0xFFFFFFFF, false, pose.last().pose(), buffers, Font.DisplayMode.SEE_THROUGH, 0x66180E1B, LightTexture.FULL_BRIGHT);
        buffers.endBatch();
        pose.popPose();
    }
}
