package com.example.charmap.smoke;

import com.example.charmap.CharMap;
import com.example.charmap.client.Filler;
import com.example.charmap.client.MapClient;
import com.example.charmap.client.MapConfig;
import com.example.charmap.client.MapScreen;
import com.example.charmap.client.Minimap;
import com.example.charmap.client.SettingsScreen;
import com.example.charmap.client.Teammates;
import com.example.charmap.client.Textures;
import com.example.charmap.client.WaypointListScreen;
import com.example.charmap.client.WaypointScreen;
import com.example.charmap.model.Waypoint;
import com.mojang.datafixers.util.Pair;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// A walk through the real game: makes a world, moves around, takes screenshots of everything you can see, measures, quits.
// Only in jars built with -Psmoke. tools/run_real_client.py starts it.
@Mod.EventBusSubscriber(modid = CharMap.MODID, value = Dist.CLIENT)
public final class ClientChecks {
    private ClientChecks() {}

    private static final boolean ON = Boolean.getBoolean("charmap.smokeclient");
    private static final boolean REOPEN = Boolean.getBoolean("charmap.reopen");
    private static final String FPS_ONLY = System.getProperty("charmap.fpsonly", "");
    private static final String JOIN = System.getProperty("charmap.join", "");
    private static final boolean VILLAGE = Boolean.getBoolean("charmap.village");

    private record Step(int after, Runnable what) {}

    private static final ArrayDeque<Step> STEPS = new ArrayDeque<>();
    private static boolean titleSeen, created, scripted;
    private static int wait;
    private static long startupNanos;

    private static void later(int ticks, Runnable what) {
        STEPS.add(new Step(ticks, what));
    }

    private static void shot(int ticks, String name) {
        later(ticks, () -> {
            CharMap.LOG.info("[Check] shot {}: {}", name, Minimap.debugLine());
            Screenshot.grab(Minecraft.getInstance().gameDirectory, name + ".png", Minecraft.getInstance().getMainRenderTarget(), c -> {});
        });
    }

