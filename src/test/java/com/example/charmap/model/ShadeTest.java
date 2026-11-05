package com.example.charmap.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ShadeTest {
    private static int red(int c) { return (c >> 16) & 255; }

    @Test
    void flatGroundStaysTheSame() {
        assertEquals(0xFF808080, Shade.hills(0xFF808080, 64, 64, 64));
    }

    @Test
    void risingGroundIsLighterAndFallingDarker() {
        assertTrue(red(Shade.hills(0xFF808080, 70, 64, 64)) > 128);
        assertTrue(red(Shade.hills(0xFF808080, 60, 64, 64)) < 128);
    }

    @Test
    void unknownNeighboursCountAsLevel() {
        assertEquals(0xFF808080, Shade.hills(0xFF808080, 64, Shade.NO_HEIGHT, Shade.NO_HEIGHT));
    }

    @Test
    void unseenStaysUnseen() {
        assertEquals(0, Shade.hills(0, 64, 10, 10));
    }

    @Test
    void cliffsDontGoBlackOrOverflow() {
        assertTrue(red(Shade.hills(0xFF404040, 0, 300, 300)) > 30);
        assertEquals(255, red(Shade.hills(0xFFFFFFFF, 300, 0, 0)));
    }

    @Test
    void averageIgnoresUnseenPixels() {
        assertEquals(0xFF646464, Shade.average4(0xFF646464, 0, 0, 0));
        assertEquals(0xFF326496, Shade.average4(0xFF000000 | 0x326496, 0xFF000000 | 0x326496, 0, 0));
        assertEquals(0, Shade.average4(0, 0, 0, 0));
        assertEquals(0xFF7F7F7F, Shade.average4(0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF, 0xFF000000));
    }

    @Test
    void waterTintMultiplies() {
        assertEquals(0xFF3F76E4, Shade.multiply(0xFFFFFFFF, 0x3F76E4));
        assertEquals(0xFF000000, Shade.multiply(0xFF000000, 0x3F76E4));
    }

    @Test
    void deeperWaterShowsLessOfTheBottom() {
        int shallow = Shade.water(0xFF0000FF, 0xFFFF0000, 1), deep = Shade.water(0xFF0000FF, 0xFFFF0000, 20);
        assertTrue(red(shallow) > red(deep));
        assertTrue((shallow & 255) > 0);
    }

    @Test
    void textureAverageSkipsHoles() {
        int[] pixels = {0xFF102030, 0x00FFFFFF, 0xFF102030, 0x00FFFFFF};
        int avg = Shade.averagePixels(pixels, 4);
        assertEquals(0x102030, avg & 0xFFFFFF);
        assertEquals(127, avg >>> 24);
        assertEquals(0, Shade.averagePixels(new int[] {0, 0}, 2));
    }
}
