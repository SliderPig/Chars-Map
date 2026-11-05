package com.example.charmap.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OtherLogicTest {
    @Test
    void regionFileNames() {
        FillOrder.Region r = FillOrder.parse("r.-1.3.mca");
        assertEquals(-1, r.x());
        assertEquals(3, r.z());
        assertNull(FillOrder.parse("r.a.3.mca"));
        assertNull(FillOrder.parse("level.dat"));
        assertNull(FillOrder.parse("r.1.mca"));
    }

    @Test
    void nearestRegionFirstAndRadiusCutsOff() {
        List<FillOrder.Region> all = List.of(new FillOrder.Region(5, 0, "a"), new FillOrder.Region(0, 0, "b"), new FillOrder.Region(-1, 0, "c"), new FillOrder.Region(40, 40, "d"));
        List<FillOrder.Region> out = FillOrder.sorted(all, 100, 100, 0);
        assertEquals("b", out.get(0).file());
        assertEquals("c", out.get(1).file());
        assertEquals("d", out.get(3).file());
        assertEquals(3, FillOrder.sorted(all, 100, 100, 8).size());
    }

    @Test
    void chunksInARegionGoNearestFirstAndNoneIsMissed() {
        int[] order = FillOrder.chunks(0, 0, 8, 8);
        assertEquals(0, order[0]);
        Set<Integer> seen = new HashSet<>();
        for (int i : order) seen.add(i);
        assertEquals(1024, seen.size());
        assertEquals(1023, order[1023]);
    }

    @Test
    void spiralCoversTheSquareOnceNearestRingsFirst() {
        int[] s = FillOrder.spiral(3);
        assertEquals(49 * 2, s.length);
        Set<Long> seen = new HashSet<>();
        int ring = 0;
        for (int i = 0; i < s.length; i += 2) {
            assertTrue(seen.add(((long) s[i] << 32) ^ (s[i + 1] & 0xFFFFFFFFL)));
            int r = Math.max(Math.abs(s[i]), Math.abs(s[i + 1]));
            assertTrue(r >= ring);
            ring = r;
        }
    }

    @Test
    void circleStaysInsideItsRadius() {
        float[] c = Shapes.rounded(100, 100, 12);
        for (int i = 0; i < c.length; i += 2) assertEquals(100, Math.hypot(c[i], c[i + 1]), 0.01);
    }

    @Test
    void clippingCutsToTheRectangle() {
        float[] square = Shapes.rounded(10, 0.0001f, 1);
        float[] cut = Shapes.clip(square, 0, 0, 100, 100);
        assertTrue(cut.length >= 6);
        for (int i = 0; i < cut.length; i += 2) {
            assertTrue(cut[i] >= -0.001 && cut[i] <= 10.001);
            assertTrue(cut[i + 1] >= -0.001 && cut[i + 1] <= 10.001);
        }
        assertEquals(0, Shapes.clip(square, 50, 50, 60, 60).length);
        float[] whole = Shapes.clip(square, -50, -50, 50, 50);
        assertEquals(square.length, whole.length);
    }

    @Test
    void rotateKeepsDistances() {
        float[] p = Shapes.rotate(new float[] {10, 0}, Math.PI / 2);
        assertEquals(0, p[0], 0.001);
        assertEquals(10, p[1], 0.001);
    }

    @Test
    void edgePullsInAndLeavesInsidePointsAlone() {
        float[] inside = {10, 10};
        assertFalse(Shapes.toEdge(inside, 100, 100));
        assertEquals(10, inside[0]);
        float[] out = {300, 0};
        assertTrue(Shapes.toEdge(out, 100, 100));
        assertEquals(100, out[0], 0.5);
        assertEquals(0, out[1], 0.01);
        float[] corner = {300, 300};
        assertTrue(Shapes.toEdge(corner, 100, 10));
        // Rounded square: a corner point stops just inside the rounded corner
        assertTrue(corner[0] > 90 && corner[0] < 100);
        assertEquals(corner[0], corner[1], 0.01);
    }

    @Test
    void waypointLinesRoundTrip() {
        Waypoint w = new Waypoint("Home base", -10, 64, 20, Waypoint.OVERWORLD, 0xFF69B4, false);
        w.shown = false;
        Waypoint b = Waypoint.fromLine(w.toLine());
        assertEquals("Home base", b.name);
        assertEquals(-10, b.x);
        assertEquals(64, b.y);
        assertEquals(20, b.z);
        assertEquals(Waypoint.OVERWORLD, b.dimension);
        assertEquals(0xFF69B4, b.colour);
        assertFalse(b.shown);
        assertFalse(b.death);
        assertNull(Waypoint.fromLine("broken line"));
        assertEquals(1, Waypoint.parseAll(List.of("# note", "", w.toLine(), "bad\tline")).size());
    }

    @Test
    void tabsInANameDontBreakTheFile() {
        Waypoint w = new Waypoint("a\tb", 1, 2, 3, "x:y", 0, false);
        assertEquals("a b", Waypoint.fromLine(w.toLine()).name);
    }

    @Test
    void waypointsCrossBetweenOverworldAndNether() {
        Waypoint over = new Waypoint("a", 800, 70, -805, Waypoint.OVERWORLD, 0, false);
        assertArrayEquals(new int[] {100, 70, -101}, over.placeIn(Waypoint.NETHER));
        assertArrayEquals(new int[] {800, 70, -805}, over.placeIn(Waypoint.OVERWORLD));
        assertNull(over.placeIn("minecraft:the_end"));
        Waypoint nether = new Waypoint("b", 10, 40, 20, Waypoint.NETHER, 0, false);
        assertArrayEquals(new int[] {80, 40, 160}, nether.placeIn(Waypoint.OVERWORLD));
    }

    @Test
    void onlyTheLatestDeathsStay() {
        List<Waypoint> list = new ArrayList<>();
        for (int i = 0; i < 6; i++) list.add(new Waypoint("d" + i, 0, 0, 0, "x", 0, true));
        list.add(1, new Waypoint("keep me", 0, 0, 0, "x", 0, false));
        Waypoint.trimDeaths(list, 3);
        assertEquals(4, list.size());
        assertEquals("keep me", list.get(0).name);
        assertEquals("d5", list.get(3).name);
    }
}
