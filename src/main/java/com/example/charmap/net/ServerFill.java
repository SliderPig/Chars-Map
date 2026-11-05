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

// The optional server half. Reads the server's save files on its own low-priority thread and sends small summaries, never faster than the set speed.
// It shows the map of parts nobody has walked, so only ops can ask unless config/charmap-server.properties says allowEveryone=true.
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
                p.setProperty("allowEveryone", "false");
                p.setProperty("kilobytesPerSecond", "128");
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
        boolean everyone = "true".equalsIgnoreCase(config.getProperty("allowEveryone", "false").trim());
        if (!everyone && !player.hasPermissions(2)) {
            send(player, new Net.FillReply(Net.REPLY_REFUSED, 0, 0));
            return;
        }
        int rate;
        try {
            rate = Math.max(8, Integer.parseInt(config.getProperty("kilobytesPerSecond", "128").trim()));
        } catch (NumberFormatException e) {
            rate = 128;
        }
        ServerLevel level = player.serverLevel();
        MinecraftServer server = player.getServer();
        Path dir = DimensionType.getStorageFolder(level.dimension(), server.getWorldPath(LevelResource.ROOT)).resolve("region");
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
