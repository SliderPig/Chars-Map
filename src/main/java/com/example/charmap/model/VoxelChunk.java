package com.example.charmap.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

// Every block of a chunk from just under its lowest ground up to its highest block: what the 3D view builds houses and towers from.
// One block list per chunk (like the game's own saves), so a chunk comes to a few KB once squeezed.
public final class VoxelChunk {
    public static final int BELOW = 6; // blocks kept under the lowest ground, so cliffs have something to stand on

    public int chunkX, chunkZ, minY, height, stamp;
    public String[] palette;      // [0] is always air
    public short[] cells;         // (y - minY) * 256 + z * 16 + x
    public String[] biomes;
    public final byte[] biome16 = new byte[16]; // one per 4x4 columns

    public static int index(int x, int yOffset, int z) {
        return yOffset * 256 + z * 16 + x;
    }

    // From a saved chunk. Null when it isn't finished.
    @SuppressWarnings("unchecked")
    public static VoxelChunk fromNbt(Map<String, Object> root) {
        String status = String.valueOf(root.get("Status"));
        if (!status.endsWith("full")) return null;
        if (!(root.get("xPos") instanceof Number xp) || !(root.get("zPos") instanceof Number zp) || !(root.get("sections") instanceof List<?> raw)) return null;
        List<ChunkSummary.Section> sections = new ArrayList<>();
        for (Object o : raw) {
            if (o instanceof Map) {
                ChunkSummary.Section s = ChunkSummary.section((Map<String, Object>) o);
                if (s != null) sections.add(s);
            }
        }
        if (sections.isEmpty()) return null;
        sections.sort((a, b) -> b.y - a.y);
        int topSection = sections.get(0).y, bottomSection = sections.get(sections.size() - 1).y;
        ChunkSummary.Section[] byY = new ChunkSummary.Section[topSection - bottomSection + 1];
        for (ChunkSummary.Section s : sections) byY[s.y - bottomSection] = s;

        // Where each column's top block and first solid ground are.
        int bottomY = bottomSection * 16, lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        int[] tops = new int[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int top = Integer.MIN_VALUE, ground = Integer.MIN_VALUE;
                for (int y = topSection * 16 + 15; y >= bottomY; y--) {
                    ChunkSummary.Section s = byY[(y >> 4) - bottomSection];
                    if (s == null || s.allAir) {
                        y &= ~15;
                        continue;
                    }
                    int id = s.at(((y & 15) << 8) | (z << 4) | x);
                    if (id >= s.palette.length || s.air[id]) continue;
                    if (top == Integer.MIN_VALUE) top = y;
                    if (!s.water[id]) {
                        ground = y;
                        break;
                    }
                    if (top - y > 64) { // a deep ocean: its floor doesn't need to be on the model
                        ground = y;
                        break;
                    }
                }
                tops[(z << 4) | x] = top;
                if (top != Integer.MIN_VALUE) {
                    highest = Math.max(highest, top);
                    lowest = Math.min(lowest, ground != Integer.MIN_VALUE ? ground : top);
                }
            }
        }
        VoxelChunk c = new VoxelChunk();
        c.chunkX = xp.intValue();
        c.chunkZ = zp.intValue();
        if (highest == Integer.MIN_VALUE) {
            c.minY = bottomY;
            c.height = 0;
            c.palette = new String[] {"minecraft:air"};
            c.cells = new short[0];
            c.biomes = new String[] {"minecraft:plains"};
            return c;
        }
        c.minY = Math.max(bottomY, lowest - BELOW);
        c.height = highest - c.minY + 1;
        c.cells = new short[c.height * 256];
        List<String> names = new ArrayList<>();
        Map<String, Integer> nameIndex = new HashMap<>();
        names.add("minecraft:air");
        nameIndex.put("minecraft:air", 0);
        for (int sy = c.minY >> 4; sy <= highest >> 4; sy++) {
            ChunkSummary.Section s = sy - bottomSection >= 0 && sy - bottomSection < byY.length ? byY[sy - bottomSection] : null;
            if (s == null || s.allAir) continue;
            int[] map = new int[s.palette.length];
            for (int i = 0; i < map.length; i++) {
                if (s.air[i]) continue;
                Integer known = nameIndex.get(s.palette[i]);
                if (known == null) {
                    known = names.size();
                    names.add(s.palette[i]);
                    nameIndex.put(s.palette[i], known);
                }
                map[i] = known;
            }
            for (int ly = 0; ly < 16; ly++) {
                int y = sy * 16 + ly;
                if (y < c.minY || y > highest) continue;
                int o = (y - c.minY) * 256;
                for (int i = 0; i < 256; i++) {
                    int id = s.at((ly << 8) | i);
                    c.cells[o + i] = id < map.length ? (short) map[id] : 0;
                }
            }
        }
        c.palette = names.toArray(new String[0]);
        List<String> biomeNames = new ArrayList<>();
        Map<String, Integer> biomeIndex = new HashMap<>();
        for (int q = 0; q < 16; q++) {
            int x = (q & 3) * 4 + 2, z = (q >> 2) * 4 + 2;
            int y = tops[(z << 4) | x];
            c.biome16[q] = (byte) ChunkSummary.biomeAt(byY, bottomSection, y == Integer.MIN_VALUE ? c.minY : y, x, z, biomeNames, biomeIndex);
        }
        c.biomes = biomeNames.toArray(new String[0]);
        return c;
    }

    public byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
        Deflater d = new Deflater(4);
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes, d, 4096))) {
            out.writeInt(chunkX);
            out.writeInt(chunkZ);
            out.writeInt(minY);
            out.writeInt(height);
            out.writeInt(stamp);
            out.writeShort(palette.length);
            for (String s : palette) out.writeUTF(s);
            out.writeByte(biomes.length);
            for (String s : biomes) out.writeUTF(s);
            out.write(biome16);
            byte[] low = new byte[cells.length];
            for (int i = 0; i < cells.length; i++) low[i] = (byte) cells[i];
            out.write(low);
            if (palette.length > 256) {
                for (int i = 0; i < cells.length; i++) low[i] = (byte) (cells[i] >> 8);
                out.write(low);
            }
        } finally {
            d.end();
        }
        return bytes.toByteArray();
    }

    public static VoxelChunk decode(byte[] data) throws IOException {
        Inflater i = new Inflater();
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data), i, 4096))) {
            VoxelChunk c = new VoxelChunk();
            c.chunkX = in.readInt();
            c.chunkZ = in.readInt();
            c.minY = in.readInt();
            c.height = in.readInt();
            c.stamp = in.readInt();
            if (c.height < 0 || c.height > 4096) throw new IOException("bad height");
            c.palette = new String[in.readUnsignedShort()];
            for (int k = 0; k < c.palette.length; k++) c.palette[k] = in.readUTF();
            c.biomes = new String[in.readUnsignedByte()];
            for (int k = 0; k < c.biomes.length; k++) c.biomes[k] = in.readUTF();
            in.readFully(c.biome16);
            c.cells = new short[c.height * 256];
            byte[] low = new byte[c.cells.length];
            in.readFully(low);
            for (int k = 0; k < low.length; k++) c.cells[k] = (short) (low[k] & 255);
            if (c.palette.length > 256) {
                in.readFully(low);
                for (int k = 0; k < low.length; k++) c.cells[k] |= (short) ((low[k] & 255) << 8);
            }
            return c;
        } finally {
            i.end();
        }
    }
}