    private static void teleport(int ticks, String dimension, double x, double y, double z) {
        later(ticks, () -> {
            var server = Minecraft.getInstance().getSingleplayerServer();
            server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                    "execute in " + dimension + " run tp Dev " + x + " " + y + " " + z));
        });
    }

    // Average frames a second over this many seconds, written to the log.
    private static void measure(int after, String label, int seconds) {
        List<Integer> samples = new ArrayList<>();
        later(after, () -> samples.clear());
        for (int i = 0; i < seconds; i++) later(20, () -> samples.add(Minecraft.getInstance().getFps()));
        later(1, () -> {
            int sum = 0, low = Integer.MAX_VALUE;
            for (int v : samples) {
                sum += v;
                low = Math.min(low, v);
            }
            CharMap.LOG.info("[Check] FPS {}: average {} lowest {} over {} s. Map time {} ms a frame", label, sum / Math.max(1, samples.size()), low, samples.size(), String.format("%.3f", MapClient.frameMillis));
        });
    }

    // Straight-line flight, measuring frame rate and exactly what the corner map cost per frame, with the worst frame.
    private static void fly(String label, float perTick, int seconds) {
        Minecraft mc = Minecraft.getInstance();
        List<Integer> fps = new ArrayList<>();
        later(5, () -> {
            MapClient.MINIMAP_ONLY.reset();
            CharMap.LOG.info("[Check] start: {}", label);
        });
        for (int i = 0; i < seconds * 20; i++) {
            int tick = i;
            later(1, () -> {
                if (mc.player == null) return;
                mc.player.getAbilities().flying = true;
                mc.player.setDeltaMovement(0, 0, 0);
                mc.player.setPos(mc.player.getX() + perTick, 150, mc.player.getZ());
                if (tick % 20 == 19) fps.add(mc.getFps());
            });
        }
        later(1, () -> {
            int sum = 0, low = Integer.MAX_VALUE;
            for (int v : fps) {
                sum += v;
                low = Math.min(low, v);
            }
            CharMap.LOG.info("[Check] {}: FPS average {} lowest {}. Corner map per frame: {}. {}", label, sum / Math.max(1, fps.size()), low, MapClient.MINIMAP_ONLY.summary(), Minimap.debugLine());
        });
    }

    private static void quit(int after) {
        later(after, () -> {
            CharMap.LOG.info("[Check] finished. {}", Minimap.debugLine());
            Minecraft.getInstance().stop();
        });
    }

    private static void noVsync(Minecraft mc) {
        mc.options.enableVsync().set(false);
        mc.getWindow().updateVsync(false);
        mc.options.framerateLimit().set(260);
        mc.getWindow().setFramerateLimit(260);
    }

    private static void scriptFirstRun() {
        Minecraft mc = Minecraft.getInstance();
        // Settle where the world starts, then look
        later(120, () -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                p.setGameMode(GameType.CREATIVE);
                p.getAbilities().flying = true;
                p.onUpdateAbilities();
                server.overworld().setDayTime(6000);
                server.overworld().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "gamerule doMobSpawning true");
            });
        });
        shot(160, "01_minimap_spawn");
        later(5, () -> {
            MapClient.MINIMAP_ONLY.reset();
        });
        later(200, () -> CharMap.LOG.info("[Check] standing still, corner map per frame: {}. {}", MapClient.MINIMAP_ONLY.summary(), Minimap.debugLine()));

        // Turn and turn about, so the world settling down doesn't favour either
        later(100, () -> {});
        for (int round = 1; round <= 1; round++) {
            measure(30, "map ON, standing still, round " + round, 6);
            later(1, () -> MapConfig.dots = MapConfig.infoText = MapConfig.compass = false);
            measure(30, "map only, no text dots compass, round " + round, 6);
            later(1, () -> {
                MapConfig.dots = MapConfig.infoText = MapConfig.compass = true;
                MapConfig.showMinimap = false;
            });
            measure(30, "working but minimap hidden, round " + round, 6);
            later(1, () -> {
                MapConfig.showMinimap = true;
                MapConfig.enabled = false;
            });
            measure(30, "RESTING (same as no map), round " + round, 6);
            later(1, () -> MapConfig.enabled = true);
        }

        // Water
        later(40, () -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerLevel level = server.overworld();
                ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                Pair<BlockPos, Holder<Biome>> found = level.findClosestBiome3d(b -> b.is(Biomes.OCEAN) || b.is(Biomes.DEEP_OCEAN) || b.is(Biomes.WARM_OCEAN) || b.is(Biomes.LUKEWARM_OCEAN), p.blockPosition(), 3000, 32, 64);
                if (found != null) {
                    BlockPos at = found.getFirst();
                    CharMap.LOG.info("[Check] ocean at {}", at);
                    p.teleportTo(level, at.getX() + 0.5, 90, at.getZ() + 0.5, p.getYRot(), p.getXRot());
                }
            });
        });
        shot(200, "02_minimap_water");

        // Walk the world in hops so several regions end up on disk
        later(10, () -> {
            Textures.usePixelBuffer = false;
            CharMap.LOG.info("[Check] uploads without a pixel buffer from here: {}", Minimap.debugLine());
        });
        teleport(1, "minecraft:overworld", 900, 110, 0);
        later(120, () -> CharMap.LOG.info("[Check] uploads without: {}", Minimap.debugLine()));
        teleport(1, "minecraft:overworld", 1600, 110, 700);
        later(120, () -> {
            CharMap.LOG.info("[Check] uploads without: {}", Minimap.debugLine());
            Textures.usePixelBuffer = true;
        });
        teleport(1, "minecraft:overworld", 800, 110, 1400);
        later(120, () -> CharMap.LOG.info("[Check] uploads with: {}", Minimap.debugLine()));
        teleport(1, "minecraft:overworld", -600, 110, 900);
        later(120, () -> CharMap.LOG.info("[Check] uploads with: {}", Minimap.debugLine()));
        shot(5, "03_minimap_after_hops");
        // The first pass generates the ground, so it isn't a fair measure of the map; the turns after it are.
        fly("first pass, new ground being generated, map on", 3f, 10);
        fly("back again, map on", -3f, 10);
        later(1, () -> MapConfig.enabled = false);
        fly("out, map resting", 3f, 10);
        fly("back, map resting", -3f, 10);
        later(1, () -> MapConfig.enabled = true);
        later(100, () -> {
            MapClient.MINIMAP_ONLY.reset();
        });
        later(120, () -> CharMap.LOG.info("[Check] hovering, nothing moving, corner map per frame: {}. {}", MapClient.MINIMAP_ONLY.summary(), Minimap.debugLine()));

        later(10, () -> {
            MapConfig.circle = true;
            MapConfig.rotate = true;
            MapConfig.zoom = 1;
            MapConfig.corner = 3;
        });
        shot(30, "04_minimap_circle_turning");
        later(1, () -> {
            MapConfig.circle = false;
            MapConfig.rotate = false;
            MapConfig.zoom = 2;
            MapConfig.corner = 1;
        });

        later(5, () -> mc.setScreen(new MapScreen()));
        shot(40, "05_world_map");
        later(1, () -> {
            if (mc.screen instanceof MapScreen m && mc.player != null) m.showAt(mc.player.getX(), mc.player.getZ(), 0.25f);
        });
        shot(40, "06_world_map_far_out");

        // The fill: everything so far is saved, then the map is wiped and brought back from the save files only
        later(5, () -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> server.saveEverything(true, true, true));
        });
        later(100, () -> {
            MapConfig.fillEnabled = false;
            Filler.stop();
            MapClient.Session s = MapClient.session;
            s.textures.clearAll();
            s.worker.clearAll(() -> CharMap.LOG.info("[Check] map wiped"));
            MapClient.lookAgain();
        });
        later(20, () -> Filler.fillNow(true));
        shot(8, "07_fill_progress");
        shot(25, "08_fill_progress_later");
        later(1, () -> {
            startupNanos = System.nanoTime();
        });
        for (int i = 0; i < 30; i++) {
            later(20, () -> {
                if (Filler.active() || startupNanos == 0) return;
                CharMap.LOG.info("[Check] fill done: {} chunks, {} regions", Filler.chunksDone, Filler.regionsDone);
                startupNanos = 0;
            });
        }
        shot(10, "09_after_fill");
        later(1, () -> {
            MapConfig.fillEnabled = true;
            mc.setScreen(null);
        });

        // Places and friends
        later(5, () -> {
            if (mc.player == null) return;
            int x = mc.player.getBlockX(), y = mc.player.getBlockY(), z = mc.player.getBlockZ();
            MapClient.WAYPOINTS.add(new Waypoint("Base", x + 30, y - 30, z - 25, Waypoint.OVERWORLD, 0xFF7EB6, false));
            MapClient.WAYPOINTS.add(new Waypoint("Far mine", x + 2000, 20, z + 900, Waypoint.OVERWORLD, 0xF5A524, false));
            MapClient.WAYPOINTS.add(new Waypoint("Death", x - 20, y - 30, z + 30, Waypoint.OVERWORLD, 0xE5484D, true));
            MapClient.WAYPOINTS.add(new Waypoint("Nether door", 100, 70, 100, Waypoint.NETHER, 0x7A7FFF, false));
            MapClient.saveWaypoints();
            Teammates.extra = List.of(
                    new Teammates.Mate(UUID.nameUUIDFromBytes(new byte[] {1}), "MiGhtyRAAin87", mc.player.getX() - 40, y, mc.player.getZ() + 20, "minecraft:overworld", 20, 20),
                    new Teammates.Mate(UUID.nameUUIDFromBytes(new byte[] {2}), "Friend", mc.player.getX() + 900, y, mc.player.getZ() - 400, "minecraft:overworld", 20, 20),
                    new Teammates.Mate(UUID.nameUUIDFromBytes(new byte[] {3}), "Elsewhere", 0, 64, 0, "minecraft:the_nether", 20, 20));
        });
        shot(30, "10_minimap_waypoints_mates");
        later(1, () -> mc.setScreen(new MapScreen()));
        shot(30, "11_world_map_waypoints_mates");
        later(1, () -> mc.setScreen(new SettingsScreen(null)));
        shot(10, "12_settings");
        later(1, () -> mc.setScreen(new WaypointListScreen(null)));
        shot(10, "13_waypoint_list");
        later(1, () -> mc.setScreen(WaypointScreen.newHere(null)));
        shot(10, "14_waypoint_new");
        later(1, () -> {
            mc.setScreen(null);
            Teammates.extra = List.of();
        });

        // The 3D view
        later(1, () -> {
            MapScreen m = new MapScreen();
            mc.setScreen(m);
            m.set3D(true);
            m.view3d().look(m.view3d().targetX, m.view3d().targetY, m.view3d().targetZ, 260);
            m.view3d().pitch = 0.75f;
        });
        shot(60, "20_3d_near");
        measure(1, "3D view near", 5);
        later(1, () -> {
            if (mc.screen instanceof MapScreen m) {
                m.view3d().look(m.view3d().targetX, m.view3d().targetY, m.view3d().targetZ, 1400);
                m.view3d().yaw = 0.8f;
            }
        });
        shot(60, "21_3d_far");
        measure(1, "3D view far", 5);
        later(1, () -> {
            if (mc.screen instanceof MapScreen m) {
                CharMap.LOG.info("[Check] 3D: {} patches drawn, {} MB", m.view3d().drawnLast, m.view3d().memoryBytes() / 1048576);
                m.view3d().look(m.view3d().targetX, m.view3d().targetY, m.view3d().targetZ, 60);
                m.view3d().pitch = 0.4f;
            }
        });
        shot(60, "22_3d_blocks_close");
        later(1, () -> {
            if (mc.screen instanceof MapScreen m) m.set3D(false);
            mc.setScreen(null);
        });

        // Under the ground and in the Nether
        teleport(5, "minecraft:overworld", -600, 12, 900);
        later(5, () -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                BlockPos at = p.blockPosition();
                ServerLevel level = p.serverLevel();
                for (BlockPos q : BlockPos.betweenClosed(at.offset(-8, -1, -8), at.offset(8, 3, 8))) level.setBlock(q, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
            });
        });
        shot(120, "15_cave_view");
        teleport(5, "minecraft:the_nether", 100, 70, 100);
        shot(200, "16_nether");
        teleport(5, "minecraft:overworld", 1600, 110, 700);
        measure(120, "map ON, after the walk", 8);
        quit(40);
    }

    private static void scriptReopen() {
        Minecraft mc = Minecraft.getInstance();
        // As soon as the player exists: what's on the map before any chunk has been coloured?
        later(1, () -> CharMap.LOG.info("[Check] world showing: {}", Minimap.debugLine()));
        shot(1, "r1_right_after_joining");
        later(10, () -> CharMap.LOG.info("[Check] half a second after joining: {}", Minimap.debugLine()));
        later(1, () -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                p.setGameMode(GameType.CREATIVE);
                p.getAbilities().flying = true;
                p.onUpdateAbilities();
            });
        });
        shot(60, "r2_minimap_reopened");
        // Somewhere far away that isn't loaded now: only the saved tiles can show this
        later(1, () -> {
            mc.setScreen(new MapScreen());
            if (mc.screen instanceof MapScreen m) m.showAt(1600, 700, 1f);
        });
        shot(70, "r3_far_map_from_disk");
        later(1, () -> {
            if (mc.screen instanceof MapScreen m && mc.player != null) m.showAt(600, 500, 0.2f);
        });
        shot(70, "r4_far_out_from_disk");
        later(1, () -> mc.setScreen(null));
        measure(40, "map ON, reopened world", 8);
        quit(20);
    }

    private static BlockPos villageAt;
    private static java.util.function.BooleanSupplier waitingFor;
    private static int waitedTicks, waitLimit;

    // Nothing else happens until this is true, or the limit runs out.
    private static void waitUntil(int limitTicks, java.util.function.BooleanSupplier condition) {
        later(1, () -> {
            waitingFor = condition;
            waitedTicks = 0;
            waitLimit = limitTicks;
        });
    }

    // Everything within this many chunks of the player has arrived.
    private static boolean loadedAround(int radius) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;
        int cx = mc.player.getBlockX() >> 4, cz = mc.player.getBlockZ() >> 4;
        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                if (mc.level.getChunkSource().getChunkNow(cx + x, cz + z) == null) return false;
            }
        }
        return true;
    }

    private static void view3D(float yaw, float pitch, float distance) {
        if (Minecraft.getInstance().screen instanceof MapScreen m && villageAt != null) {
            m.view3d().yaw = yaw;
            m.view3d().pitch = pitch;
            m.view3d().look(villageAt.getX(), m.view3d().targetY, villageAt.getZ(), distance);
        }
    }

    // A real village, seen in 3D from several sides.
    private static void scriptVillage() {
        Minecraft mc = Minecraft.getInstance();
        later(60, () -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerLevel level = server.overworld();
                ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                p.setGameMode(GameType.CREATIVE);
                p.getAbilities().flying = true;
                p.onUpdateAbilities();
                level.setDayTime(6000);
                BlockPos found = level.findNearestMapStructure(net.minecraft.tags.StructureTags.VILLAGE, p.blockPosition(), 100, false);
                CharMap.LOG.info("[Check] village at {}", found);
                if (found != null) {
                    villageAt = found;
                    p.teleportTo(level, found.getX() + 0.5, 110, found.getZ() + 0.5, p.getYRot(), 30f);
                }
            });
        });
        later(40, () -> {});
        waitUntil(1500, () -> loadedAround(7));
        later(100, () -> {
            MapScreen m = new MapScreen();
            mc.setScreen(m);
            m.set3D(true);
            view3D(0.6f, 0.62f, 95f);
        });
        waitUntil(600, () -> MapClient.session.voxels.waiting() == 0 && MapClient.session.voxels.meshes > 45);
        shot(100, "30_village_a");
        measure(1, "3D view, village, close", 4);
        later(1, () -> view3D(2.5f, 0.42f, 60f));
        shot(60, "31_village_low");
        later(1, () -> view3D(4.2f, 0.85f, 170f));
        shot(100, "32_village_high");
        measure(1, "3D view, village, further", 4);
        later(1, () -> view3D(1.2f, 0.5f, 420f));
        shot(100, "33_village_far");
        later(1, () -> {
            if (mc.screen instanceof MapScreen m) CharMap.LOG.info("[Check] 3D: {} height patches, {} block patches, {} MB + {} MB, {} real mobs. Meshes {}, average {} ms, chunks read {}", m.view3d().drawnLast, m.view3d().blocksDrawnLast, m.view3d().memoryBytes() / 1048576, m.view3d().blockMemoryBytes() / 1048576, m.view3d().mobsDrawnLast(), MapClient.session.voxels.meshes, MapClient.session.voxels.meshNanos / Math.max(1, MapClient.session.voxels.meshes) / 1000000, MapClient.session.voxels.chunksRead);
        });
        later(1, () -> view3D(5.5f, 0.3f, 40f));
        shot(60, "34_village_street");
        quit(10);
    }

    // On a real server: the map fills by itself from what the server sends.
    private static void scriptJoin() {
        Minecraft mc = Minecraft.getInstance();
        later(60, () -> CharMap.LOG.info("[Check] on the server: server has the mod: {}", mc.getConnection() != null && com.example.charmap.net.Net.CHANNEL.isRemotePresent(mc.getConnection().getConnection())));
        shot(40, "s1_joined_server");
        later(1, () -> {
            MapScreen m = new MapScreen();
            mc.setScreen(m);
            if (mc.player != null) m.showAt(mc.player.getX(), mc.player.getZ(), 0.25f);
        });
        shot(100, "s2_map_filling_from_server");
        for (int i = 0; i < 6; i++) {
            later(100, () -> CharMap.LOG.info("[Check] from the server so far: {} chunks, {} of {} regions, {} chunks a second. {}", Filler.chunksDone, Filler.regionsDone, Filler.regionsTotal, Math.round(Filler.speed()), Minimap.debugLine()));
        }
        shot(1, "s3_map_filled_from_server");
        later(1, () -> mc.setScreen(null));
        later(20, () -> mc.level.disconnect());
        later(40, () -> mc.stop());
    }

    private static void scriptFps() {
        later(400, () -> {});
        measure(40, "only: " + FPS_ONLY, 15);
        quit(10);
    }

    @SubscribeEvent
    public static void onScreen(ScreenEvent.Init.Post e) {
        if (ON && e.getScreen() instanceof TitleScreen) titleSeen = true;
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent e) {
        if (!ON || e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen) mc.setScreen(new TitleScreen());
        if (titleSeen && !created) {
            created = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.renderDistance().set(8);
            mc.options.guiScale().set(2);
            mc.resizeDisplay();
            noVsync(mc);
            if (FPS_ONLY.equals("off")) MapConfig.enabled = false;
            if (!JOIN.isEmpty()) {
                net.minecraft.client.gui.screens.ConnectScreen.startConnecting(mc.screen, mc, net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(JOIN), new net.minecraft.client.multiplayer.ServerData("test", JOIN, false), false);
            } else if (REOPEN || !FPS_ONLY.isEmpty()) {
                mc.createWorldOpenFlows().loadLevel(mc.screen, "smoke");
            } else {
                mc.createWorldOpenFlows().createFreshLevel("smoke", new LevelSettings("smoke", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                        new WorldOptions(20261105L, true, false), access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.NORMAL).value().createWorldDimensions());
            }
        }
        if (mc.player == null || mc.level == null) return;
        if (JOIN.isEmpty() && (mc.getSingleplayerServer() == null || mc.getSingleplayerServer().getPlayerList().getPlayers().isEmpty())) return;
        if (!scripted) {
            scripted = true;
            if (VILLAGE) scriptVillage();
            else if (!JOIN.isEmpty()) scriptJoin();
            else if (!FPS_ONLY.isEmpty()) scriptFps();
            else if (REOPEN) scriptReopen();
            else scriptFirstRun();
            CharMap.LOG.info("[Check] world is up, {} steps to go", STEPS.size());
        }
        if (STEPS.isEmpty()) return;
        if (mc.screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen) return; // nothing counts until the world is showing
        if (waitingFor != null) {
            if (!waitingFor.getAsBoolean() && ++waitedTicks < waitLimit) return;
            CharMap.LOG.info("[Check] waited {} ticks", waitedTicks);
            waitingFor = null;
        }
        if (++wait >= STEPS.peek().after()) {
            wait = 0;
            try {
                STEPS.poll().what().run();
            } catch (Throwable t) {
                CharMap.LOG.error("[Check] step failed", t);
            }
        }
    }
}
