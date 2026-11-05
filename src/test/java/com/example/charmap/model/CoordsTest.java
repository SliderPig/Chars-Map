package com.example.charmap.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CoordsTest {
    @Test
    void regionsWorkOnBothSidesOfZero() {
        assertEquals(0, Coords.region(511));
        assertEquals(1, Coords.region(512));
        assertEquals(-1, Coords.region(-1));
        assertEquals(-1, Coords.region(-512));
        assertEquals(-2, Coords.region(-513));
        assertEquals(511, Coords.inRegion(-1));
        assertEquals(0, Coords.inRegion(-512));
        assertEquals(-1, Coords.chunk(-1));
        assertEquals(-1, Coords.regionOfChunk(-1));
        assertEquals(31, Coords.chunkIndex(-1, 0));
        assertEquals(31 * 32 + 31, Coords.chunkIndex(-1, -1));
    }

    @Test
    void keysRoundTripWithNegatives() {
        for (int[] c : new int[][] {{0, 0, 0, 0}, {3, 5, -1, -1}, {63, 7, 123456, -654321}, {1, 0, -67108864, 67108863}}) {
            long k = Coords.key(c[0], c[1], c[2], c[3]);
            assertEquals(c[0], Coords.keyLayer(k));
            assertEquals(c[1], Coords.keyLevel(k));
            assertEquals(c[2], Coords.keyX(k));
            assertEquals(c[3], Coords.keyZ(k));
        }
    }

    @Test
    void parentCoversTheChild() {
        long child = Coords.key(0, 0, -3, 5);
        long parent = Coords.parent(child);
        assertEquals(1, Coords.keyLevel(parent));
        assertEquals(-2, Coords.keyX(parent));
        assertEquals(2, Coords.keyZ(parent));
        // Same answer as asking for the tile a block in the child falls in
        assertEquals(Coords.tileAt(-3 * 512, 1), Coords.keyX(parent));
    }

    @Test
    void levelMatchesZoom() {
        assertEquals(0, Coords.levelFor(0.25));
        assertEquals(0, Coords.levelFor(1));
        assertEquals(0, Coords.levelFor(1.3));
        assertEquals(1, Coords.levelFor(2));
        assertEquals(3, Coords.levelFor(8));
        assertEquals(Coords.MAX_LEVEL, Coords.levelFor(1000));
    }

    @Test
    void levelGoesCoarserWhenTheViewWouldNeedTooManyTextures() {
        // 4K screen at 1 block per pixel: 3840/512 is about 8 across, 2160/512 about 5 down, 54 tiles
        assertEquals(0, Coords.levelWithin(1, 3840, 2160, 64));
        assertEquals(1, Coords.levelWithin(1, 3840, 2160, 20));
    }

    @Test
    void safeNamesCantEscapeTheFolder() {
        assertEquals("_.._evil", Coords.safeName("/../evil"));
        assertEquals("play.example.com_25565", Coords.safeName("play.example.com:25565"));
        assertEquals("world", Coords.safeName(""));
        assertFalse(Coords.safeName("...").startsWith("."));
    }
}
