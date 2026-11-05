package com.example.charmap.client;

import com.example.charmap.CharMap;
import com.example.charmap.model.Coords;
import com.mojang.blaze3d.platform.GlStateManager;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

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
    public long stripNanos, strips, slowestStrip;
    public long changes; // goes up whenever pixels land in any texture

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
                if (g == null) {
                    if (u.full) worker.wanted.remove(u.key);
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
                    if (g.id == 0) return; // every texture is on screen right now: try again next frame
                    g.empty = false;
                    g.ready = false;
                    clear(g.id);
                }
                if (!stateSet) {
                    stateSet = true;
                    GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
                    GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
                    GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
                    GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
                }
                int rows = Math.min(STRIP, u.h - currentRow);
                long before = System.nanoTime();
                sendStrip(g.id, u, rows);
                long took = System.nanoTime() - before;
                stripNanos += took;
                strips++;
                slowestStrip = Math.max(slowestStrip, took);
                currentRow += rows;
                uploadedThisFrame++;
                if (currentRow >= u.h) {
                    if (u.full) g.ready = true;
                    changes++;
                    finishCurrent();
                }
            }
        } catch (Throwable t) {
            CharMap.once("upload", "Couldn't send map pixels to the graphics card", t);
            current = null;
        }
    }

    private static final int STRIP = 32;
    // Through a pixel buffer the driver takes a copy and gets on with it later, rather than making the game wait until the graphics chip has finished with the texture.
    public static boolean usePixelBuffer = true;
    private IntBuffer staging;
    private int pixelBuffer, clearTarget;

    private void sendStrip(int id, MapWorker.Upload u, int rows) {
        if (staging == null) staging = MemoryUtil.memAllocInt(Coords.TILE * STRIP);
        staging.clear();
        for (int r = 0; r < rows; r++) staging.put(u.pixels, u.offset + (currentRow + r) * u.stride, u.w);
        staging.flip();
        GlStateManager._bindTexture(id);
        if (usePixelBuffer) {
            if (pixelBuffer == 0) pixelBuffer = GlStateManager._glGenBuffers();
            GlStateManager._glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pixelBuffer);
            GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, staging, GL15.GL_STREAM_DRAW);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, u.x, u.y + currentRow, u.w, rows, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, 0L);
            GlStateManager._glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        } else {
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, u.x, u.y + currentRow, u.w, rows, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, staging);
        }
    }

    // A new or reused texture starts see-through. The graphics chip does this itself in no time, so the pixels only need sending for the part with something on it.
    private void clear(int id) {
        Minecraft mc = Minecraft.getInstance();
        if (clearTarget == 0) clearTarget = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, clearTarget);
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, id, 0);
        boolean good = GlStateManager.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
        if (good) {
            GlStateManager._disableScissorTest();
            GlStateManager._colorMask(true, true, true, true);
            GlStateManager._clearColor(0, 0, 0, 0);
            GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT, false);
        }
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
        mc.getMainRenderTarget().bindWrite(true);
        if (!good) { // never seen, but a garbage texture would be worse than a slow one
            GlStateManager._bindTexture(id);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, Coords.TILE, Coords.TILE, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, new int[Coords.TILE * Coords.TILE]);
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
        if (staging != null) MemoryUtil.memFree(staging);
        staging = null;
        if (pixelBuffer != 0) GlStateManager._glDeleteBuffers(pixelBuffer);
        pixelBuffer = 0;
        if (clearTarget != 0) GlStateManager._glDeleteFramebuffers(clearTarget);
        clearTarget = 0;
    }
}
