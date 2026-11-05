package com.example.charmap.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// What the map needs from a saved chunk, per column: the top block, what's under it and how far down.
// Made from a save file on your PC, or on a server that has this mod, then coloured on your PC because only that has the textures.
public final class ChunkSummary {
    public int chunkX, chunkZ, stamp;
    public String[] blocks;   // "minecraft:oak_log[axis=y]" and so on
    public String[] biomes;
    public final short[] top = new short[256], below = new short[256];
    public final short[] topY = new short[256], belowY = new short[256];
    public final byte[] biome = new byte[256];

    public static final short NOTHING = -1;

    // Blocks you see through completely.
    public static boolean isAir(String block) {
        switch (baseName(block)) {
            case "minecraft:air": case "minecraft:cave_air": case "minecraft:void_air":
            case "minecraft:barrier": case "minecraft:light": case "minecraft:structure_void":
                return true;
            default:
                return false;
        }
    }

    public static boolean isWaterLike(String block) {
        switch (baseName(block)) {
            case "minecraft:water": case "minecraft:kelp": case "minecraft:kelp_plant": case "minecraft:seagrass":
            case "minecraft:tall_seagrass": case "minecraft:bubble_column":
                return true;
            default:
                return block.contains("waterlogged=true");
        }
    }

    public static String baseName(String block) {
        int i = block.indexOf('[');
        return i < 0 ? block : block.substring(0, i);
    }

    private static final class Section {
        int y;
        String[] palette;
        long[] data;
        int[] decoded;
        boolean allAir, allWater;
        boolean[] air, water;
        String[] biomePalette;
        long[] biomeData;

        int at(int index) {
            if (palette.length == 1 || data == null) return 0;
            if (decoded == null) decoded = unpack(data, bitsFor(palette.length, 4), 4096);
            return decoded[index];
        }
    }

    public static int bitsFor(int size, int least) {
        int bits = 32 - Integer.numberOfLeadingZeros(Math.max(1, size - 1));
        return Math.max(least, bits);
    }

