package com.example.charmap.net;

import com.example.charmap.CharMap;
import com.example.charmap.model.ChunkSummary;
import com.example.charmap.model.Coords;
import com.example.charmap.model.FillOrder;
import com.example.charmap.model.McaFile;
import com.example.charmap.model.Nbt;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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
                p.setProperty("blocksReach", "2048");
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
        String dimension = level.dimension().location().toString();
        int bx = player.getBlockX(), bz = player.getBlockZ();
        int ceiling = level.dimensionType().hasCeiling() ? Math.min(level.dimensionType().logicalHeight() + level.getMinBuildHeight(), level.getMaxBuildHeight()) - 1 : Integer.MIN_VALUE;
        Job job = new Job();
        JOBS.put(player.getUUID(), job);
        int bytesPerSecond = rate * 1024;
        Thread t = new Thread(() -> {
            try {
                run(job, player, server, dir, dimension, bx, bz, ceiling, since, bytesPerSecond);
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

    // ---- Real blocks for the 3D view: only ever chunks a player's camera asks about, near them, a little at a time ----

    private static final Map<UUID, java.util.concurrent.LinkedBlockingQueue<Long>> VOXEL_QUEUES = new ConcurrentHashMap<>();

    static void voxelAsk(ServerPlayer player, long[] chunks) {
        Properties config = config();
        boolean enabled = !"false".equalsIgnoreCase(config.getProperty("enabled", "true").trim());
        boolean opsOnly = "true".equalsIgnoreCase(config.getProperty("opsOnly", "false").trim());
        if (!enabled || (opsOnly && !player.hasPermissions(2))) return;
        int rate = number(config, "kilobytesPerSecond", 128, 8) * 1024 * 2;
        int reach = number(config, "blocksReach", 2048, 64) >> 4;
        int px = player.getBlockX() >> 4, pz = player.getBlockZ() >> 4;
        boolean[] fresh = {false};
        java.util.concurrent.LinkedBlockingQueue<Long> queue = VOXEL_QUEUES.computeIfAbsent(player.getUUID(), id -> {
            fresh[0] = true;
            return new java.util.concurrent.LinkedBlockingQueue<>();
        });
        for (long c : chunks) {
            if (queue.size() >= 512) break;
            int cx = net.minecraft.world.level.ChunkPos.getX(c), cz = net.minecraft.world.level.ChunkPos.getZ(c);
            if (Math.max(Math.abs(cx - px), Math.abs(cz - pz)) <= reach) queue.add(c);
        }
        if (!fresh[0]) return;
        ServerLevel level = player.serverLevel();
        MinecraftServer server = player.getServer();
        Path dir = regionFolder(level, server);
        String dimension = level.dimension().location().toString();
        Thread t = new Thread(() -> {
            try {
                voxelLoop(player, server, dir, dimension, queue, rate);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                CharMap.once("server-voxels", "Sending blocks to a player's 3D view stopped on a problem", e);
            } finally {
                VOXEL_QUEUES.remove(player.getUUID(), queue);
            }
        }, "CharMap server blocks");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void voxelLoop(ServerPlayer player, MinecraftServer server, Path dir, String dimension, java.util.concurrent.LinkedBlockingQueue<Long> queue, int bytesPerSecond) throws IOException, InterruptedException {
        McaFile mca = null;
        long mcaRegion = Long.MIN_VALUE;
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
        int count = 0;
        while (!player.hasDisconnected() && player.level().dimension().location().toString().equals(dimension)) {
            Long c = queue.poll(20, java.util.concurrent.TimeUnit.SECONDS);
            if (c == null) return; // nothing asked for a while: this thread goes away, the next ask makes a new one
            int cx = net.minecraft.world.level.ChunkPos.getX(c), cz = net.minecraft.world.level.ChunkPos.getZ(c);
            int rx = Coords.regionOfChunk(cx), rz = Coords.regionOfChunk(cz);
            long region = net.minecraft.world.level.ChunkPos.asLong(rx, rz);
            if (region != mcaRegion) {
                mcaRegion = region;
                Path file = dir.resolve("r." + rx + "." + rz + ".mca");
                mca = Files.exists(file) ? McaFile.read(file) : null;
            }
            com.example.charmap.model.VoxelChunk v = null;
            try {
                byte[] data = mca == null ? null : mca.chunk(Coords.chunkIndex(cx, cz));
                if (data != null) v = com.example.charmap.model.VoxelChunk.fromNbt(Nbt.read(data));
            } catch (IOException | RuntimeException e) {
                v = null; // caught mid-save
            }
            if (v == null) { // nothing there: an empty answer, so the client doesn't wait for it
                v = new com.example.charmap.model.VoxelChunk();
                v.chunkX = cx;
                v.chunkZ = cz;
                v.palette = new String[] {"minecraft:air"};
                v.cells = new short[0];
                v.biomes = new String[] {"minecraft:plains"};
            }
            byte[] one = v.encode();
            out.writeInt(one.length);
            out.write(one);
            count++;
            if (count >= 8 || queue.isEmpty()) {
                out.flush();
                java.io.ByteArrayOutputStream packet = new java.io.ByteArrayOutputStream();
                java.io.DataOutputStream head = new java.io.DataOutputStream(packet);
                head.writeShort(count);
                bytes.writeTo(head);
                head.flush();
                byte[] blob = packet.toByteArray();
                server.execute(() -> send(player, new Net.VoxelSend(blob, dimension)));
                bytes.reset();
                count = 0;
                Thread.sleep(Math.max(10, blob.length * 1000L / bytesPerSecond));
            }
        }
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

    private static void run(Job job, ServerPlayer player, MinecraftServer server, Path dir, String dimension, int bx, int bz, int ceiling, long since, int bytesPerSecond) throws IOException, InterruptedException {
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
        Path progressFile = server.getWorldPath(LevelResource.ROOT).resolve("charmap-progress").resolve(player.getUUID() + "_" + Coords.safeName(dimension.replace(':', '_')) + ".txt");
        // "since" 0 means the player's map has nothing: everything goes again.
        Map<String, Integer> progress = since <= 0 ? new HashMap<>() : loadProgress(progressFile);
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
            int sentUpTo = progress.getOrDefault(r.file(), 0);
            int nowStamp = (int) (System.currentTimeMillis() / 1000);
            for (int index : FillOrder.chunks(r.x(), r.z(), bx, bz)) {
                if (job.stop || player.hasDisconnected()) return;
                if (!mca.has(index)) continue;
                int stamp = mca.timestamp(index);
                if (stamp <= sentUpTo) continue;
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
                    flush(batch, player, server, dimension, done, total, bytesPerSecond);
                }
            }
            done++;
            if (!batch.isEmpty()) flush(batch, player, server, dimension, done, total, bytesPerSecond);
            progress.put(r.file(), nowStamp);
            saveProgress(progressFile, progress);
        }
        server.execute(() -> send(player, new Net.FillReply(Net.REPLY_DONE, total, startedAt)));
    }

    private static Map<String, Integer> loadProgress(Path file) {
        Map<String, Integer> out = new HashMap<>();
        try {
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file)) {
                    int gap = line.indexOf(' ');
                    if (gap > 0) out.put(line.substring(0, gap), Integer.parseInt(line.substring(gap + 1).trim()));
                }
            }
        } catch (IOException | RuntimeException e) {
            CharMap.once("server-progress-read", "Couldn't read how far a map fill got, starting again", e);
        }
        return out;
    }

    private static void saveProgress(Path file, Map<String, Integer> progress) {
        try {
            Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, Integer> e : progress.entrySet()) lines.add(e.getKey() + " " + e.getValue());
            Files.write(file, lines);
        } catch (IOException | RuntimeException e) {
            CharMap.once("server-progress-write", "Couldn't note how far a map fill got", e);
        }
    }

    private static void flush(List<ChunkSummary> batch, ServerPlayer player, MinecraftServer server, String dimension, int done, int total, int bytesPerSecond) throws IOException, InterruptedException {
        byte[] packed = ChunkSummary.packAll(new ArrayList<>(batch));
        batch.clear();
        int d = done;
        server.execute(() -> send(player, new Net.FillChunks(packed, d, total, dimension)));
        Thread.sleep(Math.max(20, packed.length * 1000L / bytesPerSecond));
    }
}
