package com.example.charmap.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import java.util.zip.DataFormatException;
import org.junit.jupiter.api.Test;

class TileCodecTest {
    @Test
    void baseTileSurvivesTheDisk() throws Exception {
        TileCodec.Base t = new TileCodec.Base();
        Random r = new Random(4);
        for (int i = 0; i < 5000; i++) {
            int at = r.nextInt(TileCodec.PIXELS);
            t.colour[at] = TileCodec.known(r.nextInt());
            t.height[at] = (short) (r.nextInt(380) - 64);
        }
        t.hash[17] = 12345;
        t.stamp[1023] = 1700000000;
        t.north[3] = 70;
        t.west[511] = -5;
        TileCodec.Base back = TileCodec.decodeBase(TileCodec.encodeBase(t));
        assertArrayEquals(t.colour, back.colour);
        assertArrayEquals(t.height, back.height);
        assertArrayEquals(t.hash, back.hash);
        assertArrayEquals(t.stamp, back.stamp);
        assertArrayEquals(t.north, back.north);
        assertArrayEquals(t.west, back.west);
    }

    @Test
    void anEmptyTileIsTiny() {
        assertTrue(TileCodec.encodeBase(new TileCodec.Base()).length < 16_000);
    }

    @Test
    void farLevelSurvivesTheDisk() throws Exception {
        int[] pixels = new int[TileCodec.PIXELS];
        short[] heights = new short[TileCodec.PIXELS];
        java.util.Arrays.fill(heights, Shade.NO_HEIGHT);
        pixels[0] = 0xFF123456;
        heights[0] = 70;
        pixels[TileCodec.PIXELS - 1] = 0xFF000001;
        heights[TileCodec.PIXELS - 1] = -60;
        int[] back = new int[TileCodec.PIXELS];
        short[] backHeights = new short[TileCodec.PIXELS];
        TileCodec.decodeLod(TileCodec.encodeLod(pixels, heights), back, backHeights);
        assertArrayEquals(pixels, back);
        assertArrayEquals(heights, backHeights);
    }

    @Test
    void knownBlackIsNotUnseen() {
        assertEquals(0xFF000001, TileCodec.known(0));
        assertEquals(0xFF102030, TileCodec.known(0x102030));
    }

    @Test
    void junkFilesAreRefusedNotCrashedOn() {
        assertThrows(DataFormatException.class, () -> TileCodec.decodeBase(new byte[] {1, 2, 3, 4, 5, 6}));
        int[] p = new int[TileCodec.PIXELS];
        short[] h = new short[TileCodec.PIXELS];
        assertThrows(DataFormatException.class, () -> TileCodec.decodeLod(new byte[0], p, h));
        byte[] good = TileCodec.encodeLod(p, h);
        byte[] cut = java.util.Arrays.copyOf(good, good.length / 2);
        assertThrows(DataFormatException.class, () -> TileCodec.decodeLod(cut, p, h));
    }
}
