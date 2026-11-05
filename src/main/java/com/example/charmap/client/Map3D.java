package com.example.charmap.client;

import com.example.charmap.CharMap;
import com.example.charmap.model.Coords;
import com.example.charmap.model.PatchPlan;
import com.example.charmap.net.Net;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexSorting;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.joml.FrustumIntersection;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;

// The world map in 3D, like a little model of your world on a table that you can turn around.
// Near the middle: real blocks, built with the game's own block drawing, so houses look like houses, and the real mobs walking about. Further out: the map's colours and heights as columns, getting coarser, fading into haze.
// Everything is built on other threads and nothing here runs unless the 3D view is open, so it costs the minimap nothing.
public final class Map3D {
    public static volatile boolean open;

    private static final int MAX_BUILDS = 3, MOST_MOBS = 64;
    private static final float FOV = (float) Math.toRadians(55);
    private static final long UPLOAD_BUDGET = 3_000_000L, CAPTURE_BUDGET = 700_000L;
    static final int SKY_TOP = 0xFF86ACE0, SKY_LOW = 0xFFF4DCE8;
    private static final float[] FOG = {0xF4 / 255f, 0xDC / 255f, 0xE8 / 255f, 1f};
    public static final float MIN_PITCH = 0.16f, MAX_PITCH = 1.5f;

    public record Built(long key, BufferBuilder builder, BufferBuilder.RenderedBuffer data, float minY, float maxY, int quads, int mask) {}

    static final ConcurrentLinkedQueue<Built> DONE = new ConcurrentLinkedQueue<>();
    static final ConcurrentLinkedQueue<Long> STALE = new ConcurrentLinkedQueue<>();
    private static final ArrayBlockingQueue<BufferBuilder> BUILDERS = new ArrayBlockingQueue<>(MAX_BUILDS);
    private static boolean buildersMade;

    static ShaderInstance colourShader, textureShader;

    private static final class Patch {
        final long key;
        VertexBuffer vb;
        boolean asked, empty, stale;
        int mask, askedMask;
        long bytes, lastFrame, builtAt;

        Patch(long key) {
            this.key = key;
        }
    }

    private static final class Blocks {
        final long key;
        VertexBuffer solid, clear;
        int mask;
        long bytes, lastFrame;

        Blocks(long key) {
            this.key = key;
        }
    }

    private final Long2ObjectOpenHashMap<Patch> patches = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Blocks> blocks = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet captured = new LongOpenHashSet();
    private long frame, bytes, blockBytes, lastWanted;
    private int building;
    public int drawnLast, blocksDrawnLast;
    private long[] wantedNow = new long[0];
    private final List<Entity> mobs = new ArrayList<>();
    private final Set<Entity> mobsDrawn = new HashSet<>();

    public double targetX, targetY = 64, targetZ;
    public float yaw = 0.5f, pitch = 0.72f, distance = 160, wantDistance = 160;
    private float yawSpeed, pitchSpeed;
    public boolean turning;
    private final Matrix4f projection = new Matrix4f(), view = new Matrix4f(), both = new Matrix4f(), one = new Matrix4f();
    private double eyeX, eyeY, eyeZ;
    private int screenW, screenH;
    private final Vector4f vec = new Vector4f();
    private long lastFrameNanos;
    private int cutSent = Integer.MAX_VALUE;
    public int cutY = Integer.MAX_VALUE;
    private long cutChangedAt;

    public void look(double x, double y, double z, float dist) {
        targetX = x;
        targetY = y;
        targetZ = z;
        distance = dist;
        wantDistance = dist;
    }

