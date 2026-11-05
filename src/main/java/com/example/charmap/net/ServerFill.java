package com.example.charmap.net;

import com.example.charmap.CharMap;
import com.example.charmap.model.ChunkSummary;
import com.example.charmap.model.FillOrder;
import com.example.charmap.model.McaFile;
import com.example.charmap.model.Nbt;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.PacketDistributor;

// The server half: the same jar on a server sends players with the mod a summary of every chunk that's been generated, so their map fills in by itself.
// It reads the save files on its own low-priority thread and never touches the game's chunks, so the server's main thread doesn't notice. It never sends faster than the set speed.
// config/charmap-server.properties: enabled (who can have it at all), opsOnly (only ops), kilobytesPerSecond (per player), playersAtOnce.
public final class ServerFill {
    private ServerFill() {}

    private static final Map<UUID, Job> JOBS = new ConcurrentHashMap<>();

    private static final class Job {
        volatile boolean stop;
    }

    private static Properties config() {
        Properties p = new Properties();
        Path file = FMLPaths.CONFIGDIR.get().resolve("charmap-server.properties");
        try {
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file)) {
                    p.load(r);
                }
            } else {
                p.setProperty("enabled", "true");
                p.setProperty("opsOnly", "false");
                p.setProperty("kilobytesPerSecond", "128");
                p.setProperty("playersAtOnce", "3");
                try (var w = Files.newBufferedWriter(file)) {
                    p.store(w, "Char's Map: who can fill their map from this server's saves, and how fast it sends");
                }
            }
        } catch (IOException | RuntimeException e) {
            CharMap.once("server-config", "Couldn't read charmap-server.properties", e);
        }
        return p;
    }

    static void request(ServerPlayer player, boolean stop, long since) {
        Job running = JOBS.remove(player.getUUID());
        if (running != null) running.stop = true;
        if (stop) return;
        Properties config = config();
        boolean enabled = !"false".equalsIgnoreCase(config.getProperty("enabled", "true").trim());
        boolean opsOnly = "true".equalsIgnoreCase(config.getProperty("opsOnly", "false").trim());
        if (!enabled || (opsOnly && !player.hasPermissions(2))) {
            send(player, new Net.FillReply(Net.REPLY_REFUSED, 0, 0));
            return;
        }
        if (JOBS.size() >= number(config, "playersAtOnce", 3, 1)) {
            send(player, new Net.FillReply(Net.REPLY_BUSY, 0, 0));
            return;
        }
        int rate = number(config, "kilobytesPerSecond", 128, 8);
        ServerLevel level = player.serverLevel();
        MinecraftServer server = player.getServer();
        Path dir = regionFolder(level, server);
        int bx = player.getBlockX(), bz = player.getBlockZ();
        int ceiling = level.dimensionType().hasCeiling() ? Math.min(level.dimensionType().logicalHeight() + level.getMinBuildHeight(), level.getMaxBuildHeight()) - 1 : Integer.MIN_VALUE;
        Job job = new Job();
        JOBS.put(player.getUUID(), job);
        int bytesPerSecond = rate * 1024;
        Thread t = new Thread(() -> {
            try {
                run(job, player, server, dir, bx, bz, ceiling, since, bytesPerSecond);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                CharMap.once("server-fill", "A map fill for a player stopped on a problem", e);
            } finally {
                JOBS.remove(player.getUUID(), job);
            }
        }, "CharMap server fill");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static int number(Properties config, String key, int fallback, int least) {
        try {
            return Math.max(least, Integer.parseInt(config.getProperty(key, "" + fallback).trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // The normal place, or the separate world folders Bukkit-style servers (Arclight can) keep the Nether and End in.
    static Path regionFolder(ServerLevel level, MinecraftServer server) {
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path normal = DimensionType.getStorageFolder(level.dimension(), root).resolve("region");
        if (Files.isDirectory(normal)) return normal;
        Path parent = root.getParent();
        if (parent != null && root.getFileName() != null) {
            String name = root.getFileName().toString(), dim = level.dimension().location().getPath();
            for (String suffix : new String[] {"_" + dim, dim.equals("the_nether") ? "_nether" : "_" + dim}) {
                Path side = parent.resolve(name + suffix);
                Path other = DimensionType.getStorageFolder(level.dimension(), side).resolve("region");
                if (Files.isDirectory(other)) return other;
                if (Files.isDirectory(side.resolve("region"))) return side.resolve("region");
            }
        }
        return normal;
    }

    private static void send(ServerPlayer player, Object message) {
        Net.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    private static void run(Job job, ServerPlayer player, MinecraftServer server, Path dir, int bx, int bz, int ceiling, long since, int bytesPerSecond) throws IOException, InterruptedException {
        List<FillOrder.Region> found = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "r.*.mca")) {
                for (Path p : files) {
                    FillOrder.Region r = FillOrder.parse(p.getFileName().toString());
                    if (r != null) found.add(r);
                }
            }
        }
        List<FillOrder.Region> regions = FillOrder.sorted(found, bx, bz, 0);
        int total = regions.size();
        long startedAt = System.currentTimeMillis() / 1000;
        server.execute(() -> send(player, new Net.FillReply(Net.REPLY_STARTED, total, 0)));
        int done = 0;
        List<ChunkSummary> batch = new ArrayList<>();
        for (FillOrder.Region r : regions) {
            if (job.stop || player.hasDisconnected()) return;
            McaFile mca;
            try {
                mca = McaFile.read(dir.resolve(r.file()));
            } catch (IOException | RuntimeException e) {
                CharMap.once("server-fill-read", "Couldn't read a region file: " + r.file(), e);
                done++;
                continue;
            }
            for (int index : FillOrder.chunks(r.x(), r.z(), bx, bz)) {
                if (job.stop || player.hasDisconnected()) return;
                if (!mca.has(index)) continue;
                int stamp = mca.timestamp(index);
                if (stamp <= since) continue;
                try {
                    byte[] data = mca.chunk(index);
                    if (data == null) continue;
                    ChunkSummary s = ChunkSummary.from(Nbt.read(data), ceiling);
                    if (s == null) continue;
                    s.stamp = stamp == 0 ? 1 : stamp;
                    batch.add(s);
                } catch (IOException | RuntimeException e) {
                    continue; // caught mid-save: it'll come on the next fill
                }
                if (batch.size() >= 32) {
                    flush(batch, player, server, done, total, bytesPerSecond);
                }
            }
            done++;
        }
        if (!batch.isEmpty()) flush(batch, player, server, done, total, bytesPerSecond);
        server.execute(() -> send(player, new Net.FillReply(Net.REPLY_DONE, total, startedAt)));
    }

    private static void flush(List<ChunkSummary> batch, ServerPlayer player, MinecraftServer server, int done, int total, int bytesPerSecond) throws IOException, InterruptedException {
        byte[] packed = ChunkSummary.packAll(new ArrayList<>(batch));
        batch.clear();
        int d = done;
        server.execute(() -> send(player, new Net.FillChunks(packed, d, total)));
        Thread.sleep(Math.max(20, packed.length * 1000L / bytesPerSecond));
    }
}
