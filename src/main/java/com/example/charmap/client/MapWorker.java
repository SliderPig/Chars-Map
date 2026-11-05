package com.example.charmap.client;

import com.example.charmap.CharMap;
import com.example.charmap.model.ChunkSummary;
import com.example.charmap.model.Coords;
import com.example.charmap.model.HeightMesh;
import com.example.charmap.model.PatchPlan;
import com.example.charmap.model.Shade;
import com.example.charmap.model.TileCodec;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.DataFormatException;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;

// The map's own thread. It alone touches the tiles and the disk, so nothing here needs a lock and nothing here can ever hold the game up.
// The game hands it copies of chunks and asks for tiles; it hands back pixels for the main thread to send to the graphics card.
public final class MapWorker implements Runnable {
    // Pixels on their way to a texture.
    public static final class Upload {
        public final long key;
        public final int x, y, w, h;
        public final int[] pixels;
        public final int offset, stride; // where the first row starts in pixels, and how far apart rows are
        public final boolean full, empty;

        Upload(long key, int x, int y, int w, int h, int[] pixels, int offset, int stride, boolean full, boolean empty) {
            this.key = key;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.pixels = pixels;
            this.offset = offset;
            this.stride = stride;
            this.full = full;
            this.empty = empty;
        }
    }

    private static final class Tile {
        final long key;
        final int level;
        TileCodec.Base base;   // level 0
        int[] pixels;          // higher levels
        short[] heights;       // higher levels
        boolean unsaved;
        int dirtyX0 = Integer.MAX_VALUE, dirtyY0, dirtyX1, dirtyY1;
        long unsavedSince;

        Tile(long key) {
            this.key = key;
            this.level = Coords.keyLevel(key);
        }

        int pixel(int x, int y) {
            if (level > 0) return pixels[y * Coords.TILE + x];
            int i = y * Coords.TILE + x;
            TileCodec.Base b = base;
            int north = y > 0 ? b.height[i - Coords.TILE] : b.north[x];
            int west = x > 0 ? b.height[i - 1] : b.west[y];
            return Shade.hills(b.colour[i], b.height[i], north, west);
        }

        short height(int x, int y) {
            int i = y * Coords.TILE + x;
            return level > 0 ? heights[i] : base.height[i];
        }

        long bytes() {
            return 1_572_864;
        }

        void markDirty(int x0, int y0, int x1, int y1) {
            if (dirtyX0 == Integer.MAX_VALUE) {
                dirtyX0 = x0;
                dirtyY0 = y0;
                dirtyX1 = x1;
                dirtyY1 = y1;
            } else {
                dirtyX0 = Math.min(dirtyX0, x0);
                dirtyY0 = Math.min(dirtyY0, y0);
                dirtyX1 = Math.max(dirtyX1, x1);
                dirtyY1 = Math.max(dirtyY1, y1);
            }
        }
    }

    private final Path root;
    private final Registry<Biome> biomes;
    private final Thread thread;
    private volatile boolean running = true;
    private final CompletableFuture<Void> finished = new CompletableFuture<>();

    private final ConcurrentLinkedQueue<ChunkSnapshot> snapshots = new ConcurrentLinkedQueue<>();
    private final AtomicInteger snapshotCount = new AtomicInteger();
    private final ConcurrentLinkedQueue<ChunkSummary> fills = new ConcurrentLinkedQueue<>();
    private final AtomicInteger fillCount = new AtomicInteger();
    private final ConcurrentLinkedDeque<Long> requests = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Upload> uploads = new ConcurrentLinkedQueue<>();
    private final AtomicInteger uploadCount = new AtomicInteger();
    final Set<Long> wanted = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<int[]> spareArrays = new ConcurrentLinkedQueue<>();

    // Worker's own
    private final LinkedHashMap<Long, Tile> tiles = new LinkedHashMap<>(64, 0.75f, true);
    private final Set<Long> dirtyForUpload = new HashSet<>();
    private final Set<Long> missing = new HashSet<>();
    private long memory, maxMemory;
    private final int[] colours = new int[256];
    private final short[] heights = new short[256];
    private long lastFlush, lastSave;

