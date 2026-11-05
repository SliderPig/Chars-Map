package com.example.charmap.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Fill the map outward from where you stand, nearest first.
public final class FillOrder {
    private FillOrder() {}

    public record Region(int x, int z, String file) {}

    // "r.-1.3.mca" gives a region, anything else gives null.
    public static Region parse(String fileName) {
        if (!fileName.startsWith("r.") || !fileName.endsWith(".mca")) return null;
        String[] parts = fileName.split("\\.");
        if (parts.length != 4) return null;
        try {
            return new Region(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), fileName);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static long regionDistance(Region r, int blockX, int blockZ) {
        long dx = r.x() * 512L + 256 - blockX, dz = r.z() * 512L + 256 - blockZ;
        return dx * dx + dz * dz;
    }

    // radius in regions, 0 or less = the whole world.
    public static List<Region> sorted(List<Region> regions, int blockX, int blockZ, int radius) {
        List<Region> out = new ArrayList<>();
        int rx = Coords.region(blockX), rz = Coords.region(blockZ);
        for (Region r : regions) {
            if (radius <= 0 || Math.max(Math.abs(r.x() - rx), Math.abs(r.z() - rz)) <= radius) out.add(r);
        }
        out.sort(Comparator.comparingLong(r -> regionDistance(r, blockX, blockZ)));
        return out;
    }

    // Order of a region's 1024 chunks, nearest to the block first.
    public static int[] chunks(int regionX, int regionZ, int blockX, int blockZ) {
        Integer[] order = new Integer[1024];
        long[] distance = new long[1024];
        for (int i = 0; i < 1024; i++) {
            order[i] = i;
            long dx = (regionX * 32L + (i & 31)) * 16 + 8 - blockX, dz = (regionZ * 32L + (i >> 5)) * 16 + 8 - blockZ;
            distance[i] = dx * dx + dz * dz;
        }
        java.util.Arrays.sort(order, Comparator.comparingLong(i -> distance[i]));
        int[] out = new int[1024];
        for (int i = 0; i < 1024; i++) out[i] = order[i];
        return out;
    }

    // Square rings round the middle: (0,0), then the 8 round it, and so on out to this radius.
    public static int[] spiral(int radius) {
        int side = radius * 2 + 1;
        int[] out = new int[side * side * 2];
        int o = 0;
        out[o++] = 0;
        out[o++] = 0;
        for (int r = 1; r <= radius; r++) {
            for (int i = -r; i < r; i++) {
                out[o++] = i; out[o++] = -r;
                out[o++] = r; out[o++] = i;
                out[o++] = -i; out[o++] = r;
                out[o++] = -r; out[o++] = -i;
            }
        }
        return out;
    }
}
