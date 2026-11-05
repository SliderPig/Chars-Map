package com.example.charmap.model;

// Turns a square of map pixels into blocks you can look at: a flat top on every column and side walls wherever the neighbour is lower.
// The same code makes every level of detail: further away, each column is just wider.
// The light is baked into the corners: dark in the creases where a column meets a taller neighbour, and a real cast shadow from the sun in the north-west.
public final class HeightMesh {
    private HeightMesh() {}

    public static final int BORDER = 12;  // rows round the patch kept for looking at neighbours and the sun
    private static final float[] CREASE = {1f, 0.84f, 0.72f, 0.6f};
    private static final float SHADOW = 0.66f;
    private static final float SUN_X = 0.78f, SUN_Z = 0.62f, SUN_RISE = 0.95f;  // towards the sun: per column of ground, blocks of height

    public interface Sink {
        void quad(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, int c0, int c1, int c2, int c3);
    }

    // n columns each way, each `cell` blocks wide. colours and heights hold (n+2b) x (n+2b) values: b extra rows all round, belonging to the neighbours.
    // Positions come out relative to the patch's corner. Corners go anticlockwise seen from outside, so back faces can be skipped. Returns the number of quads.
    public static int build(int n, int cell, int b, int[] colours, short[] heights, Sink out) {
        int size = n + 2 * b, quads = 0;
        int skirt = Math.max(4, cell * 2);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int at = (j + b) * size + i + b;
                int colour = colours[at];
                short here = heights[at];
                if ((colour >>> 24) == 0 || here == Shade.NO_HEIGHT) continue;
                float top = here + 1f;
                float x0 = i * cell, x1 = x0 + cell, z0 = j * cell, z1 = z0 + cell;
                float light = inShadow(heights, size, i + b, j + b, here, cell, b) ? SHADOW : 1f;
                int lit = Shade.scale(colour, light);
                short nw = heights[at - size - 1], north = heights[at - size], ne = heights[at - size + 1], west = heights[at - 1], east = heights[at + 1], sw = heights[at + size - 1], south = heights[at + size], se = heights[at + size + 1];
                int c00 = Shade.scale(lit, crease(here, west, north, nw)), c01 = Shade.scale(lit, crease(here, west, south, sw));
                int c11 = Shade.scale(lit, crease(here, east, south, se)), c10 = Shade.scale(lit, crease(here, east, north, ne));
                out.quad(x0, top, z0, x0, top, z1, x1, top, z1, x1, top, z0, c00, c01, c11, c10);
                quads++;

                float eastBottom = wallBottom(east, here, i == n - 1, cell, skirt);
                if (eastBottom < top) {
                    int high = Shade.scale(lit, 0.66f), low = Shade.scale(high, feet(top - eastBottom));
                    out.quad(x1, top, z1, x1, eastBottom, z1, x1, eastBottom, z0, x1, top, z0, high, low, low, high);
                    quads++;
                }
                float westBottom = wallBottom(west, here, i == 0, cell, skirt);
                if (westBottom < top) {
                    int high = Shade.scale(lit, 0.72f), low = Shade.scale(high, feet(top - westBottom));
                    out.quad(x0, top, z0, x0, westBottom, z0, x0, westBottom, z1, x0, top, z1, high, low, low, high);
                    quads++;
                }
                float southBottom = wallBottom(south, here, j == n - 1, cell, skirt);
                if (southBottom < top) {
                    int high = Shade.scale(lit, 0.8f), low = Shade.scale(high, feet(top - southBottom));
                    out.quad(x0, top, z1, x0, southBottom, z1, x1, southBottom, z1, x1, top, z1, high, low, low, high);
                    quads++;
                }
                float northBottom = wallBottom(north, here, j == 0, cell, skirt);
                if (northBottom < top) {
                    int high = Shade.scale(lit, 0.86f), low = Shade.scale(high, feet(top - northBottom));
                    out.quad(x1, top, z0, x1, northBottom, z0, x0, northBottom, z0, x0, top, z0, high, low, low, high);
                    quads++;
                }
            }
        }
        return quads;
    }

    // Soft dark at the foot of a wall, more so the taller it is.
    private static float feet(float tall) {
        return tall <= 1 ? 0.85f : Math.max(0.55f, 0.85f - tall * 0.03f);
    }

    // How dark a top corner is: darker the more of its three neighbours stand above it.
    private static float crease(short here, short a, short b, short diagonal) {
        int count = 0;
        if (a != Shade.NO_HEIGHT && a > here) count++;
        if (b != Shade.NO_HEIGHT && b > here) count++;
        if (count < 2 && diagonal != Shade.NO_HEIGHT && diagonal > here) count++;
        return CREASE[count];
    }

    // Walks towards the sun over the neighbours: does anything stand in the way?
    public static boolean inShadow(short[] heights, int size, int i, int j, short here, int cell, int b) {
        for (int t = 1; t <= b; t++) {
            int x = i - Math.round(SUN_X * t), z = j - Math.round(SUN_Z * t);
            if (x < 0 || z < 0 || x >= size || z >= size) return false;
            short h = heights[z * size + x];
            if (h != Shade.NO_HEIGHT && h > here + SUN_RISE * t * cell) return true;
        }
        return false;
    }

    // How far down a wall goes. Against somewhere unknown it stops after a short way; on a patch's edge it goes a bit lower, to hide any gap to a coarser patch next door.
    private static float wallBottom(short neighbour, short here, boolean edge, int cell, int skirt) {
        if (neighbour == Shade.NO_HEIGHT) return here + 1f - skirt;
        float bottom = Math.min(neighbour, here) + 1f;
        return edge ? bottom - cell : bottom;
    }
}
