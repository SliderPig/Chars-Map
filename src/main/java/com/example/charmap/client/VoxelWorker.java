package com.example.charmap.client;

import com.example.charmap.CharMap;
import com.example.charmap.model.Coords;
import com.example.charmap.model.McaFile;
import com.example.charmap.model.Nbt;
import com.example.charmap.model.PatchPlan;
import com.example.charmap.model.VoxelChunk;
import com.example.charmap.model.VoxelFile;
import com.example.charmap.net.Net;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.client.model.data.ModelData;

// The real blocks near the 3D camera. Its own thread keeps the block data and builds models with the game's own block drawing code, so stairs are stairs and fences are fences.
// It sleeps unless the 3D view is open (on a server it also quietly keeps what you've seen, because there's no save file to read later).
public final class VoxelWorker implements Runnable {
    public record Built(long key, int generation, BufferBuilder opaque, BufferBuilder.RenderedBuffer opaqueData, int opaqueQuads, BufferBuilder clear, BufferBuilder.RenderedBuffer clearData, int clearQuads, int mask) {}

    public final ConcurrentLinkedQueue<Built> done = new ConcurrentLinkedQueue<>();
    private static final ArrayBlockingQueue<BufferBuilder> BUILDERS = new ArrayBlockingQueue<>(6);
    private static boolean buildersMade;
    private static final long MEMORY = 96L * 1024 * 1024;

    private final Path folder, regionDir;
    private final boolean host, keep;
    private final Registry<Biome> biomes;
    private final int minBuild, worldHeight;
    private final Thread thread;
    private volatile boolean running = true;

    private final ConcurrentLinkedQueue<ChunkSnapshot> captures = new ConcurrentLinkedQueue<>();
    private final AtomicInteger captureCount = new AtomicInteger();
    private final ConcurrentLinkedQueue<VoxelChunk> received = new ConcurrentLinkedQueue<>();
    private volatile long[] wanted = new long[0];
    private volatile int generation;
    private volatile int cutY = Integer.MAX_VALUE;
    private final ConcurrentLinkedQueue<Long> forget = new ConcurrentLinkedQueue<>();
    private volatile boolean serverHelps;

    // This thread's own
    private final LinkedHashMap<Long, VoxelData> memory = new LinkedHashMap<>(256, 0.75f, true);
    private long memoryBytes;
    private final LongOpenHashSet none = new LongOpenHashSet();
    private final LongOpenHashSet asked = new LongOpenHashSet();
    private final List<Long> toAsk = new ArrayList<>();
    private long lastAsk;
    private final Long2IntOpenHashMap built = new Long2IntOpenHashMap();
    private final LinkedHashMap<Long, McaFile> regions = new LinkedHashMap<>(8, 0.75f, true);
    private final LongOpenHashSet noRegion = new LongOpenHashSet();
    private final VoxelView view = new VoxelView();
    private final RandomSource random = RandomSource.create();
    private final PoseStack pose = new PoseStack();
    public volatile long meshNanos, meshes, chunksRead;

