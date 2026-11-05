package com.example.charmap.client;

import com.example.charmap.CharMap;
import com.example.charmap.model.ChunkSummary;
import com.example.charmap.model.Coords;
import com.example.charmap.model.FillOrder;
import com.example.charmap.model.McaFile;
import com.example.charmap.model.Nbt;
import com.example.charmap.net.Net;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

// Fills the map from chunks that are already generated, without loading any of them through the game: on your own world it reads the save files directly,
// nearest first, on its own thread. On a server it can ask, if the server has this mod and allows it.
public final class Filler {
    private Filler() {}

    private static volatile boolean running, stopAsked;
    private static Thread thread;

    // Shown on the map
    public static volatile int regionsDone, regionsTotal, chunksDone;
    public static volatile long startedAt;
    public static volatile String status = "";
    public static volatile boolean fromServer;
    private static volatile long serverLast;
    private static long readNanos, waitNanos;

    private static int lastRegionX = Integer.MAX_VALUE, lastRegionZ;
    private static long nextAuto;

    public static boolean active() {
        return running || (fromServer && System.currentTimeMillis() - serverLast < 15_000);
    }

    public static double speed() {
        long ms = System.currentTimeMillis() - startedAt;
        return ms <= 0 ? 0 : chunksDone * 1000.0 / ms;
    }

    static void onSession(MapClient.Session s) {
        lastRegionX = Integer.MAX_VALUE;
        nextAuto = System.currentTimeMillis() + 2000;
        fromServer = false;
    }

    // Automatic fill round where you are, on your own world only: when you first join, then again when you've gone somewhere new.
    static void tick(MapClient.Session s, LocalPlayer player) {
        if (!MapConfig.fillEnabled || running || !MapClient.isHost()) return;
        long now = System.currentTimeMillis();
        if (now < nextAuto) return;
        int rx = Coords.region(player.getBlockX()), rz = Coords.region(player.getBlockZ());
        if (rx == lastRegionX && rz == lastRegionZ && now < nextAuto + 300_000) return;
        lastRegionX = rx;
        lastRegionZ = rz;
        nextAuto = now + 5000;
        start(s, player, MapConfig.fillRadius, false);
    }

    public static boolean fillNow(boolean whole) {
        Minecraft mc = Minecraft.getInstance();
        MapClient.Session s = MapClient.session;
        if (s == null || mc.player == null) return false;
        if (MapClient.isHost()) {
            stop();
            Thread old = thread;
            try {
                if (old != null) old.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return start(s, mc.player, whole ? 0 : MapConfig.fillRadius, true);
        }
        if (mc.getConnection() != null && Net.CHANNEL.isRemotePresent(mc.getConnection().getConnection())) {
            fromServer = true;
            startedAt = System.currentTimeMillis();
            serverLast = startedAt;
            chunksDone = 0;
            regionsDone = 0;
            regionsTotal = 0;
            status = "Asking the server";
            Net.CHANNEL.sendToServer(new Net.FillRequest(false, lastServerFill(s)));
            return true;
        }
        say("This server doesn't have Char's Map, so the map only fills from what you've seen here.");
        return false;
    }

    public static void stop() {
        stopAsked = true;
        Thread t = thread;
        if (t != null) t.interrupt();
        if (fromServer) {
            fromServer = false;
            try {
                if (Minecraft.getInstance().getConnection() != null) Net.CHANNEL.sendToServer(new Net.FillRequest(true, 0));
            } catch (Throwable e) {
                CharMap.once("fill-stop", "Couldn't tell the server to stop the fill", e);
            }
        }
    }

    private static boolean start(MapClient.Session s, LocalPlayer player, int radius, boolean loud) {
        if (running) return false;
        Path dir;
        try {
            var server = Minecraft.getInstance().getSingleplayerServer();
            dir = DimensionType.getStorageFolder(player.level().dimension(), server.getWorldPath(LevelResource.ROOT)).resolve("region");
        } catch (Throwable t) {
            CharMap.once("fill-path", "Couldn't find this world's save files", t);
            return false;
        }
        int bx = player.getBlockX(), bz = player.getBlockZ();
        int ceiling = s.ceiling ? s.ceilingY : Integer.MIN_VALUE;
        stopAsked = false;
        running = true;
        startedAt = System.currentTimeMillis();
        chunksDone = 0;
        regionsDone = 0;
        regionsTotal = 0;
        status = "Filling the map";
        thread = new Thread(() -> {
            try {
                fill(s.worker, dir, bx, bz, radius, ceiling, loud);
            } catch (InterruptedException e) {
                // asked to stop
            } catch (Throwable t) {
                CharMap.once("fill", "The fill stopped on a problem, the map carries on", t);
            } finally {
                running = false;
                thread = null;
            }
        }, "CharMap fill");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
        return true;
    }

    private static void fill(MapWorker worker, Path dir, int bx, int bz, int radius, int ceiling, boolean loud) throws IOException, InterruptedException {
        if (!Files.isDirectory(dir)) return;
        List<FillOrder.Region> found = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "r.*.mca")) {
            for (Path p : files) {
                FillOrder.Region r = FillOrder.parse(p.getFileName().toString());
                if (r != null) found.add(r);
            }
        }
        List<FillOrder.Region> regions = FillOrder.sorted(found, bx, bz, radius);
        regionsTotal = regions.size();
        readNanos = 0;
        waitNanos = 0;
        for (FillOrder.Region r : regions) {
            if (stopAsked) break;
            int[] stamps = null;
            try {
                stamps = worker.stampsOf(r.x(), r.z()).get(20, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
                CharMap.once("fill-stamps", "Took too long to look at a tile, filling it anyway", e);
            }
            fillRegion(worker, dir.resolve(r.file()), r, stamps, bx, bz, ceiling);
            regionsDone++;
        }
        if (loud || chunksDone > 0) {
            long ms = Math.max(1, System.currentTimeMillis() - startedAt);
            CharMap.LOG.info("[CharMap] fill finished: {} regions, {} new chunks in {} ms ({} chunks a second; {} ms reading, {} ms waiting for the map or easing off)", regionsDone, chunksDone, ms, chunksDone * 1000L / ms, readNanos / 1_000_000L, waitNanos / 1_000_000L);
        }
    }

