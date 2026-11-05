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
        pixels[0] = 0xFF123456;
        pixels[TileCodec.PIXELS - 1] = 0xFF000001;
        int[] back = TileCodec.decodeLod(TileCodec.encodeLod(pixels), new int[TileCodec.PIXELS]);
        assertArrayEquals(pixels, back);
    }

    @Test
    void knownBlackIsNotUnseen() {
        assertEquals(0xFF000001, TileCodec.known(0));
        assertEquals(0xFF102030, TileCodec.known(0x102030));
    }

    @Test
    void junkFilesAreRefusedNotCrashedOn() {
        assertThrows(DataFormatException.class, () -> TileCodec.decodeBase(new byte[] {1, 2, 3, 4, 5, 6}));
        assertThrows(DataFormatException.class, () -> TileCodec.decodeLod(new byte[0], new int[TileCodec.PIXELS]));
        byte[] good = TileCodec.encodeLod(new int[TileCodec.PIXELS]);
        byte[] cut = java.util.Arrays.copyOf(good, good.length / 2);
        assertThrows(DataFormatException.class, () -> TileCodec.decodeLod(cut, new int[TileCodec.PIXELS]));
    }
}
