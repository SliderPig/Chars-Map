package com.example.charmap.model;

// All the sums about where a block falls in the tile pyramid. A tile is always 512x512 pixels.
// Level 0 is one Minecraft region (1 pixel = 1 block), each level up covers twice as far at half the detail,
// so any zoom needs about the same few textures.
public final class Coords {
    private Coords() {}

    public static final int TILE = 512;
    public static final int MAX_LEVEL = 5;

    public static int region(int block) {
        return block >> 9;
    }

    public static int inRegion(int block) {
        return block & 511;
    }

    public static int chunk(int block) {
        return block >> 4;
    }

    public static int regionOfChunk(int chunk) {
        return chunk >> 5;
    }

    // Which of a region's 1024 chunks this is.
    public static int chunkIndex(int chunkX, int chunkZ) {
        return (chunkX & 31) | ((chunkZ & 31) << 5);
    }

    // Blocks across one tile of this level.
    public static int tileBlocks(int level) {
        return TILE << level;
    }

    public static int tileAt(int block, int level) {
        return block >> (9 + level);
    }

    // Key for one tile: 6 bits layer, 3 bits level, 27 bits each way.
    public static long key(int layer, int level, int x, int z) {
        return ((long) (layer & 63) << 57) | ((long) (level & 7) << 54) | ((x & 0x7FFFFFFL) << 27) | (z & 0x7FFFFFFL);
    }

    public static int keyLayer(long key) {
        return (int) ((key >>> 57) & 63);
    }

    public static int keyLevel(long key) {
        return (int) ((key >>> 54) & 7);
    }

    public static int keyX(long key) {
        return (int) ((key << 10) >> 37);
    }

    public static int keyZ(long key) {
        return (int) ((key << 37) >> 37);
    }

    public static long parent(long key) {
        return key(keyLayer(key), keyLevel(key) + 1, keyX(key) >> 1, keyZ(key) >> 1);
    }

    // The level whose pixels best match the screen: blocksPerPixel 1 = level 0, 2 = level 1, and so on, rounded to the nearest.
    public static int levelFor(double blocksPerPixel) {
        if (blocksPerPixel <= 1) return 0;
        int level = (int) Math.round(Math.log(blocksPerPixel) / Math.log(2));
        return Math.max(0, Math.min(MAX_LEVEL, level));
    }

    // How many tiles of a level cover a span of this many blocks, from the left block onwards.
    public static int tilesAcross(double fromBlock, double toBlock, int level) {
        return tileAt((int) Math.floor(toBlock), level) - tileAt((int) Math.floor(fromBlock), level) + 1;
    }

    // Level to draw so the view needs no more textures than the budget. The screen can be big, the texture pool isn't.
    public static int levelWithin(double blocksPerPixel, double viewW, double viewH, int budget) {
        int level = levelFor(blocksPerPixel);
        while (level < MAX_LEVEL) {
            double across = viewW * blocksPerPixel / tileBlocks(level) + 1, down = viewH * blocksPerPixel / tileBlocks(level) + 1;
            if (Math.ceil(across) * Math.ceil(down) <= budget) break;
            level++;
        }
        return level;
    }

    public static String safeName(String raw) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length() && out.length() < 80; i++) {
            char c = raw.charAt(i);
            out.append(Character.isLetterOrDigit(c) && c < 128 || c == '-' || c == '.' ? c : '_');
        }
        String s = out.toString();
        while (s.startsWith(".")) s = s.substring(1);
        return s.isEmpty() ? "world" : s;
    }
}