    // For the debug line
    public volatile int tilesHeld;
    public volatile long chunksColoured, chunksUnchanged, tilesLoaded, tilesLoadedFromDisk, colourNanos, loadNanos;

    public MapWorker(Path root, Registry<Biome> biomes) {
        this.root = root;
        this.biomes = biomes;
        this.maxMemory = MapConfig.textureMemoryMb * 1_048_576L;
        this.thread = new Thread(this, "CharMap worker");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.start();
    }

    // ---- Called from other threads ----

    public void add(ChunkSnapshot s) {
        snapshots.add(s);
        snapshotCount.incrementAndGet();
        LockSupport.unpark(thread);
    }

    public void addFill(ChunkSummary s) {
        fills.add(s);
        fillCount.incrementAndGet();
        LockSupport.unpark(thread);
    }

    public void request(long key) {
        wanted.add(key);
        requests.addLast(key);
        LockSupport.unpark(thread);
    }

    public int snapshotsWaiting() {
        return snapshotCount.get();
    }

    public int fillsWaiting() {
        return fillCount.get();
    }

    public int requestsWaiting() {
        return requests.size();
    }

    public int uploadsWaiting() {
        return uploadCount.get();
    }

    public Upload nextUpload() {
        Upload u = uploads.poll();
        if (u != null) uploadCount.decrementAndGet();
        return u;
    }

    public void giveBack(int[] array) {
        if (array.length == TileCodec.PIXELS && spareArrays.size() < 16) spareArrays.add(array);
    }

    // When the fill last saw a region's chunks: lets it skip what hasn't changed since. Null if the map has nothing there.
    public CompletableFuture<int[]> stampsOf(int regionX, int regionZ) {
        CompletableFuture<int[]> out = new CompletableFuture<>();
        run(() -> {
            Tile t = tile(Coords.key(0, 0, regionX, regionZ), false);
            out.complete(t == null ? null : t.base.stamp.clone());
        });
        return out;
    }

    public void run(Runnable task) {
        tasks.add(task);
        LockSupport.unpark(thread);
    }

    // One patch of the 3D view, built here so the game never waits for it.
    public void buildPatch(long key, BufferBuilder builder) {
        run(() -> {
            float[] range = {Float.MAX_VALUE, -Float.MAX_VALUE};
            int quads = 0;
            BufferBuilder.RenderedBuffer data = null;
            try {
                int layer = Coords.keyLayer(key), level = Coords.keyLevel(key), n = PatchPlan.CELLS, cell = 1 << level, size = n + 2;
                int use = Math.min(level, Coords.MAX_LEVEL);
                int ox = Coords.keyX(key) * n * cell, oz = Coords.keyZ(key) * n * cell;
                int[] colour = new int[size * size];
                short[] height = new short[size * size];
                long lastKey = Long.MIN_VALUE;
                Tile t = null;
                for (int j = 0, o = 0; j < size; j++) {
                    int bz = oz + (j - 1) * cell;
                    for (int i = 0; i < size; i++, o++) {
                        int bx = ox + (i - 1) * cell;
                        long k = Coords.key(layer, use, Coords.tileAt(bx, use), Coords.tileAt(bz, use));
                        if (k != lastKey) {
                            lastKey = k;
                            t = tile(k, false);
                        }
                        if (t == null) {
                            height[o] = Shade.NO_HEIGHT;
                            continue;
                        }
                        int x = (bx >> use) & 511, z = (bz >> use) & 511;
                        colour[o] = t.pixel(x, z);
                        height[o] = t.height(x, z);
                    }
                }
                builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
                quads = HeightMesh.build(n, cell, colour, height, (x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, c) -> {
                    int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                    builder.vertex(x0, y0, z0).color(r, g, b, 255).endVertex();
                    builder.vertex(x1, y1, z1).color(r, g, b, 255).endVertex();
                    builder.vertex(x2, y2, z2).color(r, g, b, 255).endVertex();
                    builder.vertex(x3, y3, z3).color(r, g, b, 255).endVertex();
                    range[0] = Math.min(range[0], Math.min(y0, y2));
                    range[1] = Math.max(range[1], Math.max(y0, y2));
                });
                data = builder.end();
            } catch (Throwable e) {
                CharMap.once("patch", "Couldn't build a 3D map patch", e);
            }
            Map3D.DONE.add(new Map3D.Built(key, builder, data, range[0], range[1], quads));
        });
    }

