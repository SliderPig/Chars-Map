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
import java.util.Comparator;
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
// Each frame it costs one slide of a ready-made picture, one batch of shapes and one batch of text.
public final class Minimap {
    private Minimap() {}

    private static final MinimapCache CACHE = new MinimapCache();

    private static String biome = "";
    static final List<Entity> NEAR = new ArrayList<>();
    private static int age;
    private static float speed;
    private static double lastX, lastZ;
    public static double drawMillis;
    private static String coords = "";
    private static int coordX = Integer.MIN_VALUE, coordY, coordZ;

    // Outlines are only worked out again when the size or shape changes.
    private static float shapeSize = -1, shapeRadius;
    private static float[] shape, shadow, innerEdge, ringOuter, ringFeather;

    static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        double dx = p.getX() - lastX, dz = p.getZ() - lastZ;
        speed = (float) Math.sqrt(dx * dx + dz * dz) * 20;
        lastX = p.getX();
        lastZ = p.getZ();
        age++;
        if (age % 10 != 0) return;
        BlockPos at = p.blockPosition();
        biome = mc.level.getBiome(at).unwrapKey().map(k -> prettyBiome(k.location().getNamespace(), k.location().getPath())).orElse("");
        NEAR.clear();
        if (MapConfig.dots) {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e == p || !(e instanceof LivingEntity) || e.isInvisible()) continue;
                NEAR.add(e);
            }
            // Nearest first: when there are too many, the far ones are the ones to leave off.
            if (NEAR.size() > 1) NEAR.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
            if (NEAR.size() > 96) NEAR.subList(96, NEAR.size()).clear();
        }
    }

    static boolean movingFast() {
        return speed > 12;
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

    static void close() {
        CACHE.close();
    }

    public static void render(GuiGraphics g, float partial) {
        Minecraft mc = Minecraft.getInstance();
        MapClient.Session s = MapClient.session;
        // Under the full-screen map nobody can see it.
        if (s == null || !MapConfig.enabled || !MapConfig.showMinimap || mc.player == null || mc.options.hideGui || mc.screen instanceof MapScreen) return;
        long start = System.nanoTime();
        try {
            draw(g, mc, s, partial);
        } catch (Throwable t) {
            CharMap.once("minimap", "Minimap couldn't draw, carrying on", t);
        }
        long took = System.nanoTime() - start;
        MapClient.addFrameTime(took);
        drawMillis += (took / 1_000_000.0 - drawMillis) * 0.05;
    }

    private static void outlines(float half, float radius, int corner) {
        if (half == shapeSize && radius == shapeRadius && shape != null && shape.length == (corner + 1) * 8) return;
        shapeSize = half;
        shapeRadius = radius;
        shape = Shapes.rounded(half, radius, corner);
        shadow = Shapes.rounded(half + 7, radius + 7, corner);
        innerEdge = Shapes.rounded(half - 1.5f, radius - 1.5f, corner);
        ringOuter = Shapes.rounded(half + 2f, radius + 2f, corner);
        ringFeather = Shapes.rounded(half + 3.2f, radius + 3.2f, corner);
    }

    private static void draw(GuiGraphics g, Minecraft mc, MapClient.Session s, float partial) {
        LocalPlayer p = mc.player;
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
        outlines(half, radius, corner);

        double px = Mth.lerp(partial, p.xOld, p.getX()), pz = Mth.lerp(partial, p.zOld, p.getZ());
        float yaw = p.getViewYRot(partial);
        float facing = (float) Math.toRadians(yaw - 180);
        float rotation = MapConfig.rotate ? -facing : 0;
        float ppb = MapConfig.pixelsPerBlock();

        // The ready-made picture: big enough to turn in and to slide a good way before it needs remaking.
        int side = Math.round(size * 2) + 2;
        float reach = MapConfig.rotate && !MapConfig.circle ? half * 1.42f : half;
        boolean smooth = MapConfig.rotate || ppb < 1;
        CACHE.update(s.textures, s.layer, px, pz, ppb, side, side / 2f - reach - 2, smooth);

        Ui.toRealPixels(g);
        RenderSystem.disableDepthTest();
        RenderSystem.setShaderColor(1, 1, 1, 1);

        drawPicture(g, cx, cy, px, pz, ppb, rotation);
        if (!MapClient.firstPixelsLogged) checkFirstPixels(s, px, pz, ppb);

        // Soft shadow round it, then the ring: a dark edge and a thin pink line, feathered so a circle stays round at any size.
        Ui.begin(g);
        Ui.band(g, cx, cy + 2, shape, shadow, 0x66000000, 0);
        Ui.band(g, cx, cy, innerEdge, shape, 0x00000000, 0x55000000);
        Ui.band(g, cx, cy, shape, ringOuter, Ui.PINK, Ui.PINK);
        Ui.band(g, cx, cy, ringOuter, ringFeather, Ui.PINK, Ui.PINK & 0xFFFFFF);

        float cos = (float) Math.cos(rotation), sin = (float) Math.sin(rotation);
        float inner = half - 7, innerRadius = Math.max(1, radius - 7);

        if (MapConfig.dots) drawMobs(g, s, cx, cy, px, pz, ppb, cos, sin, half - 4, Math.max(1, radius - 4));
        drawWaypoints(g, s, cx, cy, px, pz, ppb, cos, sin, inner, innerRadius);
        drawMates(g, s, cx, cy, px, pz, ppb, cos, sin, inner - 4, Math.max(1, innerRadius - 4));

        // You, in the middle
        float arrowSize = Math.max(7, size * 0.037f);
        Ui.arrow(g, cx, cy, arrowSize, MapConfig.rotate ? 0 : facing, Ui.PINK, 0xFF1A0F1C);

        if (MapConfig.compass) drawCompass(g, cx, cy, half, radius, cos, sin);
        if (MapConfig.infoText) drawText(g, p, cx, cy + half + 8);
        if (MapConfig.debug) drawDebug(g, s, right ? screenW - 8 : 8, bottom ? cy - half - Ui.lineHeight() - 10 : cy + half + 8 + textRows + 2, right);
        Ui.after(() -> Icons.flush(g));

        Ui.end(g);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        g.pose().popPose();
    }

    // One draw: the map shape, showing the right part of the picture.
    private static void drawPicture(GuiGraphics g, float cx, float cy, double px, double pz, float ppb, float rotation) {
        Matrix4f m = g.pose().last().pose();
        float cos = (float) Math.cos(-rotation), sin = (float) Math.sin(-rotation);
        float side = CACHE.side();
        float slideX = (float) (px - CACHE.originX()) * ppb, slideZ = (float) (pz - CACHE.originZ()) * ppb;
        int a = Math.round(MapConfig.opacity * 255);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, CACHE.textureId());
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int i = 0; i < shape.length; i += 2) {
            float x = shape[i], y = shape[i + 1];
            float tx = (x * cos - y * sin) + slideX + side / 2, ty = (x * sin + y * cos) + slideZ + side / 2;
            buf.vertex(m, cx + x, cy + y, 0).uv(tx / side, 1f - ty / side).color(255, 255, 255, a).endVertex();
        }
        BufferUploader.drawWithShader(buf.end());
    }

    private static void checkFirstPixels(MapClient.Session s, double px, double pz, float ppb) {
        int level = Coords.levelFor(1.0 / ppb);
        Textures.Gl gl = s.textures.need(Coords.key(s.layer, level, Coords.tileAt((int) Math.floor(px), level), Coords.tileAt((int) Math.floor(pz), level)));
        if (gl.ready() && MapClient.joinNanos != 0) {
            MapClient.firstPixelsLogged = true;
            CharMap.LOG.info("[CharMap] minimap showing map {} ms after joining", (System.nanoTime() - MapClient.joinNanos) / 1_000_000L);
        }
    }

    static int ringColour(Entity e) {
        return e instanceof Player ? Ui.PINK : e instanceof Enemy ? 0xFFE5484D : 0xFF7BC96F;
    }

    private static void drawMobs(GuiGraphics g, MapClient.Session s, float cx, float cy, double px, double pz, float ppb, float cos, float sin, float half, float radius) {
        if (NEAR.isEmpty()) return;
        float[] xy = new float[2];
        float dot = Math.max(2.4f, ppb * 0.9f + 1.4f);
        float r = Math.max(6f, Ui.scale() * 3.5f);
        float limit = half + 8;
        int shown = 0;
        for (Entity e : NEAR) {
            if (e.isRemoved()) continue;
            float dx = (float) (e.getX() - px) * ppb, dz = (float) (e.getZ() - pz) * ppb;
            if (Math.abs(dx) > limit * 1.5f || Math.abs(dz) > limit * 1.5f) continue;
            xy[0] = dx * cos - dz * sin;
            xy[1] = dx * sin + dz * cos;
            if (Shapes.toEdge(xy, half, radius)) continue;
            if (++shown > 48) break;
            float x = cx + xy[0], y = cy + xy[1];
            int colour = ringColour(e);
            int cell = !MapConfig.icons ? -1 : e instanceof Player ? Icons.playerCell(e.getUUID()) : Icons.mobCell(e.getType(), s.worker);
            if (cell < 0) {
                Ui.dot(x, y, dot + 1, 0xC0000000);
                Ui.dot(x, y, dot, colour);
                continue;
            }
            Ui.dot(x, y, r + 1.6f, 0xE0180E1B);
            Ui.dot(x, y, r + 0.8f, colour);
            Icons.add(cell, x, y, r);
            if (e instanceof Player && MapConfig.playerNames) Ui.tag(g, e.getName().getString(), x, y + r + 2, 0xC0180E1B, Ui.TEXT);
        }
    }

    private static void drawWaypoints(GuiGraphics g, MapClient.Session s, float cx, float cy, double px, double pz, float ppb, float cos, float sin, float half, float radius) {
        if (MapClient.WAYPOINTS.isEmpty()) return;
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
            Ui.dot(cx + xy[0], cy + xy[1], r + 2f, 0xF0180E1B);
            Ui.disc(g, cx + xy[0], cy + xy[1], r, 0xFF000000 | w.colour);
        }
    }

    private static void drawMates(GuiGraphics g, MapClient.Session s, float cx, float cy, double px, double pz, float ppb, float cos, float sin, float half, float radius) {
        List<Teammates.Mate> mates = Teammates.list();
        if (mates.isEmpty()) return;
        float[] xy = new float[2];
        for (Teammates.Mate mate : mates) {
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
        Ui.dot(cx + edge[0], cy + edge[1], r, 0xF0180E1B);
        Ui.textCentred(g, "N", cx + edge[0], cy + edge[1] - Ui.lineHeight() / 2f + 1, Ui.PINK);
    }

    private static void drawText(GuiGraphics g, LocalPlayer p, float cx, float y) {
        int bx = p.getBlockX(), by = p.getBlockY(), bz = p.getBlockZ();
        if (bx != coordX || by != coordY || bz != coordZ) {
            coordX = bx;
            coordY = by;
            coordZ = bz;
            coords = bx + ", " + by + ", " + bz;
        }
        Ui.tag(g, coords, cx, y, 0xC0180E1B, Ui.TEXT);
        if (!biome.isEmpty()) Ui.tag(g, biome, cx, y + Ui.lineHeight() + 6, 0xC0180E1B, Ui.TEXT_DIM);
    }

    private static void drawDebug(GuiGraphics g, MapClient.Session s, float x, float y, boolean right) {
        MapWorker w = s.worker;
        String line = String.format("%.2f ms/frame  chunks %d  colour %d  fill %d  load %d  up %d  gl %d/%d  tiles %d  %d fps",
                MapClient.frameMillis, s.pending.size(), w.snapshotsWaiting(), w.fillsWaiting(), w.requestsWaiting(), w.uploadsWaiting(), s.textures.inUse(), s.textures.budget(), w.tilesHeld, Minecraft.getInstance().getFps());
        int tw = Ui.width(line);
        float x0 = right ? x - tw - 12 : x;
        Ui.round(g, x0, y, x0 + tw + 12, y + Ui.lineHeight() + 4, 8, 0xC0180E1B);
        Ui.text(g, line, x0 + 6, y + 2, Ui.PINK);
    }

    public static int composes() {
        return CACHE.composes;
    }

    public static String debugLine() {
        MapClient.Session s = MapClient.session;
        if (s == null) return "no map open";
        MapWorker w = s.worker;
        Textures t = s.textures;
        String line = String.format("%.3f ms/frame (draw %.3f), pending %d, colour %d, fill %d, load %d, up %d, gl %d/%d, tiles %d, coloured %d, unchanged %d, from disk %d, strip avg %d us max %d us, chunk copy avg %d us max %d us, worker: colour %d us, tile load %d us, picture remade %d times, faces pictured %d loaded %d",
                MapClient.frameMillis, drawMillis, s.pending.size(), w.snapshotsWaiting(), w.fillsWaiting(), w.requestsWaiting(), w.uploadsWaiting(), t.inUse(), t.budget(), w.tilesHeld, w.chunksColoured, w.chunksUnchanged, w.tilesLoadedFromDisk,
                t.stripNanos / Math.max(1, t.strips) / 1000, t.slowestStrip / 1000, MapClient.copyNanos / Math.max(1, MapClient.snapshotsTaken) / 1000, MapClient.slowestCopy / 1000, w.colourNanos / Math.max(1, w.chunksColoured + w.chunksUnchanged) / 1000, w.loadNanos / Math.max(1, w.tilesLoaded) / 1000, CACHE.composes, Icons.pictured, Icons.loaded);
        t.slowestStrip = 0;
        t.stripNanos = 0;
        t.strips = 0;
        return line;
    }
}
