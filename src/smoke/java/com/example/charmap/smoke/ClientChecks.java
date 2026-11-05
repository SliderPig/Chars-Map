package com.example.charmap.smoke;

import com.example.charmap.CharMap;
import com.example.charmap.client.Filler;
import com.example.charmap.client.MapClient;
import com.example.charmap.client.MapConfig;
import com.example.charmap.client.MapScreen;
import com.example.charmap.client.Minimap;
import com.example.charmap.client.SettingsScreen;
import com.example.charmap.client.Teammates;
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

        measure(60, "map ON, standing still, render distance 8", 12);
        later(1, () -> MapConfig.enabled = false);
        measure(40, "map RESTING (same as no map), standing still", 12);
        later(1, () -> MapConfig.enabled = true);

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
        teleport(10, "minecraft:overworld", 900, 110, 0);
        teleport(140, "minecraft:overworld", 1600, 110, 700);
        teleport(140, "minecraft:overworld", 800, 110, 1400);
        teleport(140, "minecraft:overworld", -600, 110, 900);
        shot(120, "03_minimap_after_hops");

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
        shot(30, "r3_far_map_from_disk");
        later(1, () -> {
            if (mc.screen instanceof MapScreen m && mc.player != null) m.showAt(600, 500, 0.2f);
        });
        shot(30, "r4_far_out_from_disk");
        later(1, () -> mc.setScreen(null));
        measure(40, "map ON, reopened world", 8);
        quit(20);
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
            if (REOPEN || !FPS_ONLY.isEmpty()) {
                mc.createWorldOpenFlows().loadLevel(mc.screen, "smoke");
            } else {
                mc.createWorldOpenFlows().createFreshLevel("smoke", new LevelSettings("smoke", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                        new WorldOptions(20261105L, true, false), access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.NORMAL).value().createWorldDimensions());
            }
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || mc.getSingleplayerServer().getPlayerList().getPlayers().isEmpty()) return;
        if (!scripted) {
            scripted = true;
            if (!FPS_ONLY.isEmpty()) scriptFps();
            else if (REOPEN) scriptReopen();
            else scriptFirstRun();
            CharMap.LOG.info("[Check] world is up, {} steps to go", STEPS.size());
        }
        if (STEPS.isEmpty()) return;
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
