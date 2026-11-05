package com.example.charmap.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ThreeDTest {
    private record Quad(float[] p, int colour) {
        float[] normal() {
            float ax = p[3] - p[0], ay = p[4] - p[1], az = p[5] - p[2], bx = p[6] - p[0], by = p[7] - p[1], bz = p[8] - p[2];
            return new float[] {ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx};
        }
    }

    private static List<Quad> mesh(int n, int cell, int[] colours, short[] heights) {
        List<Quad> out = new ArrayList<>();
        HeightMesh.build(n, cell, 1, colours, heights, (x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, c0, c1, c2, c3) -> out.add(new Quad(new float[] {x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3}, c0)));
        return out;
    }

    private static int[] grid(int n, int value) {
        int[] a = new int[(n + 2) * (n + 2)];
        java.util.Arrays.fill(a, value);
        return a;
    }

    private static short[] heights(int n, int value) {
        short[] a = new short[(n + 2) * (n + 2)];
        java.util.Arrays.fill(a, (short) value);
        return a;
    }

    @Test
    void flatGroundIsTopsWithALittleSkirtRoundTheEdge() {
        List<Quad> q = mesh(4, 1, grid(4, 0xFF336633), heights(4, 64));
        int tops = 0, skirts = 0;
        for (Quad k : q) {
            if (k.normal()[1] > 0) {
                tops++;
                assertEquals(65f, k.p[1]);
            } else {
                skirts++;
                // Only round the outside of the patch, one block deep, to hide gaps to a coarser patch next door
                boolean rim = (k.p[0] == 0 && k.p[6] == 0) || (k.p[0] == 4 && k.p[6] == 4) || (k.p[2] == 0 && k.p[8] == 0) || (k.p[2] == 4 && k.p[8] == 4);
                assertTrue(rim);
                assertEquals(64f, Math.min(k.p[1], k.p[4]));
            }
        }
        assertEquals(16, tops, "tops must face up, or back-face culling would hide them");
        assertEquals(16, skirts);
    }

    @Test
    void aStepGetsAWallFacingOutward() {
        int n = 3;
        short[] h = heights(n, 64);
        h[(1 + 1) * (n + 2) + 1 + 1] = 70; // the middle column is 6 higher
        List<Quad> q = mesh(n, 1, grid(n, 0xFF808080), h);
        int walls = 0;
        for (Quad w : q) {
            float[] normal = w.normal();
            if (normal[1] != 0 || Math.max(w.p[1], w.p[4]) != 71f) continue;
            walls++;
            // Facing away from the middle of the column at x 1.5, z 1.5
            float mx = (w.p[0] + w.p[6]) / 2, mz = (w.p[2] + w.p[8]) / 2;
            assertTrue(normal[0] * (mx - 1.5f) + normal[2] * (mz - 1.5f) > 0);
            assertEquals(65f, Math.min(w.p[1], w.p[4]));
        }
        assertEquals(4, walls);
    }

    @Test
    void unseenColumnsAreLeftOutAndGetAShortWallNextToThem() {
        int n = 2;
        int[] c = grid(n, 0xFF808080);
        short[] h = heights(n, 64);
        int at = (1 + 1) * (n + 2) + 1 + 1; // column 1,1
        c[at] = 0;
        h[at] = Shade.NO_HEIGHT;
        List<Quad> q = mesh(n, 1, c, h);
        long tops = q.stream().filter(k -> k.normal()[1] > 0).count();
        assertEquals(3, tops);
        assertTrue(q.size() > 3);
    }

    @Test
    void widerColumnsCoverTheSamePatch() {
        List<Quad> q = mesh(2, 8, grid(2, 0xFF808080), heights(2, 10));
        float max = 0;
        for (Quad k : q) for (int i = 0; i < 12; i += 3) max = Math.max(max, k.p[i]);
        assertEquals(16f, max);
    }

    @Test
    void patchesGetCoarserWithDistanceAndCoverTheView() {
        List<Long> keys = PatchPlan.select(0, 150, 0, 0, 0, 4096, (a, b, c, d) -> true, 0);
        assertFalse(keys.isEmpty());
        int nearLevel = 99, farLevel = -1;
        for (long k : keys) {
            int size = PatchPlan.blocks(Coords.keyLevel(k));
            double cx = (Coords.keyX(k) + 0.5) * size, cz = (Coords.keyZ(k) + 0.5) * size;
            if (Math.hypot(cx, cz) < 150) nearLevel = Math.min(nearLevel, Coords.keyLevel(k));
            farLevel = Math.max(farLevel, Coords.keyLevel(k));
        }
        assertTrue(nearLevel <= 1);
        assertTrue(farLevel >= 4);
        // Coarse ones come first so the whole view shows at once
        assertEquals(farLevel, Coords.keyLevel(keys.get(0)));
    }

    @Test
    void offScreenPatchesAreSkipped() {
        List<Long> keys = PatchPlan.select(0, 150, 0, 0, 0, 4096, (minX, minZ, maxX, maxZ) -> maxX > 0, 0);
        for (long k : keys) assertTrue(Coords.keyX(k) >= 0);
    }

    @Test
    void ancestorCoversItsChild() {
        long child = Coords.key(0, 1, -3, 5);
        long up = PatchPlan.ancestor(child, 2);
        assertEquals(3, Coords.keyLevel(up));
        assertEquals(-1, Coords.keyX(up));
        assertEquals(1, Coords.keyZ(up));
        assertEquals(PatchPlan.COARSEST, Coords.keyLevel(PatchPlan.ancestor(Coords.key(0, 6, 0, 0), 5)));
    }

    @Test
    void frameStatsReportTheSlowEnd() {
        FrameStats s = new FrameStats();
        for (int i = 0; i < 99; i++) s.add(100_000L);
        s.add(30_000_000L);
        assertEquals(100, s.frames());
        assertEquals(0.15, s.percentileMillis(0.99), 0.001);
        assertEquals(30.0, s.worstMillis(), 0.001);
        assertTrue(s.averageMillis() > 0.3 && s.averageMillis() < 0.5);
        s.reset();
        assertEquals(0, s.frames());
        assertEquals(0, s.worstMillis());
    }

    @Test
    void aTallNeighbourTowardsTheSunCastsAShadow() {
        int size = 13;
        short[] h = new short[size * size];
        java.util.Arrays.fill(h, (short) 64);
        assertFalse(HeightMesh.inShadow(h, size, 6, 6, (short) 64, 1, 6));
        h[5 * size + 5] = 80; // one step towards the sun, 16 higher
        assertTrue(HeightMesh.inShadow(h, size, 6, 6, (short) 64, 1, 6));
        // The other side of it isn't in its shadow
        assertFalse(HeightMesh.inShadow(h, size, 4, 4, (short) 64, 1, 3));
    }

    @Test
    void creasesDarkenTheCornerNextToATallerColumn() {
        int n = 3, size = n + 2;
        short[] h = heights(n, 64);
        h[(1 + 1) * size + 2 + 1] = 66; // column x 2, z 1
        List<int[]> corners = new ArrayList<>();
        HeightMesh.build(n, 1, 1, grid(n, 0xFF808080), h, (x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, c0, c1, c2, c3) -> {
            if (y0 == y1 && x0 == 1 && z0 == 1 && y0 == 65f) corners.add(new int[] {c0, c1, c2, c3});
        });
        assertEquals(1, corners.size());
        int[] c = corners.get(0);
        assertEquals(0xFF808080, c[0]); // far corner stays bright
        assertTrue((c[2] & 255) < 0x80 && (c[3] & 255) < 0x80); // the two touching it go dark
    }

    @Test
    void averageHeightIgnoresUnknown() {
        assertEquals(10, Shade.averageHeight((short) 10, Shade.NO_HEIGHT, Shade.NO_HEIGHT, Shade.NO_HEIGHT));
        assertEquals(15, Shade.averageHeight((short) 10, (short) 20, Shade.NO_HEIGHT, Shade.NO_HEIGHT));
        assertEquals(Shade.NO_HEIGHT, Shade.averageHeight(Shade.NO_HEIGHT, Shade.NO_HEIGHT, Shade.NO_HEIGHT, Shade.NO_HEIGHT));
        assertEquals(-5, Shade.averageHeight((short) -5, (short) -5, (short) -5, (short) -5));
    }
}