    // Packed values from a save: as many fit in each long as whole, top bits unused.
    public static int[] unpack(long[] data, int bits, int count) {
        int[] out = new int[count];
        if (bits <= 0) return out;
        int perLong = 64 / bits;
        long mask = (1L << bits) - 1;
        int i = 0;
        for (int l = 0; l < data.length && i < count; l++) {
            long v = data[l];
            for (int k = 0; k < perLong && i < count; k++, i++) {
                out[i] = (int) (v & mask);
                v >>>= bits;
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Section section(Map<String, Object> tag) {
        Object y = tag.get("Y");
        Object states = tag.get("block_states");
        if (!(y instanceof Number) || !(states instanceof Map)) return null;
        Map<String, Object> st = (Map<String, Object>) states;
        Object palette = st.get("palette");
        if (!(palette instanceof List<?> list) || list.isEmpty()) return null;
        Section s = new Section();
        s.y = ((Number) y).intValue();
        s.palette = new String[list.size()];
        s.air = new boolean[list.size()];
        s.water = new boolean[list.size()];
        boolean onlyAir = true, onlyWater = true;
        for (int i = 0; i < list.size(); i++) {
            String name = stateName(list.get(i));
            s.palette[i] = name;
            s.air[i] = isAir(name);
            s.water[i] = isWaterLike(name);
            onlyAir &= s.air[i];
            onlyWater &= s.water[i];
        }
        s.allAir = onlyAir;
        s.allWater = onlyWater;
        if (st.get("data") instanceof long[] data) s.data = data;
        if (tag.get("biomes") instanceof Map<?, ?> bio) {
            if (bio.get("palette") instanceof List<?> bl && !bl.isEmpty()) {
                s.biomePalette = new String[bl.size()];
                for (int i = 0; i < bl.size(); i++) s.biomePalette[i] = String.valueOf(bl.get(i));
                if (bio.get("data") instanceof long[] bd) s.biomeData = bd;
            }
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static String stateName(Object entry) {
        if (!(entry instanceof Map)) return "minecraft:air";
        Map<String, Object> m = (Map<String, Object>) entry;
        String name = String.valueOf(m.get("Name"));
        if (m.get("Properties") instanceof Map<?, ?> props && !props.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<?, ?> e : props.entrySet()) parts.add(e.getKey() + "=" + e.getValue());
            java.util.Collections.sort(parts);
            return name + "[" + String.join(",", parts) + "]";
        }
        return name;
    }

    // Null when the chunk isn't finished or is from a much older save format.
    // ceilingY: where to start looking in a world with a roof (the Nether), or Integer.MIN_VALUE for the open sky.
    @SuppressWarnings("unchecked")
    public static ChunkSummary from(Map<String, Object> root, int ceilingY) {
        String status = String.valueOf(root.get("Status"));
        if (!status.endsWith("full")) return null;
        if (!(root.get("xPos") instanceof Number xp) || !(root.get("zPos") instanceof Number zp) || !(root.get("sections") instanceof List<?> raw)) return null;
        List<Section> sections = new ArrayList<>();
        for (Object o : raw) {
            if (o instanceof Map) {
                Section s = section((Map<String, Object>) o);
                if (s != null) sections.add(s);
            }
        }
        if (sections.isEmpty()) return null;
        sections.sort((a, b) -> b.y - a.y); // highest first
        ChunkSummary out = new ChunkSummary();
        out.chunkX = xp.intValue();
        out.chunkZ = zp.intValue();
        List<String> names = new ArrayList<>();
        Map<String, Integer> index = new java.util.HashMap<>();
        List<String> biomeNames = new ArrayList<>();
        Map<String, Integer> biomeIndex = new java.util.HashMap<>();

        int[] surface = null;
        int minY = sections.get(sections.size() - 1).y * 16;
        if (root.get("Heightmaps") instanceof Map<?, ?> hm && hm.get("WORLD_SURFACE") instanceof long[] packed && ceilingY == Integer.MIN_VALUE) {
            surface = unpack(packed, 9, 256);
        }

        // Which section holds a given y, without searching each time.
        int topSection = sections.get(0).y, bottomSection = sections.get(sections.size() - 1).y;
        Section[] byY = new Section[topSection - bottomSection + 1];
        for (Section s : sections) byY[s.y - bottomSection] = s;

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int column = (z << 4) | x;
                int start = ceilingY != Integer.MIN_VALUE ? ceilingY : surface != null && surface[column] > 0 ? minY + surface[column] - 1 : topSection * 16 + 15;
                start = Math.min(start, topSection * 16 + 15);
                int foundTop = -1, foundBelow = -1, foundY = 0, foundBelowY = 0;
                boolean inRoof = ceilingY != Integer.MIN_VALUE;
                boolean water = false;
                for (int y = start; y >= minY; y--) {
                    Section s = byY[(y >> 4) - bottomSection];
                    if (s == null) { // a section not saved at all is air
                        inRoof = false;
                        y = (y & ~15);
                        continue;
                    }
                    if (s.allAir) {
                        inRoof = false;
                        y = (y & ~15);
                        continue;
                    }
                    if (foundTop >= 0 && water && s.allWater) {
                        y = (y & ~15);
                        continue;
                    }
                    int id = s.at(((y & 15) << 8) | (z << 4) | x);
                    if (id >= s.palette.length) continue;
                    if (s.air[id]) {
                        inRoof = false;
                        continue;
                    }
                    if (inRoof) continue; // still inside the roof: keep going till the first gap
                    if (foundTop < 0) {
                        foundTop = indexOf(names, index, s.palette[id]);
                        foundY = y;
                        water = s.water[id];
                        continue;
                    }
                    if (water && s.water[id]) continue;
                    foundBelow = indexOf(names, index, s.palette[id]);
                    foundBelowY = y;
                    break;
                }
                out.top[column] = (short) foundTop;
                out.below[column] = (short) foundBelow;
                out.topY[column] = (short) foundY;
                out.belowY[column] = (short) foundBelowY;
                out.biome[column] = (byte) biomeAt(byY, bottomSection, foundTop < 0 ? minY : foundY, x, z, biomeNames, biomeIndex);
            }
        }
        out.blocks = names.toArray(new String[0]);
        out.biomes = biomeNames.toArray(new String[0]);
        return out;
    }

    private static int biomeAt(Section[] byY, int bottom, int y, int x, int z, List<String> names, Map<String, Integer> index) {
        int i = (y >> 4) - bottom;
        Section s = i >= 0 && i < byY.length ? byY[i] : null;
        if (s == null || s.biomePalette == null) return indexOf(names, index, "minecraft:plains");
        int id = 0;
        if (s.biomePalette.length > 1 && s.biomeData != null) {
            int bits = bitsFor(s.biomePalette.length, 1), perLong = 64 / bits;
            int q = (((y & 15) >> 2) << 4) | ((z >> 2) << 2) | (x >> 2);
            int l = q / perLong;
            if (l < s.biomeData.length) id = (int) ((s.biomeData[l] >>> ((q % perLong) * bits)) & ((1L << bits) - 1));
        }
        return indexOf(names, index, s.biomePalette[Math.min(id, s.biomePalette.length - 1)]);
    }

    private static int indexOf(List<String> names, Map<String, Integer> index, String name) {
        Integer i = index.get(name);
        if (i != null) return i;
        names.add(name);
        index.put(name, names.size() - 1);
        return names.size() - 1;
    }

    // For sending over the network. Heights fit in shorts and most columns look alike, so a chunk comes to a few hundred bytes once squeezed.
    public void write(DataOutputStream out) throws IOException {
        out.writeInt(chunkX);
        out.writeInt(chunkZ);
        out.writeInt(stamp);
        out.writeShort(blocks.length);
        for (String s : blocks) out.writeUTF(s);
        out.writeByte(biomes.length);
        for (String s : biomes) out.writeUTF(s);
        for (short[] a : new short[][] {top, below, topY, belowY}) for (short v : a) out.writeShort(v);
        out.write(biome);
    }

    public static ChunkSummary readFrom(DataInputStream in) throws IOException {
        ChunkSummary s = new ChunkSummary();
        s.chunkX = in.readInt();
        s.chunkZ = in.readInt();
        s.stamp = in.readInt();
        s.blocks = new String[in.readUnsignedShort()];
        for (int i = 0; i < s.blocks.length; i++) s.blocks[i] = in.readUTF();
        s.biomes = new String[in.readUnsignedByte()];
        for (int i = 0; i < s.biomes.length; i++) s.biomes[i] = in.readUTF();
        for (short[] a : new short[][] {s.top, s.below, s.topY, s.belowY}) for (int i = 0; i < 256; i++) a[i] = in.readShort();
        in.readFully(s.biome);
        return s;
    }

    public static byte[] packAll(List<ChunkSummary> chunks) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        java.util.zip.Deflater d = new java.util.zip.Deflater(6);
        try (DataOutputStream out = new DataOutputStream(new java.util.zip.DeflaterOutputStream(bytes, d))) {
            out.writeShort(chunks.size());
            for (ChunkSummary c : chunks) c.write(out);
        } finally {
            d.end();
        }
        return bytes.toByteArray();
    }

    public static List<ChunkSummary> unpackAll(byte[] packed) throws IOException {
        java.util.zip.Inflater i = new java.util.zip.Inflater();
        try (DataInputStream in = new DataInputStream(new java.util.zip.InflaterInputStream(new ByteArrayInputStream(packed), i))) {
            int n = in.readUnsignedShort();
            List<ChunkSummary> out = new ArrayList<>(n);
            for (int k = 0; k < n; k++) out.add(readFrom(in));
            return out;
        } finally {
            i.end();
        }
    }
}
