package com.example.charmap.client;

import com.example.charmap.CharMap;
import com.example.charmap.model.Coords;
import com.example.charmap.model.FillOrder;
import com.example.charmap.model.Waypoint;
import com.mojang.blaze3d.platform.InputConstants;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;

// Where the game and the map meet. Everything here is short: chunks get copied, tiles get asked for, pixels get uploaded, and each has a small time budget per frame.
public final class MapClient {
    private MapClient() {}

    public static final KeyMapping KEY_MAP = key("world_map", GLFW.GLFW_KEY_M);
    public static final KeyMapping KEY_ADD = key("add_waypoint", GLFW.GLFW_KEY_B);
    public static final KeyMapping KEY_LIST = key("waypoints", InputConstants.UNKNOWN.getValue());
    public static final KeyMapping KEY_IN = key("zoom_in", GLFW.GLFW_KEY_EQUAL);
    public static final KeyMapping KEY_OUT = key("zoom_out", GLFW.GLFW_KEY_MINUS);
    public static final KeyMapping KEY_TOGGLE = key("toggle", InputConstants.UNKNOWN.getValue());

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.charmap." + name, code, "key.categories.charmap");
    }

    private static final long UPLOAD_BUDGET = 1_000_000L, MAP_OPEN_UPLOAD_BUDGET = 3_000_000L, SNAPSHOT_BUDGET = 500_000L, MOVING_SNAPSHOT_BUDGET = 300_000L;

    // Everything that belongs to the world and dimension you're in. Replaced when you change either.
    public static final class Session {
        public final String worldId, dimension;
        public final Path worldFolder;
        public final MapWorker worker;
        public final Textures textures;
        public final VoxelWorker voxels;
        final LongOpenHashSet pending = new LongOpenHashSet();
        public final boolean ceiling;
        public final int ceilingY;
        public int layer;
        int wantLayer, wantLayerFor;

        Session(String worldId, String dimension, Path worldFolder, MapWorker worker, VoxelWorker voxels, boolean ceiling, int ceilingY) {
            this.voxels = voxels;
            this.worldId = worldId;
            this.dimension = dimension;
            this.worldFolder = worldFolder;
            this.worker = worker;
            this.textures = new Textures(worker);
            this.ceiling = ceiling;
            this.ceilingY = ceilingY;
        }
    }

    public static Session session;
    public static final List<Waypoint> WAYPOINTS = new ArrayList<>();
    private static String waypointsWorld;

    private static int[] spiral = FillOrder.spiral(2);
    private static int spiralRadius = 2;
    private static final int[] NEAR = FillOrder.spiral(2);
    private static int nearIndex, farIndex;
    private static boolean diedShown;
    private static int rescanTick;

    // Measurements for the debug line and the report.
    public static long joinNanos;
    public static boolean firstPixelsLogged;
    public static double frameMillis;
    private static long thisFrame;
    public static long snapshotsTaken, copyNanos, slowestCopy;
    public static boolean worldShownLogged;
    // What the corner map costs per frame with the world map closed, kept apart so it can be measured on its own.
    public static final com.example.charmap.model.FrameStats MINIMAP_ONLY = new com.example.charmap.model.FrameStats();

    public static void setup() {
        MapConfig.load();
        IEventBus mod = FMLJavaModLoadingContext.get().getModEventBus();
        mod.addListener(MapClient::onKeys);
        mod.addListener(MapClient::onOverlays);
        mod.addListener(MapClient::onReloaders);
        mod.addListener(MapClient::onShaders);
        IEventBus forge = MinecraftForge.EVENT_BUS;
        forge.addListener(MapClient::onChunk);
        forge.addListener(MapClient::onTick);
        forge.addListener(MapClient::onRenderTick);
        forge.addListener(MapClient::onLogin);
        forge.addListener(MapClient::onLogout);
        forge.addListener(MapClient::onScreen);
        forge.addListener(MapClient::onCommands);
        forge.addListener(MapClient::onQuit);
        forge.addListener(MapClient::onWorldRender);
    }

    private static void onKeys(RegisterKeyMappingsEvent e) {
        e.register(KEY_MAP);
        e.register(KEY_ADD);
        e.register(KEY_LIST);
        e.register(KEY_IN);
        e.register(KEY_OUT);
        e.register(KEY_TOGGLE);
    }

    private static void onOverlays(RegisterGuiOverlaysEvent e) {
        e.registerAboveAll("minimap", (gui, g, partial, w, h) -> Minimap.render(g, partial));
    }

    private static void onShaders(net.minecraftforge.client.event.RegisterShadersEvent e) {
        try {
            e.registerShader(new net.minecraft.client.renderer.ShaderInstance(e.getResourceProvider(), new net.minecraft.resources.ResourceLocation(CharMap.MODID, "map3d_color"), com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR), s -> Map3D.colourShader = s);
            e.registerShader(new net.minecraft.client.renderer.ShaderInstance(e.getResourceProvider(), new net.minecraft.resources.ResourceLocation(CharMap.MODID, "map3d_texture"), com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR_TEX), s -> Map3D.textureShader = s);
        } catch (java.io.IOException | RuntimeException x) {
            CharMap.once("shaders", "The 3D view's shaders wouldn't load, it'll draw without haze", x);
        }
    }

    private static void onReloaders(RegisterClientReloadListenersEvent e) {
        e.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener) r -> {
            BlockColours.clear();
            Icons.forget();
        });
    }

    private static void onCommands(RegisterClientCommandsEvent e) {
        MapCommand.register(e.getDispatcher());
    }

    private static void onLogin(ClientPlayerNetworkEvent.LoggingIn e) {
        joinNanos = System.nanoTime();
        firstPixelsLogged = false;
        worldShownLogged = false;
    }

    private static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        endSession(false);
    }

    private static void onQuit(GameShuttingDownEvent e) {
        endSession(true);
    }

    private static void onChunk(ChunkEvent.Load e) {
        if (!e.getLevel().isClientSide() || session == null) return;
        ChunkPos p = e.getChunk().getPos();
        session.pending.add(p.toLong());
    }

    private static void onScreen(ScreenEvent.Opening e) {
        if (!(e.getScreen() instanceof DeathScreen)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (session == null || mc.player == null || diedShown) return;
        diedShown = true;
        BlockPos p = mc.player.blockPosition();
        WAYPOINTS.add(new Waypoint("Death", p.getX(), p.getY(), p.getZ(), session.dimension, 0xE5484D, true));
        Waypoint.trimDeaths(WAYPOINTS, 3);
        saveWaypoints();
    }

    private static void onWorldRender(RenderLevelStageEvent e) {
        if (e.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS && MapConfig.enabled && MapConfig.beacons && session != null) Beacons.render(e);
    }

    // ---- World and dimension changes ----

    private static void beginSession(Minecraft mc, ClientLevel level) {
        String world = worldId(mc);
        String dim = level.dimension().location().toString();
        Path folder = FMLPaths.GAMEDIR.get().resolve("charmap").resolve(world);
        Path dimFolder = folder.resolve(Coords.safeName(dim.replace(':', '_')));
        MapWorker worker = new MapWorker(dimFolder, level.registryAccess().registryOrThrow(Registries.BIOME));
        boolean ceiling = level.dimensionType().hasCeiling();
        int ceilingY = Math.min(level.dimensionType().logicalHeight() + level.getMinBuildHeight(), level.getMaxBuildHeight()) - 1;
        Path regionDir = null;
        if (mc.getSingleplayerServer() != null) {
            regionDir = net.minecraft.world.level.dimension.DimensionType.getStorageFolder(level.dimension(), mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT)).resolve("region");
        }
        VoxelWorker voxels = new VoxelWorker(dimFolder.resolve("blocks"), regionDir, mc.getSingleplayerServer() != null, MapConfig.keepBuildings, level.registryAccess().registryOrThrow(Registries.BIOME), level.getMinBuildHeight(), level.getHeight());
        session = new Session(world, dim, folder, worker, voxels, ceiling, ceilingY);
        if (!world.equals(waypointsWorld)) loadWaypoints(world, folder);
        diedShown = false;
        nearIndex = 0;
        farIndex = 0;
        // Chunks that loaded before this session began.
        var player = mc.player;
        if (player != null) {
            ChunkPos at = player.chunkPosition();
            int r = mc.options.getEffectiveRenderDistance() + 1;
            for (int z = -r; z <= r; z++) for (int x = -r; x <= r; x++) session.pending.add(ChunkPos.asLong(at.x + x, at.z + z));
        }
        Filler.onSession(session);
        CharMap.LOG.info("[CharMap] map open for {} in {}", world, dim);
    }

    private static void endSession(boolean quitting) {
        Session s = session;
        session = null;
        if (s == null) return;
        Filler.stop();
        saveWaypointsNow();
        MapConfig.save();
        try {
            s.textures.close();
            Minimap.close();
        } catch (Throwable t) {
            CharMap.once("gl-close", "Couldn't free the map's textures", t);
        }
        s.voxels.close();
        if (quitting) s.worker.waitFinished(8000);
        else s.worker.finish();
    }

    private static String worldId(Minecraft mc) {
        try {
            if (mc.getSingleplayerServer() != null) {
                Path root = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
                return "local_" + Coords.safeName(root.getFileName().toString());
            }
            if (mc.getCurrentServer() != null) return "server_" + Coords.safeName(mc.getCurrentServer().ip);
        } catch (Throwable t) {
            CharMap.once("world-id", "Couldn't name this world, using a general folder", t);
        }
        return "unknown";
    }

    public static boolean isHost() {
        return Minecraft.getInstance().getSingleplayerServer() != null;
    }

    // ---- Each tick ----

    private static void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            tick(mc);
        } catch (Throwable t) {
            CharMap.once("tick", "Map tick hit a problem, carrying on", t);
        }
    }

    private static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null || !MapConfig.enabled) {
            if (session != null) endSession(false);
            // Resting: the map key still opens the settings, so it can be woken up again.
            while (KEY_MAP.consumeClick()) {
                if (level != null && mc.screen == null) mc.setScreen(new SettingsScreen(null));
            }
            return;
        }
        if (session == null || !session.dimension.equals(level.dimension().location().toString())) {
            endSession(false);
            beginSession(mc, level);
        }
        if (player.isAlive()) diedShown = false;
        keys(mc);
        Session s = session;
        decideLayer(s, level, player);
        rescan(mc, s, player);
        Teammates.tick();
        Minimap.tick(mc);
        Filler.tick(s, player);
    }

    private static void keys(Minecraft mc) {
        while (KEY_MAP.consumeClick()) {
            if (mc.screen == null) mc.setScreen(new MapScreen());
        }
        while (KEY_ADD.consumeClick()) {
            if (mc.screen == null) mc.setScreen(WaypointScreen.newHere(null));
        }
        while (KEY_LIST.consumeClick()) {
            if (mc.screen == null) mc.setScreen(new WaypointListScreen(null));
        }
        while (KEY_IN.consumeClick()) {
            MapConfig.zoom = Math.min(MapConfig.ZOOMS.length - 1, MapConfig.zoom + 1);
            MapConfig.save();
        }
        while (KEY_OUT.consumeClick()) {
            MapConfig.zoom = Math.max(0, MapConfig.zoom - 1);
            MapConfig.save();
        }
        while (KEY_TOGGLE.consumeClick()) {
            MapConfig.showMinimap = !MapConfig.showMinimap;
            MapConfig.save();
        }
    }

    // Underground the map changes to a slice around your height. It waits a moment first, so walking under a tree doesn't flip it.
    private static void decideLayer(Session s, ClientLevel level, LocalPlayer player) {
        int y = player.getBlockY();
        int want = 0;
        if (s.ceiling) {
            if (MapConfig.netherMode == 1) want = Coords.layerForY(y);
        } else if (MapConfig.caveMode == 1) {
            BlockPos at = player.blockPosition();
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ());
            if (top > y + 4 && level.getBrightness(LightLayer.SKY, at) <= 2) want = Coords.layerForY(y);
        }
        if (want != s.wantLayer) {
            s.wantLayer = want;
            s.wantLayerFor = 0;
        } else if (want != s.layer && ++s.wantLayerFor >= (s.layer == 0 ? 30 : 10)) {
            s.layer = want;
            if (want != 0) queueAround(s, player.chunkPosition(), 12);
        }
    }

    // Everything loaded round you gets looked at again: after the map's been cleared there's no waiting for the slow sweep.
    public static void lookAgain() {
        Minecraft mc = Minecraft.getInstance();
        if (session != null && mc.player != null) queueAround(session, mc.player.chunkPosition(), mc.options.getEffectiveRenderDistance() + 1);
    }

    private static void queueAround(Session s, ChunkPos at, int r) {
        for (int z = -r; z <= r; z++) for (int x = -r; x <= r; x++) s.pending.add(ChunkPos.asLong(at.x + x, at.z + z));
    }

    // Blocks change without any notice a client mod can hear, so a slow sweep looks again: near you every second or so, further out every minute or so.
    // A chunk that turns out the same costs almost nothing after the copy.
    private static void rescan(Minecraft mc, Session s, LocalPlayer player) {
        if (Minimap.movingFast() || ++rescanTick % 2 != 0) return; // everything's new while flying anyway
        ChunkPos at = player.chunkPosition();
        int count = NEAR.length / 2;
        nearIndex = (nearIndex + 1) % count;
        s.pending.add(ChunkPos.asLong(at.x + NEAR[nearIndex * 2], at.z + NEAR[nearIndex * 2 + 1]));
        int r = mc.options.getEffectiveRenderDistance();
        if (r != spiralRadius) {
            spiral = FillOrder.spiral(r);
            spiralRadius = r;
            farIndex = 0;
        }
        if (rescanTick % 4 != 0) return;
        farIndex = (farIndex + 1) % (spiral.length / 2);
        s.pending.add(ChunkPos.asLong(at.x + spiral[farIndex * 2], at.z + spiral[farIndex * 2 + 1]));
    }

    // ---- Each frame, before anything is drawn ----

    private static void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        Session s = session;
        if (s == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        long start = System.nanoTime();
        if (!worldShownLogged && mc.screen == null && joinNanos != 0) {
            worldShownLogged = true;
            CharMap.LOG.info("[CharMap] loading screen gone {} ms after joining", (start - joinNanos) / 1_000_000L);
        }
        // What the frame before this one cost, now it's complete.
        if (lastFrameWasPlain) MINIMAP_ONLY.add(thisFrame);
        lastFrameWasPlain = mc.screen == null;
        try {
            s.textures.nextFrame();
            Icons.work(mc.screen instanceof MapScreen || !Minimap.movingFast(), s.worker);
            s.textures.upload(mc.screen instanceof MapScreen || !firstPixelsLogged ? MAP_OPEN_UPLOAD_BUDGET : UPLOAD_BUDGET);
            copyChunks(mc, s);
        } catch (Throwable t) {
            CharMap.once("frame", "Map frame work hit a problem, carrying on", t);
        }
        addFrameTime(System.nanoTime() - start);
        frameMillis += (thisFrame / 1_000_000.0 - frameMillis) * 0.05;
        thisFrame = 0;
    }

    private static boolean lastFrameWasPlain;

    static void addFrameTime(long nanos) {
        thisFrame += nanos;
    }

    private static void copyChunks(Minecraft mc, Session s) {
        if (s.pending.isEmpty() || s.worker.snapshotsWaiting() > 64 || mc.player == null) return;
        long start = System.nanoTime();
        ChunkPos at = mc.player.chunkPosition();
        int r = Math.max(mc.options.getEffectiveRenderDistance() + 1, 2);
        int[] order = spiralFor(r);
        ClientLevel level = mc.level;
        for (int i = 0; i < order.length; i += 2) {
            long key = ChunkPos.asLong(at.x + order[i], at.z + order[i + 1]);
            if (!s.pending.remove(key)) continue;
            LevelChunk chunk = level.getChunkSource().getChunkNow(at.x + order[i], at.z + order[i + 1]);
            if (chunk != null && !chunk.isEmpty()) {
                long before = System.nanoTime();
                copyChunk(s, chunk, Math.max(Math.abs(order[i]), Math.abs(order[i + 1])));
                long took = System.nanoTime() - before;
                copyNanos += took;
                slowestCopy = Math.max(slowestCopy, took);
                snapshotsTaken++;
            }
            if (s.pending.isEmpty()) return;
            if (System.nanoTime() - start > (Minimap.movingFast() ? MOVING_SNAPSHOT_BUDGET : SNAPSHOT_BUDGET)) return;
        }
        s.pending.clear(); // whatever's left is too far away to matter now
    }

    private static int[] loadSpiral = FillOrder.spiral(3);
    private static int loadSpiralRadius = 3;

    private static int[] spiralFor(int r) {
        if (r != loadSpiralRadius) {
            loadSpiral = FillOrder.spiral(r);
            loadSpiralRadius = r;
        }
        return loadSpiral;
    }

    private static void copyChunk(Session s, LevelChunk chunk, int distance) {
        if (s.ceiling) s.worker.add(ChunkSnapshot.slice(chunk, 0, s.ceilingY, 256, false));
        else {
            ChunkSnapshot surface = ChunkSnapshot.surface(chunk);
            s.worker.add(surface);
            // On a server there may be no save file to read later: quietly keep the blocks you've seen, on the 3D view's own thread, with the copy that was made anyway.
            if (MapConfig.keepBuildings && !isHost() && distance <= 16) s.voxels.capture(surface);
        }
        if (s.layer != 0 && distance <= 12) s.worker.add(ChunkSnapshot.slice(chunk, s.layer, Coords.layerTopY(s.layer), 40, true));
    }

    // ---- Waypoints ----

    private static void loadWaypoints(String world, Path folder) {
        waypointsWorld = world;
        WAYPOINTS.clear();
        Path file = folder.resolve("waypoints.txt");
        try {
            if (Files.exists(file)) WAYPOINTS.addAll(Waypoint.parseAll(Files.readAllLines(file)));
        } catch (IOException | RuntimeException e) {
            CharMap.once("waypoints-read", "Couldn't read the waypoints file", e);
        }
    }

    public static void saveWaypoints() {
        Session s = session;
        if (s == null) return;
        List<String> lines = waypointLines();
        Path file = s.worldFolder.resolve("waypoints.txt");
        s.worker.run(() -> writeLines(file, lines));
    }

    private static void saveWaypointsNow() {
        // session is already cleared on the way out, so the folder comes from what was loaded
        if (waypointsWorld == null) return;
        writeLines(FMLPaths.GAMEDIR.get().resolve("charmap").resolve(waypointsWorld).resolve("waypoints.txt"), waypointLines());
    }

    private static List<String> waypointLines() {
        List<String> lines = new ArrayList<>();
        lines.add("# name, x, y, z, dimension, colour, shown, death");
        for (Waypoint w : WAYPOINTS) lines.add(w.toLine());
        return lines;
    }

    private static void writeLines(Path file, List<String> lines) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines);
        } catch (IOException | RuntimeException e) {
            CharMap.once("waypoints-write", "Couldn't save the waypoints file", e);
        }
    }
}