    public VoxelWorker(Path folder, Path regionDir, boolean host, boolean keep, Registry<Biome> biomes, int minBuild, int worldHeight) {
        this.folder = folder;
        this.regionDir = regionDir;
        this.host = host;
        this.keep = keep;
        this.biomes = biomes;
        this.minBuild = minBuild;
        this.worldHeight = worldHeight;
        view.minBuild = minBuild;
        view.worldHeight = worldHeight;
        built.defaultReturnValue(-1);
        thread = new Thread(this, "CharMap voxels");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 2);
        thread.start();
    }

    // ---- From the main thread ----

    public void capture(ChunkSnapshot s) {
        if (captureCount.get() >= 48) return; // busy: the next look at this chunk will do
        captures.add(s);
        captureCount.incrementAndGet();
        LockSupport.unpark(thread);
    }

    public void setWanted(long[] patchKeys, boolean serverHasMod) {
        wanted = patchKeys;
        serverHelps = serverHasMod;
        LockSupport.unpark(thread);
    }

    public void setCut(int y) {
        cutY = y;
        generation++;
        LockSupport.unpark(thread);
    }

    public void forget(long key) {
        forget.add(key);
    }

    public void receive(VoxelChunk c) {
        received.add(c);
        LockSupport.unpark(thread);
    }

    public void giveBack(BufferBuilder b) {
        BUILDERS.offer(b);
        LockSupport.unpark(thread);
    }

    public void close() {
        running = false;
        LockSupport.unpark(thread);
    }

    public int waiting() {
        return captureCount.get();
    }

    // ---- The thread ----

    @Override
    public void run() {
        synchronized (BUILDERS) {
            if (!buildersMade) {
                buildersMade = true;
                for (int i = 0; i < 6; i++) BUILDERS.add(new BufferBuilder(1 << 20));
            }
        }
        while (running) {
            boolean did = false;
            try {
                did = step();
            } catch (Throwable t) {
                CharMap.once("voxels", "The 3D view's thread hit a problem, carrying on", t);
            }
            if (!did) LockSupport.parkNanos(Map3D.open ? 4_000_000L : 50_000_000L);
        }
        memory.clear();
        regions.clear();
    }

    private boolean step() {
        boolean did = false;
        Long gone;
        while ((gone = forget.poll()) != null) built.remove((long) gone);
        ChunkSnapshot s = captures.poll();
        if (s != null) {
            captureCount.decrementAndGet();
            if (!s.empty()) took(VoxelData.from(s), true);
            did = true;
        }
        VoxelChunk r = received.poll();
        if (r != null) {
            took(VoxelData.of(r, biomes), false);
            toDisk(r);
            did = true;
        }
        if (!Map3D.open) return did;
        sendAsks();
        long[] list = wanted;
        int gen = generation;
        for (long key : list) {
            if (built.get(key) == gen) continue;
            if (BUILDERS.size() < 2) return did; // the last ones are still waiting to be sent to the graphics card
            if (mesh(key, gen)) return true;
        }
        return did;
    }

    private void put(long key, VoxelData d) {
        VoxelData old = memory.put(key, d);
        if (old != null) memoryBytes -= old.bytes();
        memoryBytes += d.bytes();
        none.remove(key);
        if (memoryBytes > MEMORY) {
            Iterator<Map.Entry<Long, VoxelData>> it = memory.entrySet().iterator();
            while (memoryBytes > MEMORY * 3 / 4 && it.hasNext()) {
                memoryBytes -= it.next().getValue().bytes();
                it.remove();
            }
        }
    }

    // Fresh blocks arrived: if they differ from what a model was built from, that model gets rebuilt.
    private void took(VoxelData d, boolean live) {
        long key = ChunkPos.asLong(d.chunkX, d.chunkZ);
        VoxelData old = memory.get(key);
        put(key, d);
        if (old == null || old.hash != d.hash) {
            built.remove(patchOf(d.chunkX, d.chunkZ));
            if (live && !host && keep && (old != null || !Files.exists(pathOf(d.chunkX, d.chunkZ)) || true)) {
                try {
                    toDisk(d.toChunk(biomes));
                } catch (Throwable t) {
                    CharMap.once("voxel-keep", "Couldn't keep a chunk's blocks for the 3D view", t);
                }
            }
        }
    }

    private static long patchOf(int chunkX, int chunkZ) {
        return Coords.key(0, 0, chunkX >> 2, chunkZ >> 2);
    }

    private Path pathOf(int chunkX, int chunkZ) {
        return folder.resolve("v." + Coords.regionOfChunk(chunkX) + "." + Coords.regionOfChunk(chunkZ) + ".cvx");
    }

    private void toDisk(VoxelChunk c) {
        try {
            VoxelFile.write(pathOf(c.chunkX, c.chunkZ), Coords.chunkIndex(c.chunkX, c.chunkZ), c.encode());
        } catch (IOException | RuntimeException e) {
            CharMap.once("voxel-write", "Couldn't save a chunk's blocks for the 3D view", e);
        }
    }

    // A chunk's blocks if there's any way to have them right now. Null: nothing there, or still on its way from the server.
    private VoxelData chunk(int cx, int cz, boolean[] waiting) {
        long key = ChunkPos.asLong(cx, cz);
        VoxelData d = memory.get(key);
        if (d != null) return d;
        if (none.contains(key)) return null;
        d = fromDisk(cx, cz);
        if (d != null) {
            chunksRead++;
            put(key, d);
            return d;
        }
        if (!host && serverHelps) {
            if (asked.add(key)) toAsk.add(key);
            waiting[0] = true;
            return null;
        }
        none.add(key);
        return null;
    }

    private VoxelData fromDisk(int cx, int cz) {
        try {
            byte[] saved = VoxelFile.read(pathOf(cx, cz), Coords.chunkIndex(cx, cz));
            if (saved != null) return VoxelData.of(VoxelChunk.decode(saved), biomes);
            if (!host || regionDir == null) return null;
            int rx = Coords.regionOfChunk(cx), rz = Coords.regionOfChunk(cz);
            long region = ChunkPos.asLong(rx, rz);
            if (noRegion.contains(region)) return null;
            McaFile mca = regions.get(region);
            if (mca == null) {
                Path file = regionDir.resolve("r." + rx + "." + rz + ".mca");
                if (!Files.exists(file)) {
                    noRegion.add(region);
                    return null;
                }
                mca = McaFile.read(file);
                regions.put(region, mca);
                if (regions.size() > 3) {
                    Iterator<Long> it = regions.keySet().iterator();
                    it.next();
                    it.remove();
                }
            }
            byte[] data = mca.chunk(Coords.chunkIndex(cx, cz));
            if (data == null) return null;
            VoxelChunk c = VoxelChunk.fromNbt(Nbt.read(data));
            return c == null ? null : VoxelData.of(c, biomes);
        } catch (IOException | RuntimeException e) {
            CharMap.once("voxel-read", "Couldn't read a chunk's blocks for the 3D view", e);
            return null;
        }
    }

    private void sendAsks() {
        if (toAsk.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastAsk < 200) return;
        lastAsk = now;
        int n = Math.min(64, toAsk.size());
        long[] list = new long[n];
        for (int i = 0; i < n; i++) list[i] = toAsk.remove(toAsk.size() - 1);
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.getConnection() != null) Net.CHANNEL.sendToServer(new Net.VoxelAsk(list));
        });
    }

    // Builds one patch's model. False if its chunks aren't all here yet.
    private boolean mesh(long key, int gen) {
        int px = Coords.keyX(key), pz = Coords.keyZ(key);
        int cx0 = px * 4, cz0 = pz * 4;
        boolean[] waiting = {false};
        int mask = 0;
        view.baseX = cx0 - 1;
        view.baseZ = cz0 - 1;
        view.cutY = cutY;
        // The model's own chunks, then the ring round them just to know what's next door.
        for (int z = 0; z < 4; z++) {
            for (int x = 0; x < 4; x++) {
                VoxelData d = chunk(cx0 + x, cz0 + z, waiting);
                view.grid[(z + 1) * VoxelView.ACROSS + x + 1] = d;
                if (d != null) mask |= 1 << (z * 4 + x);
            }
        }
        if (waiting[0]) return false;
        boolean[] ignore = {false};
        for (int z = -1; z <= 4; z++) {
            for (int x = -1; x <= 4; x++) {
                if (x >= 0 && x < 4 && z >= 0 && z < 4) continue;
                view.grid[(z + 1) * VoxelView.ACROSS + x + 1] = chunk(cx0 + x, cz0 + z, ignore);
            }
        }
        BufferBuilder opaque = BUILDERS.poll(), clear = BUILDERS.poll();
        if (opaque == null || clear == null) {
            if (opaque != null) BUILDERS.offer(opaque);
            if (clear != null) BUILDERS.offer(clear);
            return false;
        }
        long start = System.nanoTime();
        int oq = 0, cq = 0;
        BufferBuilder.RenderedBuffer od = null, cd = null;
        try {
            opaque.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX);
            clear.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX);
            Sink solid = new Sink(opaque), see = new Sink(clear);
            buildBlocks(cx0, cz0, solid, see);
            oq = solid.quads;
            cq = see.quads;
            od = opaque.end();
            cd = clear.end();
        } catch (Throwable t) {
            CharMap.once("voxel-mesh", "Couldn't build a piece of the 3D view", t);
            if (opaque.building()) opaque.discard();
            if (clear.building()) clear.discard();
            oq = 0;
            cq = 0;
        }
        meshNanos += System.nanoTime() - start;
        meshes++;
        built.put(key, gen);
        done.add(new Built(key, gen, opaque, od, oq, clear, cd, cq, mask));
        return true;
    }

    private void buildBlocks(int cx0, int cz0, Sink solid, Sink see) {
        Minecraft mc = Minecraft.getInstance();
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int ox = cx0 * 16, oz = cz0 * 16;
        Sink.view = view;
        for (int cz = 0; cz < 4; cz++) {
            for (int cx = 0; cx < 4; cx++) {
                VoxelData d = view.grid[(cz + 1) * VoxelView.ACROSS + cx + 1];
                if (d == null || d.height == 0) continue;
                int wx = (cx0 + cx) * 16, wz = (cz0 + cz) * 16;
                for (int up = 0; up < d.height; up++) {
                    int y = d.minY + up;
                    if (y > cutY) break;
                    for (int z = 0, i = up * 256; z < 16; z++) {
                        for (int x = 0; x < 16; x++, i++) {
                            int cell = d.cells[i];
                            if (cell == 0) continue;
                            BlockState state = d.palette[cell];
                            int bx = wx + x, bz = wz + z;
                            if (hidden(state, bx, y, bz, pos)) continue;
                            pos.set(bx, y, bz);
                            try {
                                drawBlock(dispatcher, state, pos, bx - ox, y, bz - oz, solid, see, ox, oz);
                            } catch (Throwable t) {
                                CharMap.once("voxel-block", "A block wouldn't draw in the 3D view: " + state, t);
                            }
                        }
                    }
                }
            }
        }
    }

    // A solid block with solid blocks on all sides: nothing of it can be seen. Most of the world is this.
    private boolean hidden(BlockState state, int x, int y, int z, BlockPos.MutableBlockPos pos) {
        if (!state.canOcclude() || !state.getFluidState().isEmpty()) return false;
        pos.set(x, y, z);
        if (!state.isSolidRender(view, pos)) return false;
        return solidAt(x + 1, y, z, pos) && solidAt(x - 1, y, z, pos) && solidAt(x, y + 1, z, pos) && solidAt(x, y - 1, z, pos) && solidAt(x, y, z + 1, pos) && solidAt(x, y, z - 1, pos);
    }

    private boolean solidAt(int x, int y, int z, BlockPos.MutableBlockPos pos) {
        BlockState s = view.at(x, y, z);
        if (!s.canOcclude()) return false;
        pos.set(x, y, z);
        return s.isSolidRender(view, pos);
    }

    private void drawBlock(BlockRenderDispatcher dispatcher, BlockState state, BlockPos.MutableBlockPos pos, int rx, int y, int rz, Sink solid, Sink see, int ox, int oz) {
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            RenderType layer = ItemBlockRenderTypes.getRenderLayer(fluid);
            Sink sink = layer == RenderType.translucent() ? see : solid;
            // The game draws fluids relative to their 16-block section.
            sink.shift((pos.getX() & ~15) - ox, pos.getY() & ~15, (pos.getZ() & ~15) - oz);
            sink.origin(ox, 0, oz);
            dispatcher.renderLiquid(pos, view, sink, state, fluid);
            sink.shift(0, 0, 0);
        }
        RenderShape shape = state.getRenderShape();
        if (shape == RenderShape.MODEL) {
            BakedModel model = dispatcher.getBlockModel(state);
            long seed = state.getSeed(pos);
            random.setSeed(seed);
            for (RenderType layer : model.getRenderTypes(state, random, ModelData.EMPTY)) {
                Sink sink = layer == RenderType.translucent() ? see : solid;
                sink.origin(ox, 0, oz);
                pose.pushPose();
                pose.translate(rx, y, rz);
                random.setSeed(seed);
                dispatcher.renderBatched(state, pos, view, pose, sink, true, random, ModelData.EMPTY, layer);
                pose.popPose();
            }
        } else if (shape == RenderShape.ENTITYBLOCK_ANIMATED) {
            // Chests, beds, signs: the game draws these from their live data, which a map doesn't have. A box in the right colours says what's there.
            box(dispatcher, state, pos, rx, y, rz, solid, ox, oz);
        }
    }

    private void box(BlockRenderDispatcher dispatcher, BlockState state, BlockPos pos, int rx, int y, int rz, Sink sink, int ox, int oz) {
        VoxelShape shape = state.getShape(view, pos);
        if (shape.isEmpty()) return;
        AABB b = shape.bounds();
        TextureAtlasSprite sprite = dispatcher.getBlockModel(state).getParticleIcon(ModelData.EMPTY);
        if (sprite == null) return;
        float x0 = rx + (float) b.minX, x1 = rx + (float) b.maxX, y0 = y + (float) b.minY, y1 = y + (float) b.maxY, z0 = rz + (float) b.minZ, z1 = rz + (float) b.maxZ;
        float u0 = sprite.getU0(), u1 = sprite.getU1(), v0 = sprite.getV0(), v1 = sprite.getV1();
        sink.origin(ox, 0, oz);
        sink.face(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, u0, v0, u1, v1, 1f, 0, 1, 0);
        sink.face(x1, y1, z1, x1, y0, z1, x1, y0, z0, x1, y1, z0, u0, v0, u1, v1, 0.6f, 1, 0, 0);
        sink.face(x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1, u0, v0, u1, v1, 0.6f, -1, 0, 0);
        sink.face(x0, y1, z1, x0, y0, z1, x1, y0, z1, x1, y1, z1, u0, v0, u1, v1, 0.8f, 0, 0, 1);
        sink.face(x1, y1, z0, x1, y0, z0, x0, y0, z0, x0, y1, z0, u0, v0, u1, v1, 0.8f, 0, 0, -1);
    }

    // Collects what the game's drawing code sends, four corners at a time, works out if the sun reaches that face, and writes it to the model.
    private static final class Sink implements VertexConsumer {
        static VoxelView view;
        // Where the sun is, from the north-west and high up, the way a model on a table is lit.
        private static final float SUN_X = -0.52f, SUN_Y = 0.74f, SUN_Z = -0.42f;
        private static final float SHADOW = 0.66f, AWAY = 0.86f;
        private static final int REACH = 32;

        final BufferBuilder out;
        int quads;
        private float shiftX, shiftY, shiftZ;
        private int originX, originZ;
        private final float[] xs = new float[4], ys = new float[4], zs = new float[4], us = new float[4], vs = new float[4];
        private final int[] rs = new int[4], gs = new int[4], bs = new int[4], as = new int[4];
        private int n;
        private float x, y, z, u, v, nx, ny = 1, nz;
        private int r = 255, g = 255, b = 255, a = 255;

        Sink(BufferBuilder out) {
            this.out = out;
        }

        void shift(float dx, float dy, float dz) {
            shiftX = dx;
            shiftY = dy;
            shiftZ = dz;
        }

        void origin(int ox, int oy, int oz) {
            originX = ox;
            originZ = oz;
        }

        @Override
        public VertexConsumer vertex(double px, double py, double pz) {
            x = (float) px + shiftX;
            y = (float) py + shiftY;
            z = (float) pz + shiftZ;
            return this;
        }

        @Override
        public VertexConsumer color(int cr, int cg, int cb, int ca) {
            r = cr;
            g = cg;
            b = cb;
            a = ca;
            return this;
        }

        @Override
        public VertexConsumer uv(float cu, float cv) {
            u = cu;
            v = cv;
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int cu, int cv) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int cu, int cv) {
            return this;
        }

        @Override
        public VertexConsumer normal(float cx, float cy, float cz) {
            nx = cx;
            ny = cy;
            nz = cz;
            return this;
        }

        @Override
        public void endVertex() {
            xs[n] = x;
            ys[n] = y;
            zs[n] = z;
            us[n] = u;
            vs[n] = v;
            rs[n] = r;
            gs[n] = g;
            bs[n] = b;
            as[n] = a;
            if (++n == 4) {
                n = 0;
                flush();
            }
        }

        @Override
        public void defaultColor(int cr, int cg, int cb, int ca) {}

        @Override
        public void unsetDefaultColor() {}

        void face(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, float u0, float v0, float u1, float v1, float shade, float fx, float fy, float fz) {
            int c = Math.round(255 * shade);
            normal(fx, fy, fz);
            color(c, c, c, 255);
            vertex(x0, y0, z0).uv(u0, v0).endVertex();
            vertex(x1, y1, z1).uv(u0, v1).endVertex();
            vertex(x2, y2, z2).uv(u1, v1).endVertex();
            vertex(x3, y3, z3).uv(u1, v0).endVertex();
        }

        private void flush() {
            float light = sunlight();
            for (int i = 0; i < 4; i++) {
                out.vertex(xs[i], ys[i], zs[i]).color((int) (rs[i] * light), (int) (gs[i] * light), (int) (bs[i] * light), as[i]).uv(us[i], vs[i]).endVertex();
            }
            quads++;
        }

        // Faces turned away from the sun are a bit darker. Faces towards it are fully lit unless something stands in the way.
        private float sunlight() {
            float facing = nx * SUN_X + ny * SUN_Y + nz * SUN_Z;
            if (facing <= 0.05f) return AWAY;
            float px = (xs[0] + xs[2]) * 0.5f + originX + nx * 0.6f;
            float py = (ys[0] + ys[2]) * 0.5f + ny * 0.6f;
            float pz = (zs[0] + zs[2]) * 0.5f + originZ + nz * 0.6f;
            int lastX = Integer.MIN_VALUE, lastY = 0, lastZ = 0;
            for (int t = 1; t <= REACH; t++) {
                int bx = (int) Math.floor(px + SUN_X * t), by = (int) Math.floor(py + SUN_Y * t), bz = (int) Math.floor(pz + SUN_Z * t);
                if (bx == lastX && by == lastY && bz == lastZ) continue;
                lastX = bx;
                lastY = by;
                lastZ = bz;
                if (view.blocksSun(bx, by, bz)) return SHADOW;
            }
            return 1f;
        }
    }
}
