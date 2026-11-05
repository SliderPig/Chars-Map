package com.example.charmap.client;

import com.example.charmap.CharMap;
import com.example.charmap.model.Coords;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.loading.FMLPaths;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.system.MemoryUtil;

// Faces for the maps. Every mob type and every player gets one cell in a single texture, so a crowd of them is one draw.
// A mob's face is pictured once from its real model and kept on disk, so next time it just loads. While you're moving fast nothing new gets pictured:
// the map shows a dot for that mob until you slow down, because a smooth minimap matters more than a face arriving a moment sooner.
public final class Icons {
    private Icons() {}

    private static final int CELL = 48, ACROSS = 16, SIZE = CELL * ACROSS, MAX_CELLS = ACROSS * ACROSS;

    private static TextureTarget atlas;
    private static int nextCell;
    private static final Map<EntityType<?>, Integer> MOBS = new HashMap<>();
    private static final Set<EntityType<?>> FAILED = new HashSet<>();
    private static final Set<EntityType<?>> ASKED = new HashSet<>();
    private static final ArrayDeque<EntityType<?>> TO_PICTURE = new ArrayDeque<>();
    private static final ConcurrentLinkedQueue<Loaded> LOADED = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<EntityType<?>> NOT_ON_DISK = new ConcurrentLinkedQueue<>();

    private record Loaded(EntityType<?> type, byte[] rgba) {}

    private record Head(int cell, ResourceLocation skin) {}

    private static final Map<UUID, Head> PLAYERS = new HashMap<>();
    private static final ArrayDeque<UUID> PLAYERS_TO_BAKE = new ArrayDeque<>();
    private static final Set<UUID> PLAYERS_ASKED = new HashSet<>();
    private static long lastPictured;

    private record Mark(int cell, float x, float y, float r) {}

    private static final List<Mark> MARKS = new ArrayList<>();
    public static int pictured, loaded;

    // Most recent face work, in ms, for the debug line.
    public static long bakeNanos;

    private static Path folder() {
        return FMLPaths.GAMEDIR.get().resolve("charmap").resolve("icons");
    }

    private static Path fileOf(EntityType<?> type) {
        return folder().resolve(Coords.safeName(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString().replace(':', '_')) + ".icon");
    }

    private static void ensureAtlas() {
        if (atlas != null) return;
        atlas = new TextureTarget(SIZE, SIZE, true, Minecraft.ON_OSX);
        atlas.setClearColor(0, 0, 0, 0);
        atlas.clear(Minecraft.ON_OSX);
        atlas.setFilterMode(GL11.GL_LINEAR);
        Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
    }

    // After a resource pack change faces might look different.
    public static void forget() {
        MOBS.clear();
        FAILED.clear();
        ASKED.clear();
        TO_PICTURE.clear();
        PLAYERS.clear();
        PLAYERS_ASKED.clear();
        PLAYERS_TO_BAKE.clear();
        nextCell = 0;
    }