    private static void fillRegion(MapWorker worker, Path file, FillOrder.Region r, int[] stamps, int bx, int bz, int ceiling) throws InterruptedException {
        McaFile mca;
        try {
            mca = McaFile.read(file);
        } catch (IOException | RuntimeException e) {
            CharMap.once("fill-read", "Couldn't read a region file: " + file, e);
            return;
        }
        for (int index : FillOrder.chunks(r.x(), r.z(), bx, bz)) {
            if (stopAsked) return;
            if (!mca.has(index)) continue;
            int stamp = mca.timestamp(index);
            if (stamps != null && stamps[index] != 0 && stamp <= stamps[index]) continue; // nothing new since the map last had it
            try {
                long t0 = System.nanoTime();
                byte[] data = mca.chunk(index);
                if (data == null) continue;
                Map<String, Object> root = Nbt.read(data);
                ChunkSummary s = ChunkSummary.from(root, ceiling);
                if (s == null) continue;
                s.stamp = stamp == 0 ? 1 : stamp;
                long t1 = System.nanoTime();
                readNanos += t1 - t0;
                while (worker.fillsWaiting() > 48 && !stopAsked) Thread.sleep(1);
                worker.addFill(s);
                chunksDone++;
                // The game comes first: when frames drop, ease right off.
                int fps = Minecraft.getInstance().getFps();
                if (fps < 20) Thread.sleep(12);
                else if (fps < 40) Thread.sleep(3);
                waitNanos += System.nanoTime() - t1;
            } catch (IOException | RuntimeException e) {
                CharMap.once("fill-chunk", "Skipped a chunk that couldn't be read, it may be mid-save", e);
            }
        }
    }

    private static long lastServerFill(MapClient.Session s) {
        try {
            Path file = s.worldFolder.resolve(Coords.safeName(s.dimension.replace(':', '_'))).resolve("server_fill.txt");
            return Files.exists(file) ? Long.parseLong(Files.readString(file).trim()) : 0;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private static void rememberServerFill(MapClient.Session s, long when) {
        Path file = s.worldFolder.resolve(Coords.safeName(s.dimension.replace(':', '_'))).resolve("server_fill.txt");
        s.worker.run(() -> {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, Long.toString(when));
            } catch (IOException | RuntimeException e) {
                CharMap.once("fill-remember", "Couldn't note the time of the last server fill", e);
            }
        });
    }

    // ---- From the server (network thread) ----

    public static void onServerChunks(List<ChunkSummary> chunks, int done, int total) {
        MapClient.Session s = MapClient.session;
        if (s == null || !fromServer) return;
        serverLast = System.currentTimeMillis();
        regionsDone = done;
        regionsTotal = total;
        status = "Filling from the server";
        for (ChunkSummary c : chunks) {
            s.worker.addFill(c);
            chunksDone++;
        }
    }

    public static void onServerReply(int code, int total, long finishedAt) {
        MapClient.Session s = MapClient.session;
        switch (code) {
            case Net.REPLY_REFUSED:
                fromServer = false;
                say("This server only lets ops fill the map from its saves.");
                break;
            case Net.REPLY_STARTED:
                regionsTotal = total;
                say("Filling the map from the server, " + total + " regions.");
                break;
            case Net.REPLY_DONE:
                fromServer = false;
                if (s != null) rememberServerFill(s, finishedAt);
                say("The map has everything the server has generated here.");
                break;
            default:
                break;
        }
    }

    private static void say(String text) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.displayClientMessage(Component.literal(text), false);
        });
    }
}
