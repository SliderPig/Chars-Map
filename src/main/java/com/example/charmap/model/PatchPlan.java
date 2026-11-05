package com.example.charmap.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Which squares of the world the 3D view needs, and how detailed each one should be. Near the camera each column is one block wide, then 2, 4, 8 and so on.
public final class PatchPlan {
    private PatchPlan() {}

    public static final int CELLS = 64;
    public static final int COARSEST = 7;
    private static final double SPLIT = 1.5; // a patch is drawn at this many of its own widths from the camera or further
    private static final int MOST_PATCHES = 320;

    public interface Visible {
        boolean box(double minX, double minZ, double maxX, double maxZ);
    }

    public static int blocks(int level) {
        return CELLS << level;
    }

    public static long ancestor(long key, int up) {
        int level = Coords.keyLevel(key), to = Math.min(COARSEST, level + up), by = to - level;
        return Coords.key(Coords.keyLayer(key), to, Coords.keyX(key) >> by, Coords.keyZ(key) >> by);
    }

    // Patch keys are ordinary tile keys: layer, level, x, z, except that the patch is CELLS columns across.
    public static List<Long> select(double eyeX, double eyeY, double eyeZ, double targetX, double targetZ, double far, Visible visible, int layer) {
        List<Long> out = new ArrayList<>();
        int size = blocks(COARSEST);
        int x0 = (int) Math.floor((targetX - far) / size), x1 = (int) Math.floor((targetX + far) / size);
        int z0 = (int) Math.floor((targetZ - far) / size), z1 = (int) Math.floor((targetZ + far) / size);
        // Keep it sane however far out the camera is.
        if ((x1 - x0 + 1) * (long) (z1 - z0 + 1) > 400) {
            int cx = (int) Math.floor(targetX / size), cz = (int) Math.floor(targetZ / size);
            x0 = cx - 9;
            x1 = cx + 9;
            z0 = cz - 9;
            z1 = cz + 9;
        }
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) visit(COARSEST, x, z, eyeX, eyeY, eyeZ, visible, layer, out);
        }
        // Coarse ones first, and nearest first within that: the whole view appears at once, then sharpens.
        out.sort(Comparator.<Long>comparingInt(k -> -Coords.keyLevel(k)).thenComparingDouble(k -> distance(k, eyeX, eyeZ)));
        return out;
    }

    private static double distance(long key, double eyeX, double eyeZ) {
        int size = blocks(Coords.keyLevel(key));
        double cx = (Coords.keyX(key) + 0.5) * size - eyeX, cz = (Coords.keyZ(key) + 0.5) * size - eyeZ;
        return cx * cx + cz * cz;
    }

    private static void visit(int level, int x, int z, double eyeX, double eyeY, double eyeZ, Visible visible, int layer, List<Long> out) {
        int size = blocks(level);
        double minX = x * (double) size, minZ = z * (double) size, maxX = minX + size, maxZ = minZ + size;
        if (!visible.box(minX, minZ, maxX, maxZ)) return;
        double dx = Math.max(0, Math.max(minX - eyeX, eyeX - maxX)), dz = Math.max(0, Math.max(minZ - eyeZ, eyeZ - maxZ));
        // Ground is taken as somewhere about sea level for this: a mountain top being a little coarser than it could be doesn't matter.
        double dy = Math.max(0, eyeY - 80);
        double near = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (level > 0 && near < size * SPLIT && out.size() < MOST_PATCHES) {
            for (int j = 0; j < 2; j++) {
                for (int i = 0; i < 2; i++) visit(level - 1, x * 2 + i, z * 2 + j, eyeX, eyeY, eyeZ, visible, layer, out);
            }
            return;
        }
        out.add(Coords.key(layer, level, x, z));
    }
}
