package com.example.charmap.client;

import com.example.charmap.CharMap;
import com.example.charmap.model.Coords;
import com.example.charmap.model.Shapes;
import com.example.charmap.model.Waypoint;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;

// The corner map. It draws what's there and never waits: no tile yet just means the plain background for a moment.
public final class Minimap {
    private Minimap() {}

    private static String biome = "";
    private static final List<Entity> NEAR = new ArrayList<>();
    private static int age;

    static void tick(Minecraft mc) {
        age++;
        if (age % 10 != 0) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        BlockPos at = p.blockPosition();
        biome = mc.level.getBiome(at).unwrapKey().map(k -> prettyBiome(k.location().getNamespace(), k.location().getPath())).orElse("");
        NEAR.clear();
        if (MapConfig.dots) {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e == p || !(e instanceof LivingEntity) || e.isInvisible()) continue;
                if (e.distanceToSqr(p) > 256 * 256) continue;
                NEAR.add(e);
                if (NEAR.size() >= 96) break;
            }
        }
    }

    private static String prettyBiome(String ns, String path) {
        String key = "biome." + ns + "." + path;
        String shown = Component.translatable(key).getString();
        if (!shown.equals(key)) return shown;
        StringBuilder out = new StringBuilder();
        for (String w : path.split("_")) {
            if (w.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return out.toString();
    }

    public static void render(GuiGraphics g, float partial) {
        Minecraft mc = Minecraft.getInstance();
        MapClient.Session s = MapClient.session;
        if (s == null || !MapConfig.enabled || !MapConfig.showMinimap || mc.player == null || mc.options.hideGui) return;
        long start = System.nanoTime();
        try {
            draw(g, mc, s, partial);
        } catch (Throwable t) {
            CharMap.once("minimap", "Minimap couldn't draw, carrying on", t);
        }
        MapClient.addFrameTime(System.nanoTime() - start);
    }

    private static void draw(GuiGraphics g, Minecraft mc, MapClient.Session s, float partial) {
        LocalPlayer p = mc.player;
        Ui.toRealPixels(g);
        RenderSystem.disableDepthTest();
        RenderSystem.setShaderColor(1, 1, 1, 1);

        var window = mc.getWindow();
        int screenW = window.getWidth(), screenH = window.getHeight();
        float size = Math.min(MapConfig.size, Math.min(screenW, screenH) - 20);
        float half = size / 2, margin = 12 + 2;
        float textRows = MapConfig.infoText ? (Ui.lineHeight() + 6) * 2 : 0;
        boolean right = MapConfig.corner == 1 || MapConfig.corner == 3, bottom = MapConfig.corner >= 2;
        float cx = right ? screenW - margin - half : margin + half;
        float cy = bottom ? screenH - margin - half - textRows : margin + half;
        float radius = MapConfig.circle ? half : Math.min(half, Math.max(14, size * 0.11f));
        int corner = MapConfig.circle ? 22 : 8;

        float[] shape = Shapes.rounded(half, radius, corner);
        // Soft shadow, dark bed
        Ui.band(g, cx, cy + 2, shape, Shapes.rounded(half + 7, radius + 7, corner), 0x66000000, 0);
        int back = (Math.round(MapConfig.opacity * 255) << 24) | (Ui.PLUM_DARK & 0xFFFFFF);
        Ui.polygon(g, cx, cy, shape, null, back);

        double px = Mth.lerp(partial, p.xOld, p.getX()), pz = Mth.lerp(partial, p.zOld, p.getZ());
        float yaw = p.getViewYRot(partial);
        float facing = (float) Math.toRadians(yaw - 180);
        float rotation = MapConfig.rotate ? -facing : 0;
        float ppb = MapConfig.pixelsPerBlock();

        MapView.draw(g, s.textures, s.layer, shape, cx, cy, px, pz, ppb, rotation, MapConfig.opacity, s.textures.budget() / 2);
        if (!MapClient.firstPixelsLogged) checkFirstPixels(s, px, pz, ppb);

        // Ring: a dark edge then a thin pink line, feathered so a circle stays round at any size.
        Ui.band(g, cx, cy, Shapes.rounded(half - 1.5f, radius - 1.5f, corner), shape, 0x00000000, 0x55000000);
        Ui.band(g, cx, cy, shape, Shapes.rounded(half + 2f, radius + 2f, corner), Ui.PINK, Ui.PINK);
        Ui.band(g, cx, cy, Shapes.rounded(half + 2f, radius + 2f, corner), Shapes.rounded(half + 3.2f, radius + 3.2f, corner), Ui.PINK, Ui.PINK & 0xFFFFFF);

        float cos = (float) Math.cos(rotation), sin = (float) Math.sin(rotation);
        float inner = half - 7, innerRadius = Math.max(1, radius - 7);

        if (MapConfig.dots) drawDots(g, cx, cy, px, pz, ppb, cos, sin, half - 3, Math.max(1, radius - 3));
        drawWaypoints(g, s, cx, cy, px, pz, ppb, cos, sin, inner, innerRadius);
        drawMates(g, mc, s, cx, cy, px, pz, ppb, cos, sin, inner - 4, Math.max(1, innerRadius - 4));

        // You, in the middle
        float arrowSize = Math.max(7, size * 0.037f);
        Ui.arrow(g, cx, cy, arrowSize, MapConfig.rotate ? 0 : facing, Ui.PINK, 0xFF1A0F1C);

        if (MapConfig.compass) drawCompass(g, cx, cy, half, radius, cos, sin);

        if (MapConfig.infoText) drawText(g, p, cx, cy + half + 8, half);
        if (MapConfig.debug) drawDebug(g, s, right ? screenW - 8 : 8, bottom ? cy - half - Ui.lineHeight() - 10 : cy + half + 8 + textRows + 2, right);

        g.flush();
        RenderSystem.enableDepthTest();
        g.pose().popPose();
    }

    private static void checkFirstPixels(MapClient.Session s, double px, double pz, float ppb) {
        int level = Coords.levelFor(1.0 / ppb);
        Textures.Gl gl = s.textures.need(Coords.key(s.layer, level, Coords.tileAt((int) Math.floor(px), level), Coords.tileAt((int) Math.floor(pz), level)));
        if (gl.ready() && MapClient.joinNanos != 0) {
            MapClient.firstPixelsLogged = true;
            CharMap.LOG.info("[CharMap] minimap showing map {} ms after joining", (System.nanoTime() - MapClient.joinNanos) / 1_000_000L);
        }
    }

    private static void drawDots(GuiGraphics g, float cx, float cy, double px, double pz, float ppb, float cos, float sin, float half, float radius) {
        if (NEAR.isEmpty()) return;
        g.flush();
        Matrix4f m = g.pose().last().pose();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        float[] xy = new float[2];
        float dot = Math.max(2.4f, ppb * 0.9f + 1.4f);
        for (Entity e : NEAR) {
            if (e.isRemoved()) continue;
            float dx = (float) (e.getX() - px) * ppb, dz = (float) (e.getZ() - pz) * ppb;
            xy[0] = dx * cos - dz * sin;
            xy[1] = dx * sin + dz * cos;
            if (Shapes.toEdge(xy, half, radius)) continue;
            int colour = e instanceof Player ? 0xFFFFFFFF : e instanceof Enemy ? 0xFFFF5C6C : 0xFF9BE37A;
            hexagon(buf, m, cx + xy[0], cy + xy[1], dot + 1, 0xC0000000);
            hexagon(buf, m, cx + xy[0], cy + xy[1], dot, colour);
        }
        BufferUploader.drawWithShader(buf.end());
    }

    private static final float[] HEX = {1f, 0f, 0.5f, 0.866f, -0.5f, 0.866f, -1f, 0f, -0.5f, -0.866f, 0.5f, -0.866f};

    private static void hexagon(BufferBuilder buf, Matrix4f m, float x, float y, float r, int c) {
        int a = c >>> 24, rr = (c >> 16) & 255, gg = (c >> 8) & 255, bb = c & 255;
        for (int i = 0; i < 6; i++) {
            int j = (i + 1) % 6;
            buf.vertex(m, x, y, 0).color(rr, gg, bb, a).endVertex();
            buf.vertex(m, x + HEX[j * 2] * r, y + HEX[j * 2 + 1] * r, 0).color(rr, gg, bb, a).endVertex();
            buf.vertex(m, x + HEX[i * 2] * r, y + HEX[i * 2 + 1] * r, 0).color(rr, gg, bb, a).endVertex();
        }
    }

    private static void drawWaypoints(GuiGraphics g, MapClient.Session s, float cx, float cy, double px, double pz, float ppb, float cos, float sin, float half, float radius) {
        float[] xy = new float[2];
        for (Waypoint w : MapClient.WAYPOINTS) {
            if (!w.shown) continue;
            int[] at = w.placeIn(s.dimension);
            if (at == null) continue;
            float dx = (float) (at[0] + 0.5 - px) * ppb, dz = (float) (at[2] + 0.5 - pz) * ppb;
            xy[0] = dx * cos - dz * sin;
            xy[1] = dx * sin + dz * cos;
            boolean edge = Shapes.toEdge(xy, half, radius);
            float r = edge ? 4.5f : 6f;
            Ui.disc(g, cx + xy[0], cy + xy[1], r + 1.5f, 0xF0180E1B);
            Ui.disc(g, cx + xy[0], cy + xy[1], r, 0xFF000000 | w.colour);
        }
    }

    private static void drawMates(GuiGraphics g, Minecraft mc, MapClient.Session s, float cx, float cy, double px, double pz, float ppb, float cos, float sin, float half, float radius) {
        float[] xy = new float[2];
        for (Teammates.Mate mate : Teammates.list()) {
            if (!Teammates.sameDimension(mate, s.dimension)) continue;
            float dx = (float) (mate.x() - px) * ppb, dz = (float) (mate.z() - pz) * ppb;
            xy[0] = dx * cos - dz * sin;
            xy[1] = dx * sin + dz * cos;
            Shapes.toEdge(xy, half, radius);
            float r = Math.max(8, Ui.scale() * 4.5f);
            Teammates.head(g, mate, cx + xy[0], cy + xy[1], r);
            Ui.tag(g, mate.name(), cx + xy[0], cy + xy[1] + r + 2, 0xC0180E1B, Ui.TEXT);
        }
    }

    private static void drawCompass(GuiGraphics g, float cx, float cy, float half, float radius, float cos, float sin) {
        float[] edge = {sin * half * 3, -cos * half * 3};
        Shapes.toEdge(edge, half - 2, Math.max(1, radius - 2));
        float r = Math.max(7, Ui.lineHeight() * 0.52f);
        Ui.disc(g, cx + edge[0], cy + edge[1], r, 0xF0180E1B);
        Ui.textCentred(g, "N", cx + edge[0], cy + edge[1] - Ui.lineHeight() / 2f + 1, Ui.PINK);
        g.flush();
    }

    private static void drawText(GuiGraphics g, LocalPlayer p, float cx, float y, float half) {
        String coords = p.getBlockX() + ", " + p.getBlockY() + ", " + p.getBlockZ();
        int h = Ui.lineHeight();
        Ui.tag(g, coords, cx, y, 0xC0180E1B, Ui.TEXT);
        if (!biome.isEmpty()) Ui.tag(g, biome, cx, y + h + 6, 0xC0180E1B, Ui.TEXT_DIM);
    }

    private static void drawDebug(GuiGraphics g, MapClient.Session s, float x, float y, boolean right) {
        MapWorker w = s.worker;
        String line = String.format("%.2f ms/frame  chunks %d  colour %d  fill %d  load %d  up %d  gl %d/%d  tiles %d  %d fps",
                MapClient.frameMillis, s.pending.size(), w.snapshotsWaiting(), w.fillsWaiting(), w.requestsWaiting(), w.uploadsWaiting(), s.textures.inUse(), s.textures.budget(), w.tilesHeld, Minecraft.getInstance().getFps());
        int tw = Ui.width(line);
        float x0 = right ? x - tw - 12 : x;
        Ui.round(g, x0, y, x0 + tw + 12, y + Ui.lineHeight() + 4, 8, 0xC0180E1B);
        Ui.text(g, line, x0 + 6, y + 2, Ui.PINK);
        g.flush();
    }

    public static String debugLine() {
        MapClient.Session s = MapClient.session;
        if (s == null) return "no map open";
        MapWorker w = s.worker;
        return String.format("%.3f ms/frame, pending %d, colour %d, fill %d, load %d, up %d, gl %d/%d, tiles %d, coloured %d, unchanged %d, from disk %d",
                MapClient.frameMillis, s.pending.size(), w.snapshotsWaiting(), w.fillsWaiting(), w.requestsWaiting(), w.uploadsWaiting(), s.textures.inUse(), s.textures.budget(), w.tilesHeld, w.chunksColoured, w.chunksUnchanged, w.tilesLoadedFromDisk);
    }
}
