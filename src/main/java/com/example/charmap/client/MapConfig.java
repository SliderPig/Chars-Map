package com.example.charmap.client;

import com.example.charmap.CharMap;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.minecraftforge.fml.loading.FMLPaths;

// Settings, in config/charmap.properties. Anything broken or missing just falls back to its default.
public final class MapConfig {
    private MapConfig() {}

    public static boolean enabled = true;   // false = the whole mod rests: no map, no scanning
    public static boolean showMinimap = true;
    public static int corner = 1;           // 0 top left, 1 top right, 2 bottom left, 3 bottom right
    public static int size = 200;           // real screen pixels
    public static boolean circle = false;
    public static float opacity = 1f;
    public static int zoom = 2;             // index into ZOOMS
    public static boolean rotate = false;
    public static boolean dots = true;      // mobs and players on the maps at all
    public static boolean icons = true;     // their faces instead of plain dots
    public static boolean playerNames = true;
    public static boolean infoText = true;
    public static boolean compass = true;
    public static boolean fillEnabled = true;
    public static int fillRadius = 8;       // regions round you for the automatic fill
    public static int textureMemoryMb = 64;
    public static int caveMode = 1;         // 0 never, 1 automatic underground
    public static int netherMode = 0;       // 0 below the roof, 1 around your height
    public static boolean beacons = true;
    public static boolean debug = false;

    public static final float[] ZOOMS = {0.5f, 1f, 2f, 3f, 4f, 6f, 8f};

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("charmap.properties");
    }

    public static float pixelsPerBlock() {
        return ZOOMS[Math.max(0, Math.min(ZOOMS.length - 1, zoom))];
    }

    public static void load() {
        Properties p = new Properties();
        Path f = file();
        if (Files.exists(f)) {
            try (Reader r = Files.newBufferedReader(f)) {
                p.load(r);
            } catch (IOException | RuntimeException e) {
                CharMap.once("config", "Couldn't read charmap.properties, using defaults", e);
            }
        }
        enabled = bool(p, "enabled", enabled);
        showMinimap = bool(p, "minimap", showMinimap);
        corner = number(p, "corner", corner, 0, 3);
        size = number(p, "size", size, 80, 600);
        circle = bool(p, "circle", circle);
        opacity = number(p, "opacity", Math.round(opacity * 100), 20, 100) / 100f;
        zoom = number(p, "zoom", zoom, 0, ZOOMS.length - 1);
        rotate = bool(p, "rotate", rotate);
        dots = bool(p, "dots", dots);
        icons = bool(p, "icons", icons);
        playerNames = bool(p, "playerNames", playerNames);
        infoText = bool(p, "infoText", infoText);
        compass = bool(p, "compass", compass);
        fillEnabled = bool(p, "fill", fillEnabled);
        fillRadius = number(p, "fillRadius", fillRadius, 1, 64);
        textureMemoryMb = number(p, "textureMemoryMb", textureMemoryMb, 32, 512);
        caveMode = number(p, "caveMode", caveMode, 0, 1);
        netherMode = number(p, "netherMode", netherMode, 0, 1);
        beacons = bool(p, "beacons", beacons);
        debug = bool(p, "debug", debug);
    }

    public static void save() {
        Properties p = new Properties();
        p.setProperty("enabled", "" + enabled);
        p.setProperty("minimap", "" + showMinimap);
        p.setProperty("corner", "" + corner);
        p.setProperty("size", "" + size);
        p.setProperty("circle", "" + circle);
        p.setProperty("opacity", "" + Math.round(opacity * 100));
        p.setProperty("zoom", "" + zoom);
        p.setProperty("rotate", "" + rotate);
        p.setProperty("dots", "" + dots);
        p.setProperty("icons", "" + icons);
        p.setProperty("playerNames", "" + playerNames);
        p.setProperty("infoText", "" + infoText);
        p.setProperty("compass", "" + compass);
        p.setProperty("fill", "" + fillEnabled);
        p.setProperty("fillRadius", "" + fillRadius);
        p.setProperty("textureMemoryMb", "" + textureMemoryMb);
        p.setProperty("caveMode", "" + caveMode);
        p.setProperty("netherMode", "" + netherMode);
        p.setProperty("beacons", "" + beacons);
        p.setProperty("debug", "" + debug);
        try (Writer w = Files.newBufferedWriter(file())) {
            p.store(w, "Char's Map");
        } catch (IOException | RuntimeException e) {
            CharMap.once("config-save", "Couldn't save charmap.properties", e);
        }
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        String v = p.getProperty(key);
        return v == null ? fallback : v.trim().equalsIgnoreCase("true");
    }

    private static int number(Properties p, String key, int fallback, int low, int high) {
        try {
            String v = p.getProperty(key);
            return v == null ? fallback : Math.max(low, Math.min(high, Integer.parseInt(v.trim())));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
