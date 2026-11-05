package com.example.charmap.client;

import com.example.charmap.CharMap;
import com.example.charmap.model.Coords;
import com.example.charmap.model.PatchPlan;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;

// The world map in 3D: the same colours and heights the flat map keeps, shown as real blocks and mountains from a camera you can fly around.
// The world is cut into square patches that get coarser with distance, each built on the map's own thread and only sent to the graphics card here.
// Nothing here runs unless the 3D view is open, so it costs the minimap nothing.
public final class Map3D {
    public static volatile boolean open;

    private static final int MAX_BUILDS = 3;
    private static final float FOV = (float) Math.toRadians(60);
    private static final long UPLOAD_BUDGET = 2_000_000L;

    public record Built(long key, BufferBuilder builder, BufferBuilder.RenderedBuffer data, float minY, float maxY, int quads) {}

    static final ConcurrentLinkedQueue<Built> DONE = new ConcurrentLinkedQueue<>();
    static final ConcurrentLinkedQueue<Long> STALE = new ConcurrentLinkedQueue<>();
    private static final ArrayBlockingQueue<BufferBuilder> BUILDERS = new ArrayBlockingQueue<>(MAX_BUILDS);
    private static boolean buildersMade;

    private static final class Patch {
        final long key;
        VertexBuffer vb;
        boolean asked, empty, stale;
        float minY = -64, maxY = 320;
        long bytes, lastFrame, builtAt;

        Patch(long key) {
            this.key = key;
        }
    }

    private final Long2ObjectOpenHashMap<Patch> patches = new Long2ObjectOpenHashMap<>();
    private long frame, bytes;
    private int building;
    public int drawnLast, quadsLast;

    public double targetX, targetY = 64, targetZ;
    public float yaw = 0, pitch = 0.9f, distance = 400;
    private final Matrix4f projection = new Matrix4f(), view = new Matrix4f(), both = new Matrix4f(), one = new Matrix4f();
    private double eyeX, eyeY, eyeZ;
    private int screenW, screenH;
    private final Vector4f vec = new Vector4f();

    public void look(double x, double y, double z, float dist) {
        targetX = x;
        targetY = y;
        targetZ = z;
        distance = dist;
    }

    // ---- Moving the camera ----

    public void pan(double dx, double dy) {
        double scale = distance * 2 * Math.tan(FOV / 2) / Math.max(1, screenH);
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        targetX -= (cos * dx) * scale;
        targetZ -= (-sin * dx) * scale;
        targetX += (-sin * dy) * scale;
        targetZ += (-cos * dy) * scale;
    }

    public void turn(double dx, double dy) {
        yaw += (float) dx * 0.006f;
        pitch = Math.max(0.12f, Math.min(1.5f, pitch + (float) dy * 0.005f));
    }

    public void zoom(double wheel) {
        distance = Math.max(12, Math.min(16000, distance * (float) Math.pow(0.85, wheel)));
    }

    public void fly(float forward, float right, float seconds) {
        double speed = Math.max(20, distance) * seconds;
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        targetX += (-sin * forward + cos * right) * speed;
        targetZ += (-cos * forward - sin * right) * speed;
    }

    // ---- Drawing ----