    public static void forgetDisk() {
        forget();
        try (var files = Files.list(folder())) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (p.getFileName().toString().endsWith(".icon")) Files.deleteIfExists(p);
            }
        } catch (IOException | RuntimeException e) {
            CharMap.once("icons-clear", "Couldn't clear the saved faces", e);
        }
    }

    // The cell for this mob type, or -1 if there's none yet. Asking is cheap and starts the work.
    public static int mobCell(EntityType<?> type, MapWorker worker) {
        Integer cell = MOBS.get(type);
        if (cell != null) return cell;
        if (FAILED.contains(type) || !ASKED.add(type)) return -1;
        Path file = fileOf(type);
        worker.run(() -> {
            try {
                if (Files.exists(file)) {
                    byte[] raw = Files.readAllBytes(file);
                    if (raw.length == CELL * CELL * 4) {
                        LOADED.add(new Loaded(type, raw));
                        return;
                    }
                }
            } catch (IOException | RuntimeException e) {
                CharMap.once("icon-read", "Couldn't read a saved face", e);
            }
            NOT_ON_DISK.add(type);
        });
        return -1;
    }

    public static int playerCell(UUID id) {
        Head h = PLAYERS.get(id);
        if (h != null) return h.cell;
        if (PLAYERS_ASKED.add(id)) PLAYERS_TO_BAKE.add(id);
        return -1;
    }

    // Once a frame before drawing starts: at most one small piece of work. mayPicture is false when you're moving fast.
    static void work(boolean mayPicture, MapWorker worker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        EntityType<?> missing;
        while ((missing = NOT_ON_DISK.poll()) != null) TO_PICTURE.add(missing);
        long start = System.nanoTime();
        try {
            Loaded l = LOADED.poll();
            if (l != null) {
                put(l);
                return;
            }
            UUID id = PLAYERS_TO_BAKE.poll();
            if (id != null) {
                bakePlayer(mc, id);
                return;
            }
            refreshSkins(mc);
            if (mayPicture && !TO_PICTURE.isEmpty() && start - lastPictured > 150_000_000L) {
                lastPictured = start;
                picture(mc, TO_PICTURE.poll(), worker);
            }
        } catch (Throwable t) {
            CharMap.once("icons", "Couldn't make a face for the map", t);
        } finally {
            long took = System.nanoTime() - start;
            if (took > 200_000L) bakeNanos = took;
        }
    }

    private static int newCell() {
        return nextCell < MAX_CELLS ? nextCell++ : -1;
    }

    private static int cellX(int cell) {
        return (cell % ACROSS) * CELL;
    }

    private static int cellY(int cell) {
        return (cell / ACROSS) * CELL;
    }

    private static void put(Loaded l) {
        int cell = newCell();
        if (cell < 0) {
            FAILED.add(l.type);
            return;
        }
        ensureAtlas();
        ByteBuffer direct = MemoryUtil.memAlloc(l.rgba.length);
        try {
            direct.put(l.rgba).flip();
            RenderSystem.bindTexture(atlas.getColorTextureId());
            RenderSystem.pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
            RenderSystem.pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
            RenderSystem.pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, cellX(cell), SIZE - cellY(cell) - CELL, CELL, CELL, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, direct);
        } finally {
            MemoryUtil.memFree(direct);
        }
        MOBS.put(l.type, cell);
        loaded++;
    }

    // A real look at the mob: a fresh copy of it (not one in the world), facing us, framed on its head.
    private static void picture(Minecraft mc, EntityType<?> type, MapWorker worker) {
        LivingEntity mob;
        try {
            if (!(type.create(mc.level) instanceof LivingEntity living)) {
                FAILED.add(type);
                return;
            }
            mob = living;
        } catch (Throwable t) {
            FAILED.add(type); // some mobs can't be made outside the world
            return;
        }
        int cell = newCell();
        if (cell < 0) {
            FAILED.add(type);
            return;
        }
        ensureAtlas();
        float eye = mob.getEyeHeight();
        float frame = Math.max(0.55f, Math.min(mob.getBbWidth() * 1.2f, mob.getBbHeight() * 0.55f));
        int scale = Math.max(1, Math.round(CELL / frame));
        mob.yBodyRot = 180;
        mob.setYRot(180);
        mob.setXRot(0);
        mob.yHeadRot = 180;
        mob.yHeadRotO = 180;
        mob.yBodyRotO = 180;
        int cx = cellX(cell), cy = cellY(cell);
        beginAtlas();
        try {
            RenderSystem.enableScissor(cx, SIZE - cy - CELL, CELL, CELL);
            RenderSystem.clearColor(0, 0, 0, 0);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            RenderSystem.enableDepthTest();
            RenderSystem.enableBlend();
            InventoryScreen.renderEntityInInventory(g, cx + CELL / 2, cy + CELL / 2 + Math.round(eye * scale), scale, new Quaternionf().rotateZ((float) Math.PI), null, mob);
            g.flush();
            // Keep it for next time. Only ever happens once for each type of mob.
            ByteBuffer read = MemoryUtil.memAlloc(CELL * CELL * 4);
            try {
                GL11.glReadPixels(cx, SIZE - cy - CELL, CELL, CELL, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, read);
                byte[] raw = new byte[CELL * CELL * 4];
                read.get(raw);
                Path file = fileOf(type);
                worker.run(() -> {
                    try {
                        Files.createDirectories(file.getParent());
                        Files.write(file, raw);
                    } catch (IOException | RuntimeException e) {
                        CharMap.once("icon-write", "Couldn't save a face", e);
                    }
                });
            } finally {
                MemoryUtil.memFree(read);
            }
            MOBS.put(type, cell);
            pictured++;
        } catch (Throwable t) {
            CharMap.once("icon-render", "A mob wouldn't have its picture taken: " + type, t);
            FAILED.add(type);
        } finally {
            RenderSystem.disableScissor();
            endAtlas(mc);
        }
    }

    private static void bakePlayer(Minecraft mc, UUID id) {
        ResourceLocation skin = skinOf(mc, id);
        Head old = PLAYERS.get(id);
        int cell = old != null ? old.cell : newCell();
        if (cell < 0) return;
        ensureAtlas();
        beginAtlas();
        try {
            int cx = cellX(cell), cy = cellY(cell);
            RenderSystem.enableScissor(cx, SIZE - cy - CELL, CELL, CELL);
            RenderSystem.clearColor(0, 0, 0, 0);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            RenderSystem.disableScissor();
            RenderSystem.disableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderTexture(0, skin);
            Matrix4f m = new Matrix4f();
            BufferBuilder buf = Tesselator.getInstance().getBuilder();
            buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            face(buf, m, cx, cy, 8f / 64, 8f / 64);
            face(buf, m, cx, cy, 40f / 64, 8f / 64);
            BufferUploader.drawWithShader(buf.end());
        } finally {
            endAtlas(mc);
        }
        PLAYERS.put(id, new Head(cell, skin));
    }

    private static void face(BufferBuilder buf, Matrix4f m, int x, int y, float u, float v) {
        float s = 8f / 64;
        buf.vertex(m, x, y, 0).uv(u, v).endVertex();
        buf.vertex(m, x, y + CELL, 0).uv(u, v + s).endVertex();
        buf.vertex(m, x + CELL, y + CELL, 0).uv(u + s, v + s).endVertex();
        buf.vertex(m, x + CELL, y, 0).uv(u + s, v).endVertex();
    }

    // A skin often arrives a moment after the player does.
    private static long skinCheck;

    private static void refreshSkins(Minecraft mc) {
        long now = System.nanoTime();
        if (now - skinCheck < 1_000_000_000L || PLAYERS.isEmpty()) return;
        skinCheck = now;
        for (Map.Entry<UUID, Head> e : PLAYERS.entrySet()) {
            if (!skinOf(mc, e.getKey()).equals(e.getValue().skin)) {
                PLAYERS_TO_BAKE.add(e.getKey());
                return;
            }
        }
    }

    private static ResourceLocation skinOf(Minecraft mc, UUID id) {
        if (mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(id);
            if (info != null) return info.getSkinLocation();
        }
        return DefaultPlayerSkin.getDefaultSkin(id);
    }

    private static void beginAtlas() {
        atlas.bindWrite(true);
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, SIZE, SIZE, 0, -1000, 1000), VertexSorting.ORTHOGRAPHIC_Z);
        PoseStack view = RenderSystem.getModelViewStack();
        view.pushPose();
        view.setIdentity();
        RenderSystem.applyModelViewMatrix();
    }

    private static void endAtlas(Minecraft mc) {
        PoseStack view = RenderSystem.getModelViewStack();
        view.popPose();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.restoreProjectionMatrix();
        mc.getMainRenderTarget().bindWrite(true);
    }

    // ---- Drawing: collected while the rings go down, drawn as one batch straight after ----

    public static void add(int cell, float x, float y, float r) {
        if (cell >= 0) MARKS.add(new Mark(cell, x, y, r));
    }

    // Goes inside Ui.after.
    public static void flush(GuiGraphics g) {
        if (MARKS.isEmpty() || atlas == null) {
            MARKS.clear();
            return;
        }
        Matrix4f m = g.pose().last().pose();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, atlas.getColorTextureId());
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX);
        float s = 1f / ACROSS;
        for (Mark k : MARKS) {
            // The picture's up and down are flipped in the texture: the first row drawn is the bottom one there.
            float mu = (cellX(k.cell) + CELL / 2f) / SIZE, mv = 1f - (cellY(k.cell) + CELL / 2f) / SIZE;
            for (int i = 0; i < 12; i++) {
                double a0 = i * Math.PI / 6, a1 = (i + 1) * Math.PI / 6;
                float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
                buf.vertex(m, k.x, k.y, 0).uv(mu, mv).endVertex();
                buf.vertex(m, k.x + c0 * k.r, k.y + s0 * k.r, 0).uv(mu + c0 * s / 2, mv - s0 * s / 2).endVertex();
                buf.vertex(m, k.x + c1 * k.r, k.y + s1 * k.r, 0).uv(mu + c1 * s / 2, mv - s1 * s / 2).endVertex();
            }
        }
        BufferUploader.drawWithShader(buf.end());
        MARKS.clear();
    }
}
