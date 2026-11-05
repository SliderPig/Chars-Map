package com.example.charmap.model;

// Turns a square of map pixels into blocks you can look at: a flat top on every column and side walls wherever the neighbour is lower.
// The same code makes every level of detail: further away, each column is just wider.
public final class HeightMesh {
    private HeightMesh() {}

    public interface Sink {
        void quad(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, int colour);
    }

    // n columns each way, each `cell` blocks wide. colours and heights hold (n+2) x (n+2) values: one extra row all round, belonging to the neighbours.
    // Positions come out relative to the patch's corner. Corners go anticlockwise seen from outside, so back faces can be skipped. Returns the number of quads.
    public static int build(int n, int cell, int[] colours, short[] heights, Sink out) {
        int size = n + 2, quads = 0;
        int skirt = Math.max(4, cell * 2);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int at = (j + 1) * size + i + 1;
                int colour = colours[at];
                short here = heights[at];
                if ((colour >>> 24) == 0 || here == Shade.NO_HEIGHT) continue;
                float top = here + 1f;
                float x0 = i * cell, x1 = x0 + cell, z0 = j * cell, z1 = z0 + cell;
                out.quad(x0, top, z0, x0, top, z1, x1, top, z1, x1, top, z0, colour);
                quads++;

                float east = wallBottom(heights[at + 1], here, i == n - 1, cell, skirt);
                if (east < top) {
                    out.quad(x1, top, z1, x1, east, z1, x1, east, z0, x1, top, z0, Shade.scale(colour, 0.66f));
                    quads++;
                }
                float west = wallBottom(heights[at - 1], here, i == 0, cell, skirt);
                if (west < top) {
                    out.quad(x0, top, z0, x0, west, z0, x0, west, z1, x0, top, z1, Shade.scale(colour, 0.66f));
                    quads++;
                }
                float south = wallBottom(heights[at + size], here, j == n - 1, cell, skirt);
                if (south < top) {
                    out.quad(x0, top, z1, x0, south, z1, x1, south, z1, x1, top, z1, Shade.scale(colour, 0.82f));
                    quads++;
                }
                float north = wallBottom(heights[at - size], here, j == 0, cell, skirt);
                if (north < top) {
                    out.quad(x1, top, z0, x1, north, z0, x0, north, z0, x0, top, z0, Shade.scale(colour, 0.82f));
                    quads++;
                }
            }
        }
        return quads;
    }

    // How far down a wall goes. Against somewhere unknown it stops after a short way; on a patch's edge it goes a bit lower, to hide any gap to a coarser patch next door.
    private static float wallBottom(short neighbour, short here, boolean edge, int cell, int skirt) {
        if (neighbour == Shade.NO_HEIGHT) return here + 1f - skirt;
        float bottom = Math.min(neighbour, here) + 1f;
        return edge ? bottom - cell : bottom;
    }
}