    // Empties the current dimension's map, memory and disk.
    public void clearAll(Runnable after) {
        run(() -> {
            tiles.clear();
            dirtyForUpload.clear();
            missing.clear();
            memory = 0;
            try {
                if (Files.isDirectory(root)) {
                    Files.walkFileTree(root, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            if (file.getFileName().toString().endsWith(".cmt")) Files.deleteIfExists(file);
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            } catch (IOException | RuntimeException e) {
                CharMap.once("clear", "Couldn't clear all of the map files", e);
            }
            after.run();
        });
    }

    // Stop: everything unsaved goes to disk on this thread, and the game isn't kept waiting unless it's quitting.
    public CompletableFuture<Void> finish() {
        running = false;
        LockSupport.unpark(thread);
        return finished;
    }

    public void waitFinished(long millis) {
        try {
            finish().get(millis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            CharMap.once("finish", "Map saving took too long, carrying on", e);
        }
    }

    // ---- The thread ----

    @Override
    public void run() {
        try {
            lastSave = System.currentTimeMillis();
            while (running) {
                boolean did = false;
                try {
                    did = step();
                } catch (Throwable t) {
                    CharMap.once("worker", "Map thread hit a problem, carrying on", t);
                }
                if (!did) LockSupport.parkNanos(3_000_000L);
            }
        } finally {
            try {
                saveAll(Long.MAX_VALUE);
            } catch (Throwable t) {
                CharMap.once("final-save", "Couldn't save the map when leaving", t);
            }
            drainUploads();
            finished.complete(null);
        }
    }

    private void drainUploads() {
        uploads.clear();
        uploadCount.set(0);
    }

    private boolean step() {
        boolean did = false;
        // What's on screen right now comes first. Newest ask first: when you drag the map the older asks have scrolled away already.
        if (uploadCount.get() < 10) {
            Long key = requests.pollLast();
            if (key != null) {
                if (wanted.contains(key)) serve(key);
                did = true;
            }
        }
        Runnable task = tasks.poll();
        if (task != null) {
            task.run();
            did = true;
        }
        for (int i = 0; i < 6; i++) {
            ChunkSnapshot s = snapshots.poll();
            if (s == null) break;
            snapshotCount.decrementAndGet();
            colourLive(s);
            did = true;
        }
        if (snapshots.isEmpty() && requests.isEmpty()) {
            for (int i = 0; i < 4; i++) {
                ChunkSummary s = fills.poll();
                if (s == null) break;
                fillCount.decrementAndGet();
                colourFill(s);
                did = true;
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastFlush >= 30) {
            lastFlush = now;
            flushDirty();
        }
        if (now - lastSave >= 10_000 && !did) {
            lastSave = now;
            saveAll(now + 40);
            did = true;
        }
        return did;
    }

    private void serve(long key) {
        Tile t = tile(key, false);
        if (t == null) {
            post(new Upload(key, 0, 0, 0, 0, null, 0, 0, false, true));
            return;
        }
        sendWhole(t);
    }

    private void sendWhole(Tile t) {
        int[] out = spareArrays.poll();
        if (out == null) out = new int[TileCodec.PIXELS];
        int n = Coords.TILE;
        if (t.level > 0) System.arraycopy(t.pixels, 0, out, 0, out.length);
        else {
            TileCodec.Base b = t.base;
            for (int y = 0, i = 0; y < n; y++) {
                for (int x = 0; x < n; x++, i++) {
                    int north = y > 0 ? b.height[i - n] : b.north[x];
                    int west = x > 0 ? b.height[i - 1] : b.west[y];
                    out[i] = Shade.hills(b.colour[i], b.height[i], north, west);
                }
            }
        }
        t.dirtyX0 = Integer.MAX_VALUE;
        dirtyForUpload.remove(t.key);
        // Only the box that has anything in it. A tile with a few chunks on it goes in a few rows, not 512.
        int x0 = n, y0 = n, x1 = -1, y1 = -1;
        for (int y = 0, i = 0; y < n; y++) {
            for (int x = 0; x < n; x++, i++) {
                if (out[i] != 0) {
                    if (x < x0) x0 = x;
                    if (x > x1) x1 = x;
                    if (y < y0) y0 = y;
                    y1 = y;
                }
            }
        }
        if (x1 < 0) {
            giveBack(out);
            post(new Upload(t.key, 0, 0, 0, 0, null, 0, 0, false, true));
            return;
        }
        post(new Upload(t.key, x0, y0, x1 - x0 + 1, y1 - y0 + 1, out, y0 * n + x0, n, true, false));
    }

    private void post(Upload u) {
        uploads.add(u);
        uploadCount.incrementAndGet();
    }

    // Changed parts of tiles that are on screen go out together every so often, instead of every chunk on its own.
    private void flushDirty() {
        if (dirtyForUpload.isEmpty() || uploadCount.get() >= 24) return;
        Iterator<Long> it = dirtyForUpload.iterator();
        int sent = 0;
        while (it.hasNext() && sent < 16) {
            long key = it.next();
            it.remove();
            Tile t = tiles.get(key);
            if (t == null || t.dirtyX0 == Integer.MAX_VALUE) continue;
            if (!wanted.contains(key)) {
                t.dirtyX0 = Integer.MAX_VALUE;
                continue;
            }
            int w = t.dirtyX1 - t.dirtyX0, h = t.dirtyY1 - t.dirtyY0;
            if (w * h > TileCodec.PIXELS / 2) {
                sendWhole(t);
                sent++;
                continue;
            }
            int[] out = new int[w * h];
            for (int y = 0, o = 0; y < h; y++) {
                for (int x = 0; x < w; x++) out[o++] = t.pixel(t.dirtyX0 + x, t.dirtyY0 + y);
            }
            post(new Upload(key, t.dirtyX0, t.dirtyY0, w, h, out, 0, w, false, false));
            t.dirtyX0 = Integer.MAX_VALUE;
            sent++;
        }
    }

    private void colourLive(ChunkSnapshot s) {
        if (s.empty()) return;
        long before = System.nanoTime();
        int hash = Colourer.fromSnapshot(s, colours, heights);
        store(s.layer, s.chunkX, s.chunkZ, hash, (int) (System.currentTimeMillis() / 1000), false);
        colourNanos += System.nanoTime() - before;
    }

    private void colourFill(ChunkSummary s) {
        long before = System.nanoTime();
        int hash = Colourer.fromSummary(s, biomes, colours, heights);
        store(0, s.chunkX, s.chunkZ, hash, s.stamp, true);
        colourNanos += System.nanoTime() - before;
    }

    // The fresh colours of one chunk go into its tile, then up through the far levels.
    private void store(int layer, int chunkX, int chunkZ, int hash, int stamp, boolean fromFill) {
        int rx = Coords.regionOfChunk(chunkX), rz = Coords.regionOfChunk(chunkZ);
        Tile t = tile(Coords.key(layer, 0, rx, rz), true);
        int index = Coords.chunkIndex(chunkX, chunkZ);
        TileCodec.Base b = t.base;
        if (fromFill && b.stamp[index] != 0 && stamp <= b.stamp[index]) return; // you've seen it since that was saved
        if (b.hash[index] == hash) {
            chunksUnchanged++;
            if (stamp > b.stamp[index]) {
                b.stamp[index] = stamp;
                unsaved(t);
            }
            return;
        }
        b.hash[index] = hash;
        b.stamp[index] = stamp;
        int x0 = (chunkX & 31) * 16, z0 = (chunkZ & 31) * 16, n = Coords.TILE;
        for (int z = 0; z < 16; z++) {
            int o = (z0 + z) * n + x0, c = z << 4;
            for (int x = 0; x < 16; x++) {
                b.colour[o + x] = colours[c + x];
                b.height[o + x] = heights[c + x];
            }
        }
        chunksColoured++;
        unsaved(t);
        if (Map3D.open) staleAround(layer, chunkX, chunkZ);

        // Hills depend on the neighbour to the north and west, also across tile edges.
        if (x0 == 0) {
            Tile west = tile(Coords.key(layer, 0, rx - 1, rz), false);
            if (west != null) for (int z = 0; z < 16; z++) b.west[z0 + z] = west.base.height[(z0 + z) * n + n - 1];
        }
        if (z0 == 0) {
            Tile north = tile(Coords.key(layer, 0, rx, rz - 1), false);
            if (north != null) for (int x = 0; x < 16; x++) b.north[x0 + x] = north.base.height[(n - 1) * n + x0 + x];
        }
        // This chunk's own right and bottom edges change how the next pixels along are lit.
        int x1 = Math.min(n, x0 + 17), z1 = Math.min(n, z0 + 17);
        changed(t, x0, z0, x1, z1);
        if (x0 + 16 == n) {
            Tile east = tile(Coords.key(layer, 0, rx + 1, rz), false);
            if (east != null) {
                for (int z = 0; z < 16; z++) east.base.west[z0 + z] = heights[(z << 4) + 15];
                unsaved(east);
                changed(east, 0, z0, 1, z0 + 16);
            }
        }
        if (z0 + 16 == n) {
            Tile south = tile(Coords.key(layer, 0, rx, rz + 1), false);
            if (south != null) {
                for (int x = 0; x < 16; x++) south.base.north[x0 + x] = heights[(15 << 4) + x];
                unsaved(south);
                changed(south, x0, 0, x0 + 16, 1);
            }
        }
    }

    // Tells the 3D view which of its patches just changed.
    private static void staleAround(int layer, int chunkX, int chunkZ) {
        int bx = chunkX * 16, bz = chunkZ * 16;
        for (int level = 0; level <= PatchPlan.COARSEST; level++) {
            int size = PatchPlan.blocks(level);
            Map3D.STALE.add(Coords.key(layer, level, Math.floorDiv(bx, size), Math.floorDiv(bz, size)));
        }
    }

    private void unsaved(Tile t) {
        if (!t.unsaved) {
            t.unsaved = true;
            t.unsavedSince = System.currentTimeMillis();
        }
    }

    private void changed(Tile t, int x0, int y0, int x1, int y1) {
        if (wanted.contains(t.key)) {
            t.markDirty(x0, y0, x1, y1);
            dirtyForUpload.add(t.key);
        }
        if (t.level >= Coords.MAX_LEVEL) return;
        int cx = Coords.keyX(t.key), cz = Coords.keyZ(t.key);
        Tile p = tile(Coords.parent(t.key), true);
        int half = Coords.TILE / 2, offX = (cx & 1) * half, offZ = (cz & 1) * half;
        int ax0 = x0 & ~1, ay0 = y0 & ~1, ax1 = Math.min(Coords.TILE, (x1 + 1) & ~1), ay1 = Math.min(Coords.TILE, (y1 + 1) & ~1);
        for (int y = ay0; y < ay1; y += 2) {
            int o = (offZ + y / 2) * Coords.TILE + offX;
            for (int x = ax0; x < ax1; x += 2) {
                p.pixels[o + x / 2] = Shade.average4(t.pixel(x, y), t.pixel(x + 1, y), t.pixel(x, y + 1), t.pixel(x + 1, y + 1));
                p.heights[o + x / 2] = Shade.averageHeight(t.height(x, y), t.height(x + 1, y), t.height(x, y + 1), t.height(x + 1, y + 1));
            }
        }
        unsaved(p);
        changed(p, offX + ax0 / 2, offZ + ay0 / 2, offX + ax1 / 2, offZ + ay1 / 2);
    }

    // ---- The tile store ----

    private Path pathOf(long key) {
        int level = Coords.keyLevel(key);
        return root.resolve(Coords.layerFolder(Coords.keyLayer(key))).resolve("l" + level).resolve(Coords.keyX(key) + "_" + Coords.keyZ(key) + ".cmt");
    }

    private Tile tile(long key, boolean create) {
        Tile t = tiles.get(key);
        if (t != null) return t;
        if (!missing.contains(key)) t = load(key);
        if (t == null) {
            if (!create) {
                missing.add(key);
                return null;
            }
            missing.remove(key);
            t = new Tile(key);
            if (t.level > 0) {
                t.pixels = new int[TileCodec.PIXELS];
                t.heights = new short[TileCodec.PIXELS];
                java.util.Arrays.fill(t.heights, Shade.NO_HEIGHT);
            } else t.base = new TileCodec.Base();
        }
        tiles.put(key, t);
        memory += t.bytes();
        tilesHeld = tiles.size();
        trim();
        return t;
    }

    private Tile load(long key) {
        Path path = pathOf(key);
        long before = System.nanoTime();
        byte[] file;
        try {
            file = Files.readAllBytes(path);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException | RuntimeException e) {
            CharMap.once("tile-read", "Couldn't read a map tile: " + path, e);
            return null;
        }
        try {
            Tile t = new Tile(key);
            if (t.level > 0) {
                t.pixels = new int[TileCodec.PIXELS];
                t.heights = new short[TileCodec.PIXELS];
                TileCodec.decodeLod(file, t.pixels, t.heights);
            } else t.base = TileCodec.decodeBase(file);
            tilesLoaded++;
            tilesLoadedFromDisk++;
            loadNanos += System.nanoTime() - before;
            return t;
        } catch (DataFormatException | RuntimeException e) {
            CharMap.once("tile-bad", "A map tile was damaged, it'll be remade: " + path, e);
            return null;
        }
    }

    private void trim() {
        if (memory <= maxMemory) return;
        Iterator<Map.Entry<Long, Tile>> it = tiles.entrySet().iterator();
        // Keep the handful just touched: a chunk update is walking up through them right now.
        int keep = Coords.MAX_LEVEL + 7;
        while (memory > maxMemory && tiles.size() > keep && it.hasNext()) {
            Tile t = it.next().getValue();
            if (t.dirtyX0 != Integer.MAX_VALUE && wanted.contains(t.key)) continue;
            save(t);
            memory -= t.bytes();
            dirtyForUpload.remove(t.key);
            it.remove();
        }
        tilesHeld = tiles.size();
    }

    private void saveAll(long until) {
        List<Tile> all = new ArrayList<>(tiles.values());
        for (Tile t : all) {
            if (t.unsaved) {
                save(t);
                if (System.currentTimeMillis() > until) return;
            }
        }
    }

    // A virus scanner or the indexer can hold a brand new file for a moment, which Windows answers with "access denied": try a few times before calling it trouble.
    private static void moveIntoPlace(Path temp, Path path) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                try {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (java.nio.file.AccessDeniedException e) {
                if (attempt >= 4) throw e;
                LockSupport.parkNanos(30_000_000L);
            }
        }
    }

    private void save(Tile t) {
        if (!t.unsaved) return;
        t.unsaved = false;
        Path path = pathOf(t.key);
        try {
            byte[] bytes = t.level > 0 ? TileCodec.encodeLod(t.pixels, t.heights) : TileCodec.encodeBase(t.base);
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(temp, bytes);
            moveIntoPlace(temp, path);
        } catch (IOException | RuntimeException e) {
            CharMap.once("tile-write", "Couldn't save a map tile: " + path, e);
        }
    }
}
