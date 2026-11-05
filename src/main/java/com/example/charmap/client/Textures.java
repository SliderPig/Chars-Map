package com.example.charmap.client;

import com.example.charmap.CharMap;
import com.example.charmap.model.Coords;
import com.mojang.blaze3d.platform.GlStateManager;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayDeque;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

// The graphics card's side of the map: a fixed pool of 512x512 textures, reused oldest-first, filled a strip at a time within a tight budget per frame.
public final class Textures {
    public static final class Gl {
        final long key;
        int id;
        boolean ready;        // every row has arrived
        boolean empty;        // the map knows nothing there
        boolean linear;
        long lastFrame;
        long askedFrame;

        Gl(long key) {
            this.key = key;
        }

        public int id() {
            return id;
        }

        public boolean ready() {
            return ready;
        }
    }

    private final MapWorker worker;
    private final Long2ObjectOpenHashMap<Gl> tiles = new Long2ObjectOpenHashMap<>();
    private final ArrayDeque<Integer> free = new ArrayDeque<>();
    private final int maxTextures;
    private int made;
    private long frame;

    private MapWorker.Upload current;
    private int currentRow;

    public int uploadedThisFrame;

    public Textures(MapWorker worker) {
        this.worker = worker;
        this.maxTextures = Math.max(16, MapConfig.textureMemoryMb);
    }

    public int inUse() {
        return made - free.size();
    }

    public int budget() {
        return maxTextures;
    }

    public void nextFrame() {
        frame++;
        if (frame % 120 == 0) dropIdle();
    }

    // Drawing asks for a tile: whatever state it's in is returned straight away, never waited for.
    public Gl need(long key) {
        Gl g = tiles.get(key);
        if (g == null) {
            g = new Gl(key);
            g.askedFrame = frame;
            tiles.put(key, g);
            worker.request(key);
        } else if (!g.ready && !g.empty && frame - g.askedFrame > 90) {
            // Its turn may have been skipped when it scrolled away and came back.
            g.askedFrame = frame;
            worker.request(key);
        }
        g.lastFrame = frame;
        return g;
    }

    public void clearAll() {
        for (Gl g : tiles.values()) {
            if (g.id != 0) free.add(g.id);
            worker.wanted.remove(g.key);
        }
        tiles.clear();
        current = null;
    }

    // Tiles nobody has looked at for a while stop being kept up to date. Those holding a texture stay until the texture is needed.
    private void dropIdle() {
        var it = tiles.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2ObjectMap.Entry<Gl> e = it.next();
            Gl g = e.getValue();
            if (g.id == 0 && frame - g.lastFrame > 240) {
                worker.wanted.remove(g.key);
                it.remove();
            }
        }
    }

    private int takeTexture(long forKey) {
        Integer id = free.poll();
        if (id != null) return id;
        if (made < maxTextures) {
            made++;
            return makeTexture();
        }
        // Reuse the one unseen for longest. Never one drawn this frame or last.
        Gl oldest = null;
        for (Gl g : tiles.values()) {
            if (g.id != 0 && g.key != forKey && g.lastFrame < frame - 1 && (oldest == null || g.lastFrame < oldest.lastFrame)) oldest = g;
        }
        if (oldest == null) return 0;
        int out = oldest.id;
        oldest.id = 0;
        oldest.ready = false;
        worker.wanted.remove(oldest.key);
        tiles.remove(oldest.key);
        return out;
    }

    private int makeTexture() {
        int id = GlStateManager._genTexture();
        GlStateManager._bindTexture(id);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, Coords.TILE, Coords.TILE, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, (java.nio.IntBuffer) null);
        return id;
    }

    // Sends waiting pixels to the graphics card until the time is up. A whole tile goes in strips, so one big tile can't make a frame late.
    public void upload(long budgetNanos) {
        uploadedThisFrame = 0;
        long start = System.nanoTime();
        boolean stateSet = false;
        try {
            while (System.nanoTime() - start < budgetNanos) {
                if (current == null) {
                    current = worker.nextUpload();
                    currentRow = 0;
                    if (current == null) break;
                    if (current.empty) {
                        Gl g = tiles.get(current.key);
                        if (g != null && g.id == 0) g.empty = true;
                        current = null;
                        continue;
                    }
                }
                MapWorker.Upload u = current;
                Gl g = tiles.get(u.key);
                if (g == null && u.full) {
                    worker.wanted.remove(u.key);
                    finishCurrent();
                    continue;
                }
                if (g == null) {
                    finishCurrent();
                    continue;
                }
                if (g.id == 0) {
                    if (!u.full) { // a change to something with no texture yet: ask for the whole tile once
                        if (g.empty || frame - g.askedFrame > 90) {
                            g.empty = false;
                            g.askedFrame = frame;
                            worker.request(u.key);
                        }
                        finishCurrent();
                        continue;
                    }
                    g.id = takeTexture(u.key);
                    if (g.id == 0) { // every texture is on screen right now: try again next frame
                        return;
                    }
                    g.empty = false;
                    g.ready = false;
                }
                if (!stateSet) {
                    stateSet = true;
                    GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
                }
                GlStateManager._bindTexture(g.id);
                int rows = Math.min(64, u.h - currentRow);
                GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, u.w);
                GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
                GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, currentRow);
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, u.x, u.y + currentRow, u.w, rows, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, u.pixels);
                currentRow += rows;
                uploadedThisFrame++;
                if (currentRow >= u.h) {
                    if (u.full) g.ready = true;
                    finishCurrent();
                }
            }
        } catch (Throwable t) {
            CharMap.once("upload", "Couldn't send map pixels to the graphics card", t);
            current = null;
        } finally {
            if (stateSet) {
                GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
                GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
                GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            }
        }
    }

    private void finishCurrent() {
        if (current != null && current.pixels != null) worker.giveBack(current.pixels);
        current = null;
    }

    public void useFilter(Gl g, boolean linear) {
        if (g.linear == linear) return;
        g.linear = linear;
        GlStateManager._bindTexture(g.id);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, linear ? GL11.GL_LINEAR : GL11.GL_NEAREST);
    }

    // Frees everything on the graphics card when leaving a world.
    public void close() {
        for (Gl g : tiles.values()) {
            if (g.id != 0) GlStateManager._deleteTexture(g.id);
        }
        tiles.clear();
        for (int id : free) GlStateManager._deleteTexture(id);
        free.clear();
        made = 0;
        current = null;
    }
}