    public void reset(double x, double y, double z) {
        yaw = 0.5f;
        pitch = 0.72f;
        yawSpeed = 0;
        pitchSpeed = 0;
        look(x, y, z, 160);
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

    // Drag to turn. It keeps a little of its swing when you let go.
    public void turn(double dx, double dy, float seconds) {
        float dYaw = (float) -dx * 0.006f, dPitch = (float) dy * 0.005f;
        yaw += dYaw;
        pitch = Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitch + dPitch));
        if (seconds > 0) {
            yawSpeed += (dYaw / seconds - yawSpeed) * 0.35f;
            pitchSpeed += (dPitch / seconds - pitchSpeed) * 0.35f;
        }
    }

    public void zoom(double wheel) {
        wantDistance = Math.max(14, Math.min(16000, wantDistance * (float) Math.pow(0.84, wheel)));
    }

    public void fly(float forward, float right, float seconds) {
        double speed = Math.max(20, distance) * seconds;
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        targetX += (-sin * forward + cos * right) * speed;
        targetZ += (-cos * forward - sin * right) * speed;
    }

    private void move(float dt) {
        if (!turning && (Math.abs(yawSpeed) > 0.001f || Math.abs(pitchSpeed) > 0.001f)) {
            yaw += yawSpeed * dt;
            pitch = Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitch + pitchSpeed * dt));
            float slow = (float) Math.exp(-dt * 5);
            yawSpeed *= slow;
            pitchSpeed *= slow;
        }
        distance += (wantDistance - distance) * Math.min(1f, dt * 12);
        // The middle of the view rides on the ground, if the ground there is known.
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            int bx = (int) Math.floor(targetX), bz = (int) Math.floor(targetZ);
            LevelChunk chunk = mc.level.getChunkSource().getChunkNow(bx >> 4, bz >> 4);
            if (chunk != null) {
                int h = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, bx & 15, bz & 15);
                targetY += (h - targetY) * Math.min(1f, dt * 4);
            }
        }
    }

    // ---- Drawing ----

    public void render(net.minecraft.client.gui.GuiGraphics g, MapClient.Session s, int w, int h, float partial) {
        frame++;
        screenW = w;
        screenH = h;
        open = true;
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 0.016f : Math.min(0.1f, (now - lastFrameNanos) / 1e9f);
        lastFrameNanos = now;
        move(dt);
        makeBuilders();
        takeStale();
        upload(s);

        float cosP = (float) Math.cos(pitch);
        eyeX = targetX + distance * cosP * Math.sin(yaw);
        eyeY = targetY + distance * Math.sin(pitch);
        eyeZ = targetZ + distance * cosP * Math.cos(yaw);
        groundCheck();
        float fogEnd = Math.max(900f, distance * 7f), fogStart = fogEnd * 0.4f;
        float near = Math.max(0.5f, distance * 0.02f), far = fogEnd * 1.1f;
        projection.setPerspective(FOV, (float) w / Math.max(1, h), near, far);
        view.setLookAt(0, 0, 0, (float) (targetX - eyeX), (float) (targetY - eyeY), (float) (targetZ - eyeZ), 0, 1, 0);
        both.set(projection).mul(view);
        FrustumIntersection frustum = new FrustumIntersection(both);

        int radius = MapConfig.detailRadius;
        boolean useBlocks = radius > 0 && !s.ceiling && s.layer == 0 && distance < radius * 4f;
        if (useBlocks) planBlocks(s, radius);

        List<Patch> ready = new ArrayList<>();
        List<Patch> fallbacks = new ArrayList<>();
        List<Blocks> near3D = new ArrayList<>();
        List<Long> wanted = new ArrayList<>();
        int layer = s.layer;
        double detail2 = useBlocks ? (radius + 48.0) * (radius + 48.0) : -1;
        PatchPlan.Detail keepSplitting = (minX, minZ, maxX, maxZ) -> {
            if (detail2 < 0) return false;
            double dx = Math.max(0, Math.max(minX - targetX, targetX - maxX)), dz = Math.max(0, Math.max(minZ - targetZ, targetZ - maxZ));
            return dx * dx + dz * dz < detail2;
        };
        for (long key : PatchPlan.select(eyeX, eyeY, eyeZ, targetX, targetZ, far, (minX, minZ, maxX, maxZ) -> frustum.testAab((float) (minX - eyeX), (float) (-64 - eyeY), (float) (minZ - eyeZ), (float) (maxX - eyeX), (float) (384 - eyeY), (float) (maxZ - eyeZ)), keepSplitting, layer)) {
            int wantMask = 0;
            if (useBlocks && Coords.keyLevel(key) == 0) {
                Blocks b = blocks.get(key);
                if (b != null && (b.solid != null || b.clear != null)) {
                    near3D.add(b);
                    wantMask = b.mask;
                }
            }
            if (wantMask == 0xFFFF) continue; // real blocks cover the whole of it
            Patch p = patch(key);
            if (p.vb != null || p.empty) {
                if (p.vb != null) ready.add(p);
                boolean needsMask = p.mask != wantMask;
                if (((p.stale && System.nanoTime() - p.builtAt > 3_000_000_000L) || needsMask) && !p.asked) {
                    p.askedMask = wantMask;
                    wanted.add(key);
                }
                continue;
            }
            p.askedMask = wantMask;
            wanted.add(key);
            // Something coarser to look at until this one is there.
            long up = PatchPlan.ancestor(key, 2);
            Patch parent = patch(up);
            if (parent.vb != null) {
                if (!fallbacks.contains(parent)) fallbacks.add(parent);
            } else if (!parent.empty) wanted.add(0, up);
        }
        ask(s, wanted);

        // Sky first, then the model over it. Everything fades towards the bottom colour of the sky at the far edge.
        g.fillGradient(0, 0, g.guiWidth(), g.guiHeight(), SKY_TOP, SKY_LOW);
        g.flush();
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        float oldStart = RenderSystem.getShaderFogStart(), oldEnd = RenderSystem.getShaderFogEnd();
        float[] oldColour = RenderSystem.getShaderFogColor().clone();
        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
        RenderSystem.setShaderFogColor(FOG[0], FOG[1], FOG[2], FOG[3]);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        drawnLast = 0;
        blocksDrawnLast = 0;
        ShaderInstance flat = colourShader != null ? colourShader : GameRenderer.getPositionColorShader();
        for (Patch p : fallbacks) draw(p, flat, 3f * cellOf(p.key));
        for (Patch p : ready) draw(p, flat, 0);
        ShaderInstance textured = textureShader != null ? textureShader : GameRenderer.getPositionColorTexShader();
        if (!near3D.isEmpty()) {
            RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
            Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).setFilter(false, true);
            for (Blocks b : near3D) {
                b.lastFrame = frame;
                if (b.solid == null) continue;
                place(b.key);
                b.solid.bind();
                b.solid.drawWithShader(one, projection, textured);
                blocksDrawnLast++;
            }
        }
        VertexBuffer.unbind();
        try {
            drawMobs(partial);
        } catch (Throwable t) {
            CharMap.once("mobs3d", "Couldn't draw the mobs in the 3D view", t);
        }
        if (!near3D.isEmpty()) {
            // Water and glass last, seen through, far ones first.
            near3D.sort((x, y) -> Double.compare(distance2(y.key), distance2(x.key)));
            RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(false);
            for (Blocks b : near3D) {
                if (b.clear == null) continue;
                place(b.key);
                b.clear.bind();
                b.clear.drawWithShader(one, projection, textured);
            }
            RenderSystem.depthMask(true);
            VertexBuffer.unbind();
        }
        RenderSystem.setShaderFogStart(oldStart);
        RenderSystem.setShaderFogEnd(oldEnd);
        RenderSystem.setShaderFogColor(oldColour[0], oldColour[1], oldColour[2], oldColour[3]);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.disableCull();
        trim(s);
    }

    // The real mobs, with their real models and animation, wherever the game has them loaded: nearest ones only, so a farm doesn't hurt.
    private void drawMobs(float partial) {
        mobsDrawn.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || MapConfig.mobs3d == 0 || distance > 700) return;
        if (frame % 6 == 1 || mobs.isEmpty()) {
            mobs.clear();
            double reach = distance * 3 + 96;
            for (Entity e : mc.level.entitiesForRendering()) {
                if (!(e instanceof LivingEntity) || e.isInvisible() || e.isRemoved()) continue;
                double dx = e.getX() - targetX, dz = e.getZ() - targetZ;
                if (dx * dx + dz * dz < reach * reach) mobs.add(e);
            }
            mobs.sort((a, b) -> Double.compare(dist2(a), dist2(b)));
            if (mobs.size() > MOST_MOBS) mobs.subList(MOST_MOBS, mobs.size()).clear();
        }
        if (mobs.isEmpty()) return;
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.setIdentity();
        RenderSystem.applyModelViewMatrix();
        Lighting.setupLevel(new Matrix4f(view));
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        dispatcher.setRenderShadow(false);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        PoseStack pose = new PoseStack();
        pose.last().pose().set(view);
        pose.last().normal().set(new Matrix3f(view));
        // Further out they're a bit bigger than life, so you can still tell what they are.
        float big = MapConfig.mobs3d == 2 ? Math.max(1f, Math.min(2.5f, distance / 130f)) : 1f;
        try {
            for (Entity e : mobs) {
                if (e.isRemoved()) continue;
                try {
                    double x = Mth.lerp(partial, e.xOld, e.getX()) - eyeX, y = Mth.lerp(partial, e.yOld, e.getY()) - eyeY, z = Mth.lerp(partial, e.zOld, e.getZ()) - eyeZ;
                    float turn = Mth.rotLerp(partial, e.yRotO, e.getYRot());
                    pose.pushPose();
                    if (big != 1f) {
                        pose.translate(x, y, z);
                        pose.scale(big, big, big);
                        pose.translate(-x, -y, -z);
                    }
                    dispatcher.render(e, x, y, z, turn, partial, pose, buffers, LightTexture.FULL_BRIGHT);
                    pose.popPose();
                    mobsDrawn.add(e);
                } catch (Throwable t) {
                    CharMap.once("mob3d", "A mob wouldn't draw in the 3D view: " + e.getType(), t);
                }
            }
            buffers.endBatch();
        } finally {
            dispatcher.setRenderShadow(true);
            mc.gameRenderer.lightTexture().turnOffLightLayer();
            Lighting.setupFor3DItems();
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
    }

    private double dist2(Entity e) {
        double dx = e.getX() - eyeX, dy = e.getY() - eyeY, dz = e.getZ() - eyeZ;
        return dx * dx + dy * dy + dz * dz;
    }

    public boolean drawnAsModel(Entity e) {
        return mobsDrawn.contains(e);
    }

    public int mobsDrawnLast() {
        return mobsDrawn.size();
    }

    private double distance2(long key) {
        int size = PatchPlan.blocks(Coords.keyLevel(key));
        double dx = (Coords.keyX(key) + 0.5) * size - eyeX, dz = (Coords.keyZ(key) + 0.5) * size - eyeZ;
        return dx * dx + dz * dz;
    }

    // Never under the ground: if the camera would go into a hill, it tips up instead.
    private void groundCheck() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        int bx = (int) Math.floor(eyeX), bz = (int) Math.floor(eyeZ);
        LevelChunk chunk = mc.level.getChunkSource().getChunkNow(bx >> 4, bz >> 4);
        if (chunk == null) return;
        int ground = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, bx & 15, bz & 15) + 3;
        if (eyeY < ground) {
            float need = (float) Math.asin(Math.min(1.0, (ground - targetY) / Math.max(1f, distance)));
            pitch = Math.max(pitch, Math.min(MAX_PITCH, need));
            pitchSpeed = 0;
            float cosP = (float) Math.cos(pitch);
            eyeX = targetX + distance * cosP * Math.sin(yaw);
            eyeY = targetY + distance * Math.sin(pitch);
            eyeZ = targetZ + distance * cosP * Math.cos(yaw);
        }
    }

    private void place(long key) {
        int size = PatchPlan.blocks(0);
        float ox = (float) (Coords.keyX(key) * (double) size - eyeX), oz = (float) (Coords.keyZ(key) * (double) size - eyeZ);
        one.set(view).translate(ox, (float) -eyeY, oz);
    }

    // Which patches get real blocks: nearest the middle first. Chunks the game has loaded are copied now, so what you've just built is on the model.
    private void planBlocks(MapClient.Session s, int radius) {
        if (cutY != cutSent && System.nanoTime() - cutChangedAt > 250_000_000L) {
            cutSent = cutY;
            s.voxels.setCut(cutY);
        }
        long now = System.nanoTime();
        Minecraft mc = Minecraft.getInstance();
        if (now - lastWanted > 100_000_000L) {
            lastWanted = now;
            int size = PatchPlan.blocks(0);
            int cx = (int) Math.floor(targetX / size), cz = (int) Math.floor(targetZ / size), r = radius / size + 1;
            List<long[]> list = new ArrayList<>();
            long max = MapConfig.voxelMemoryMb * 1_048_576L;
            for (int z = -r; z <= r; z++) {
                for (int x = -r; x <= r; x++) {
                    double wx = (cx + x + 0.5) * size - targetX, wz = (cz + z + 0.5) * size - targetZ;
                    double d = wx * wx + wz * wz;
                    if (d > radius * (double) radius) continue;
                    list.add(new long[] {(long) d, Coords.key(0, 0, cx + x, cz + z)});
                }
            }
            list.sort((a, b) -> Long.compare(a[0], b[0]));
            int count = blockBytes > max ? 0 : list.size();
            long[] keys = new long[count];
            for (int i = 0; i < count; i++) keys[i] = list.get(i)[1];
            wantedNow = keys;
            boolean helps = mc.getConnection() != null && !MapClient.isHost() && Net.CHANNEL.isRemotePresent(mc.getConnection().getConnection());
            s.voxels.setWanted(keys, helps);
        }
        // Copy what's loaded right now, a little per frame.
        if (mc.level == null) return;
        long start = System.nanoTime();
        for (long key : wantedNow) {
            int cx0 = Coords.keyX(key) * 4, cz0 = Coords.keyZ(key) * 4;
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    long c = ChunkPos.asLong(cx0 + x, cz0 + z);
                    if (!captured.add(c)) continue;
                    LevelChunk chunk = mc.level.getChunkSource().getChunkNow(cx0 + x, cz0 + z);
                    if (chunk != null && !chunk.isEmpty()) s.voxels.capture(ChunkSnapshot.surface(chunk));
                    if (System.nanoTime() - start > CAPTURE_BUDGET) return;
                }
            }
        }
    }

    public void setCut(int y) {
        cutY = y;
        cutChangedAt = System.nanoTime();
    }

    private static int cellOf(long key) {
        return 1 << Coords.keyLevel(key);
    }

    private void draw(Patch p, ShaderInstance shader, float lower) {
        p.lastFrame = frame;
        int size = PatchPlan.blocks(Coords.keyLevel(p.key));
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
            s.worker.buildPatch(key, b, p.askedMask);
        }
    }

    private void upload(MapClient.Session s) {
        long start = System.nanoTime();
        Built b;
        while (System.nanoTime() - start < UPLOAD_BUDGET && (b = DONE.poll()) != null) {
            building--;
            try {
                Patch p = patch(b.key());
                p.asked = false;
                p.stale = false;
                p.mask = b.mask();
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
                }
            } catch (Throwable t) {
                CharMap.once("patch-upload", "Couldn't send a 3D map patch to the graphics card", t);
            } finally {
                BUILDERS.offer(b.builder());
            }
        }
        VoxelWorker.Built v;
        while (System.nanoTime() - start < UPLOAD_BUDGET && (v = s.voxels.done.poll()) != null) {
            try {
                Blocks k = blocks.get(v.key());
                if (k == null) {
                    k = new Blocks(v.key());
                    blocks.put(v.key(), k);
                }
                k.lastFrame = frame;
                k.mask = v.mask();
                blockBytes -= k.bytes;
                k.solid = send(k.solid, v.opaqueData(), v.opaqueQuads());
                k.clear = send(k.clear, v.clearData(), v.clearQuads());
                k.bytes = (v.opaqueQuads() + v.clearQuads()) * 96L;
                blockBytes += k.bytes;
            } catch (Throwable t) {
                CharMap.once("voxel-upload", "Couldn't send a piece of the 3D view to the graphics card", t);
            } finally {
                s.voxels.giveBack(v.opaque());
                s.voxels.giveBack(v.clear());
            }
        }
    }

    private static VertexBuffer send(VertexBuffer vb, BufferBuilder.RenderedBuffer data, int quads) {
        if (data == null) return vb;
        if (quads == 0) {
            data.release();
            if (vb != null) vb.close();
            return null;
        }
        if (vb == null) vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
        vb.bind();
        vb.upload(data);
        VertexBuffer.unbind();
        return vb;
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

    // Keeps to the memory settings; the ones not seen for longest go first.
    private void trim(MapClient.Session s) {
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
        long blockMax = MapConfig.voxelMemoryMb * 1_048_576L;
        if (blockBytes > blockMax) {
            var it = blocks.long2ObjectEntrySet().fastIterator();
            while (it.hasNext() && blockBytes > blockMax * 9 / 10) {
                Long2ObjectMap.Entry<Blocks> e = it.next();
                Blocks b = e.getValue();
                if (frame - b.lastFrame > 30) {
                    closeBlocks(b);
                    blockBytes -= b.bytes;
                    s.voxels.forget(b.key);
                    it.remove();
                }
            }
        }
    }

    private static void closeBlocks(Blocks b) {
        if (b.solid != null) b.solid.close();
        if (b.clear != null) b.clear.close();
    }

    public void close(MapClient.Session s) {
        open = false;
        lastFrameNanos = 0;
        mobs.clear();
        mobsDrawn.clear();
        for (Patch p : patches.values()) {
            if (p.vb != null) p.vb.close();
        }
        patches.clear();
        bytes = 0;
        for (Blocks b : blocks.values()) {
            closeBlocks(b);
            if (s != null) s.voxels.forget(b.key);
        }
        blocks.clear();
        blockBytes = 0;
        captured.clear();
        wantedNow = new long[0];
        if (s != null) {
            s.voxels.setWanted(wantedNow, false);
            VoxelWorker.Built v;
            while ((v = s.voxels.done.poll()) != null) {
                if (v.opaqueData() != null) v.opaqueData().release();
                if (v.clearData() != null) v.clearData().release();
                s.voxels.forget(v.key());
                s.voxels.giveBack(v.opaque());
                s.voxels.giveBack(v.clear());
            }
        }
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

    public long blockMemoryBytes() {
        return blockBytes;
    }

    // Where a place in the world lands on the screen, or null if it's behind the camera.
    public float[] project(double x, double y, double z) {
        vec.set((float) (x - eyeX), (float) (y - eyeY), (float) (z - eyeZ), 1f);
        both.transform(vec);
        if (vec.w <= 0.01f) return null;
        return new float[] {(vec.x / vec.w * 0.5f + 0.5f) * screenW, (0.5f - vec.y / vec.w * 0.5f) * screenH};
    }
}