    public void render(MapClient.Session s, int w, int h) {
        frame++;
        screenW = w;
        screenH = h;
        open = true;
        makeBuilders();
        takeStale();
        upload();

        float cosP = (float) Math.cos(pitch);
        eyeX = targetX + distance * cosP * Math.sin(yaw);
        eyeY = targetY + distance * Math.sin(pitch);
        eyeZ = targetZ + distance * cosP * Math.cos(yaw);
        float near = Math.max(0.5f, distance * 0.02f), far = Math.max(4096f, distance * 12f);
        projection.setPerspective(FOV, (float) w / Math.max(1, h), near, far);
        view.setLookAt(0, 0, 0, (float) (targetX - eyeX), (float) (targetY - eyeY), (float) (targetZ - eyeZ), 0, 1, 0);
        both.set(projection).mul(view);
        FrustumIntersection frustum = new FrustumIntersection(both);

        List<Patch> ready = new ArrayList<>();
        List<Patch> fallbacks = new ArrayList<>();
        List<Long> wanted = new ArrayList<>();
        int layer = s.layer;
        for (long key : PatchPlan.select(eyeX, eyeY, eyeZ, targetX, targetZ, far, (minX, minZ, maxX, maxZ) -> frustum.testAab((float) (minX - eyeX), (float) (-64 - eyeY), (float) (minZ - eyeZ), (float) (maxX - eyeX), (float) (384 - eyeY), (float) (maxZ - eyeZ)), layer)) {
            Patch p = patch(key);
            if (p.vb != null || p.empty) {
                if (p.vb != null) ready.add(p);
                if (p.stale && !p.asked && System.nanoTime() - p.builtAt > 3_000_000_000L) wanted.add(key);
                continue;
            }
            wanted.add(key);
            // Something coarser to look at until this one is there.
            long up = PatchPlan.ancestor(key, 2);
            Patch parent = patch(up);
            if (parent.vb != null) {
                if (!fallbacks.contains(parent)) fallbacks.add(parent);
            } else if (!parent.empty) wanted.add(0, up);
        }
        ask(s, wanted);

        RenderSystem.clearColor(0.09f, 0.06f, 0.11f, 1f);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, net.minecraft.client.Minecraft.ON_OSX);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        var shader = GameRenderer.getPositionColorShader();
        drawnLast = 0;
        quadsLast = 0;
        for (Patch p : fallbacks) draw(p, shader, 3f * cellOf(p.key));
        for (Patch p : ready) draw(p, shader, 0);
        VertexBuffer.unbind();
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, net.minecraft.client.Minecraft.ON_OSX);
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.disableCull();
        trim();
    }

    private static int cellOf(long key) {
        return 1 << Coords.keyLevel(key);
    }

    private void draw(Patch p, net.minecraft.client.renderer.ShaderInstance shader, float lower) {
        p.lastFrame = frame;
        int cell = cellOf(p.key), size = PatchPlan.CELLS * cell;
        float ox = (float) (Coords.keyX(p.key) * (double) size - eyeX), oz = (float) (Coords.keyZ(p.key) * (double) size - eyeZ);
        one.set(view).translate(ox, (float) -eyeY - lower, oz);
        p.vb.bind();
        p.vb.drawWithShader(one, projection, shader);
        drawnLast++;
    }

    private Patch patch(long key) {
        Patch p = patches.get(key);
        if (p == null) {
            p = new Patch(key);
            patches.put(key, p);
        }
        p.lastFrame = frame;
        return p;
    }

    private static void makeBuilders() {
        if (buildersMade) return;
        buildersMade = true;
        for (int i = 0; i < MAX_BUILDS; i++) BUILDERS.add(new BufferBuilder(1 << 19));
    }

    private void ask(MapClient.Session s, List<Long> wanted) {
        for (long key : wanted) {
            if (building >= MAX_BUILDS) return;
            Patch p = patch(key);
            if (p.asked) continue;
            BufferBuilder b = BUILDERS.poll();
            if (b == null) return;
            p.asked = true;
            building++;
            s.worker.buildPatch(key, b);
        }
    }

    private void upload() {
        long start = System.nanoTime();
        Built b;
        while (System.nanoTime() - start < UPLOAD_BUDGET && (b = DONE.poll()) != null) {
            building--;
            try {
                Patch p = patch(b.key());
                p.asked = false;
                p.stale = false;
                p.builtAt = System.nanoTime();
                if (b.data() == null || b.quads() == 0) {
                    if (b.data() != null) b.data().release();
                    p.empty = true;
                    if (p.vb != null) {
                        p.vb.close();
                        p.vb = null;
                        bytes -= p.bytes;
                        p.bytes = 0;
                    }
                } else {
                    if (p.vb == null) p.vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
                    p.vb.bind();
                    p.vb.upload(b.data());
                    VertexBuffer.unbind();
                    bytes -= p.bytes;
                    p.bytes = b.quads() * 64L;
                    bytes += p.bytes;
                    p.empty = false;
                    p.minY = b.minY();
                    p.maxY = b.maxY();
                }
            } catch (Throwable t) {
                CharMap.once("patch-upload", "Couldn't send a 3D map patch to the graphics card", t);
            } finally {
                BUILDERS.offer(b.builder());
            }
        }
    }

    private void takeStale() {
        Long key;
        while ((key = STALE.poll()) != null) {
            Patch p = patches.get((long) key);
            if (p != null) {
                p.stale = true;
                p.empty = false;
            }
        }
    }

    // Keeps to a few times the texture memory; the ones not seen for longest go first.
    private void trim() {
        long max = MapConfig.textureMemoryMb * 2L * 1_048_576L;
        if (frame % 60 == 0 || bytes > max) {
            var it = patches.long2ObjectEntrySet().fastIterator();
            while (it.hasNext()) {
                Long2ObjectMap.Entry<Patch> e = it.next();
                Patch p = e.getValue();
                boolean old = frame - p.lastFrame > 600 || (bytes > max && frame - p.lastFrame > 30);
                if (old && !p.asked) {
                    if (p.vb != null) p.vb.close();
                    bytes -= p.bytes;
                    it.remove();
                }
            }
        }
    }

    public void close() {
        open = false;
        for (Patch p : patches.values()) {
            if (p.vb != null) p.vb.close();
        }
        patches.clear();
        bytes = 0;
        // Anything still arriving gets put away, its builder kept for next time.
        Built b;
        while ((b = DONE.poll()) != null) {
            building--;
            if (b.data() != null) b.data().release();
            BUILDERS.offer(b.builder());
        }
        STALE.clear();
    }

    public long memoryBytes() {
        return bytes;
    }

    // Where a place in the world lands on the screen, or null if it's behind the camera.
    public float[] project(double x, double y, double z) {
        vec.set((float) (x - eyeX), (float) (y - eyeY), (float) (z - eyeZ), 1f);
        both.transform(vec);
        if (vec.w <= 0.01f) return null;
        return new float[] {(vec.x / vec.w * 0.5f + 0.5f) * screenW, (0.5f - vec.y / vec.w * 0.5f) * screenH};
    }
}
