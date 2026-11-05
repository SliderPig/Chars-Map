package com.example.charmap.model;

import java.util.ArrayList;
import java.util.List;

// One saved place. Kept in a plain text file, one per line, so it can be fixed by hand.
public final class Waypoint {
    public String name;
    public int x, y, z;
    public String dimension;
    public int colour;
    public boolean shown = true;
    public boolean death;

    public Waypoint(String name, int x, int y, int z, String dimension, int colour, boolean death) {
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimension = dimension;
        this.colour = colour;
        this.death = death;
    }

    public static final String OVERWORLD = "minecraft:overworld", NETHER = "minecraft:the_nether";

    // Where this waypoint falls in the dimension you're in. Null when it's somewhere with no sensible match (the End, a modded world).
    public int[] placeIn(String here) {
        if (dimension.equals(here)) return new int[] {x, y, z};
        if (dimension.equals(OVERWORLD) && here.equals(NETHER)) return new int[] {Math.floorDiv(x, 8), y, Math.floorDiv(z, 8)};
        if (dimension.equals(NETHER) && here.equals(OVERWORLD)) return new int[] {x * 8, y, z * 8};
        return null;
    }

    public String toLine() {
        return String.join("\t", clean(name), Integer.toString(x), Integer.toString(y), Integer.toString(z), dimension,
                Integer.toHexString(colour & 0xFFFFFF), shown ? "1" : "0", death ? "1" : "0");
    }

    private static String clean(String s) {
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    public static Waypoint fromLine(String line) {
        String[] p = line.split("\t");
        if (p.length < 8) return null;
        try {
            Waypoint w = new Waypoint(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), p[4], Integer.parseInt(p[5], 16), p[7].equals("1"));
            w.shown = p[6].equals("1");
            return w;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static List<Waypoint> parseAll(List<String> lines) {
        List<Waypoint> out = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) continue;
            Waypoint w = fromLine(line);
            if (w != null) out.add(w);
        }
        return out;
    }

    // Only the latest few deaths stay: nobody wants a graveyard of old markers.
    public static void trimDeaths(List<Waypoint> list, int keep) {
        int deaths = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (!list.get(i).death) continue;
            if (++deaths > keep) list.remove(i);
        }
    }
}
