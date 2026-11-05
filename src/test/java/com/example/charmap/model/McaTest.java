package com.example.charmap.model;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.Test;

// Builds a real region file in memory, the way the game lays them out, and reads it back.
class McaTest {
    // A small writer for saved data, only for making the test file.
    private static void write(DataOutputStream out, Object v) throws IOException {
        if (v instanceof Integer i) out.writeInt(i);
        else if (v instanceof Byte b) out.writeByte(b);
        else if (v instanceof String s) out.writeUTF(s);
        else if (v instanceof long[] l) {
            out.writeInt(l.length);
            for (long x : l) out.writeLong(x);
        } else if (v instanceof List<?> list) {
            out.writeByte(list.isEmpty() ? 0 : type(list.get(0)));
            out.writeInt(list.size());
            for (Object o : list) write(out, o);
        } else if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.writeByte(type(e.getValue()));
                out.writeUTF((String) e.getKey());
                write(out, e.getValue());
            }
            out.writeByte(0);
        } else throw new IOException("can't write " + v);
    }

    private static int type(Object v) {
        if (v instanceof Integer) return 3;
        if (v instanceof Byte) return 1;
        if (v instanceof String) return 8;
        if (v instanceof long[]) return 12;
        if (v instanceof List) return 9;
        if (v instanceof Map) return 10;
        throw new IllegalArgumentException();
    }

    private static byte[] nbt(Map<String, Object> root) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(10);
            out.writeUTF("");
            write(out, root);
        }
        return bytes.toByteArray();
    }

    private static Map<String, Object> block(String name, String... props) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Name", name);
        if (props.length > 0) {
            Map<String, Object> p = new LinkedHashMap<>();
            for (int i = 0; i < props.length; i += 2) p.put(props[i], props[i + 1]);
            m.put("Properties", p);
        }
        return m;
    }

    private static long[] pack(int[] values, int bits) {
        int perLong = 64 / bits;
        long[] out = new long[(values.length + perLong - 1) / perLong];
        for (int i = 0; i < values.length; i++) out[i / perLong] |= (long) values[i] << ((i % perLong) * bits);
        return out;
    }

    private static Map<String, Object> section(int y, List<Map<String, Object>> palette, int[] blocks, List<Object> biomePalette, long[] biomeData) {
        Map<String, Object> states = new LinkedHashMap<>();
        states.put("palette", palette);
        if (blocks != null) states.put("data", pack(blocks, ChunkSummary.bitsFor(palette.size(), 4)));
        Map<String, Object> biomes = new LinkedHashMap<>();
        biomes.put("palette", biomePalette);
        if (biomeData != null) biomes.put("data", biomeData);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("Y", (byte) y);
        s.put("block_states", states);
        s.put("biomes", biomes);
        // Things the reader must skip over without tripping
        s.put("BlockLight", new long[] {1, 2, 3});
        return s;
    }

    private static int at(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    // Column (1,2) has grass at y 69, column (3,4) has water from 64 down to sand at 60, the rest stone at the top of section 3 (y 63).
    private static Map<String, Object> chunk(int cx, int cz) {
        int[] low = new int[4096];
        List<Map<String, Object>> lowPalette = new ArrayList<>(List.of(block("minecraft:stone"), block("minecraft:sand"), block("minecraft:water", "level", "0")));
        for (int i = 0; i < 4096; i++) low[i] = 0;
        low[at(3, 12, 4)] = 1; // y 60 sand
        for (int y = 13; y <= 15; y++) low[at(3, y, 4)] = 2; // y 61-63 water
        int[] upper = new int[4096];
        List<Map<String, Object>> upperPalette = new ArrayList<>(List.of(block("minecraft:air"), block("minecraft:grass_block", "snowy", "false"), block("minecraft:dirt"), block("minecraft:water", "level", "0")));
        upper[at(1, 5, 2)] = 1; // y 69
        for (int y = 0; y < 5; y++) upper[at(1, y, 2)] = 2;
        upper[at(3, 0, 4)] = 3; // y 64 water surface
        List<Object> two = new ArrayList<>(List.of("minecraft:plains", "minecraft:swamp"));
        int[] biome = new int[64];
        biome[(1 << 4)] = 1; // y 68-71 in section 4... quart y=1, z=0, x=0 is swamp
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", 3465);
        root.put("xPos", cx);
        root.put("zPos", cz);
        root.put("Status", "minecraft:full");
        root.put("block_entities", new ArrayList<>(List.of(block("minecraft:chest"))));
        root.put("sections", new ArrayList<>(List.of(
                section(3, lowPalette, low, new ArrayList<>(List.of("minecraft:plains")), null),
                section(4, upperPalette, upper, two, pack(biome, 1)),
                section(5, new ArrayList<>(List.of(block("minecraft:air"))), null, new ArrayList<>(List.of("minecraft:plains")), null))));
        return root;
    }

    private static byte[] regionFile(Map<Integer, byte[]> chunks, int stamp) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] offsets = new byte[4096], stamps = new byte[4096];
        int sector = 2;
        for (Map.Entry<Integer, byte[]> e : chunks.entrySet()) {
            ByteArrayOutputStream zipped = new ByteArrayOutputStream();
            try (DeflaterOutputStream z = new DeflaterOutputStream(zipped)) {
                z.write(e.getValue());
            }
            byte[] packed = zipped.toByteArray();
            ByteArrayOutputStream one = new ByteArrayOutputStream();
            try (DataOutputStream o = new DataOutputStream(one)) {
                o.writeInt(packed.length + 1);
                o.writeByte(2);
                o.write(packed);
            }
            int sectors = (one.size() + 4095) / 4096;
            one.write(new byte[sectors * 4096 - one.size()]);
            int i = e.getKey() * 4;
            offsets[i] = (byte) (sector >> 16);
            offsets[i + 1] = (byte) (sector >> 8);
            offsets[i + 2] = (byte) sector;
            offsets[i + 3] = (byte) sectors;
            stamps[i] = (byte) (stamp >> 24);
            stamps[i + 1] = (byte) (stamp >> 16);
            stamps[i + 2] = (byte) (stamp >> 8);
            stamps[i + 3] = (byte) stamp;
            body.write(one.toByteArray());
            sector += sectors;
        }
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(offsets);
        file.write(stamps);
        file.write(body.toByteArray());
        return file.toByteArray();
    }

    private static ChunkSummary summary(byte[] file, int index) throws IOException {
        byte[] data = new McaFile(file).chunk(index);
        return ChunkSummary.from(Nbt.read(data), Integer.MIN_VALUE);
    }

    @Test
    void readsARealLayoutRegionFile() throws Exception {
        int index = Coords.chunkIndex(5, 7);
        byte[] file = regionFile(Map.of(index, nbt(chunk(5, 7))), 1700000123);
        McaFile mca = new McaFile(file);
        assertTrue(mca.has(index));
        assertFalse(mca.has(0));
        assertEquals(1700000123, mca.timestamp(index));
        assertNull(mca.chunk(0));
        ChunkSummary s = summary(file, index);
        assertEquals(5, s.chunkX);
        assertEquals(7, s.chunkZ);

        int grass = (2 << 4) | 1;
        assertEquals("minecraft:grass_block[snowy=false]", s.blocks[s.top[grass]]);
        assertEquals(69, s.topY[grass]);
        assertEquals("minecraft:dirt", s.blocks[s.below[grass]]);
        assertEquals(68, s.belowY[grass]);

        int water = (4 << 4) | 3;
        assertEquals("minecraft:water[level=0]", s.blocks[s.top[water]]);
        assertEquals(64, s.topY[water]);
        assertEquals("minecraft:sand", s.blocks[s.below[water]]);
        assertEquals(60, s.belowY[water]);

        int plain = 0;
        assertEquals("minecraft:stone", s.blocks[s.top[plain]]);
        assertEquals(63, s.topY[plain]);
    }

    @Test
    void readsBiomesFromTheSectionOfTheTopBlock() throws Exception {
        Map<String, Object> c = chunk(0, 0);
        ChunkSummary s = ChunkSummary.from(Nbt.read(nbt(c)), Integer.MIN_VALUE);
        int grass = (2 << 4) | 1; // y 69 = section 4, quart y 1, x 0, z 0: swamp
        assertEquals("minecraft:swamp", s.biomes[s.biome[grass]]);
        int water = (4 << 4) | 3; // y 64 = section 4, quart y 0, z 1: plains
        assertEquals("minecraft:plains", s.biomes[s.biome[water]]);
    }

    @Test
    void usesTheHeightmapToStartLowerDown() throws Exception {
        Map<String, Object> c = chunk(0, 0);
        int[] heights = new int[256];
        // Min y here is section 3 (y 48): a height of 16 means top block at y 63
        java.util.Arrays.fill(heights, 16);
        Map<String, Object> maps = new LinkedHashMap<>();
        maps.put("WORLD_SURFACE", pack(heights, 9));
        c.put("Heightmaps", maps);
        ChunkSummary s = ChunkSummary.from(Nbt.read(nbt(c)), Integer.MIN_VALUE);
        assertEquals(63, s.topY[0]);
        // The heightmap says 63, so the grass above it isn't looked at
        assertEquals(63, s.topY[(2 << 4) | 1]);
    }

    @Test
    void underTheRoofSkipsDownThroughSolidBlocks() throws Exception {
        Map<String, Object> c = chunk(0, 0);
        // Start inside the stone at y 55: nothing above counts, the first gap is never found, so the column has no top
        ChunkSummary s = ChunkSummary.from(Nbt.read(nbt(c)), 55);
        assertEquals(-1, s.top[0]);
        // Start inside the top of a column with air above it: y 70 is air, then grass at 69
        ChunkSummary t = ChunkSummary.from(Nbt.read(nbt(c)), 75);
        assertEquals(69, t.topY[(2 << 4) | 1]);
    }

    @Test
    void unfinishedChunksAreLeftOut() throws Exception {
        Map<String, Object> c = chunk(0, 0);
        c.put("Status", "minecraft:noise");
        assertNull(ChunkSummary.from(Nbt.read(nbt(c)), Integer.MIN_VALUE));
    }

    @Test
    void junkDoesntCrash() {
        assertThrows(IOException.class, () -> Nbt.read(new byte[] {10, 0, 0, 3, 0, 1}));
        assertFalse(new McaFile(new byte[10]).has(0));
        assertEquals(0, new McaFile(new byte[10]).timestamp(3));
    }

    @Test
    void summariesSurviveTheNetwork() throws Exception {
        ChunkSummary s = ChunkSummary.from(Nbt.read(nbt(chunk(-3, 9))), Integer.MIN_VALUE);
        s.stamp = 99;
        List<ChunkSummary> back = ChunkSummary.unpackAll(ChunkSummary.packAll(List.of(s, s)));
        assertEquals(2, back.size());
        ChunkSummary b = back.get(1);
        assertEquals(-3, b.chunkX);
        assertEquals(9, b.chunkZ);
        assertEquals(99, b.stamp);
        assertArrayEquals(s.blocks, b.blocks);
        assertArrayEquals(s.biomes, b.biomes);
        assertArrayEquals(s.top, b.top);
        assertArrayEquals(s.below, b.below);
        assertArrayEquals(s.topY, b.topY);
        assertArrayEquals(s.belowY, b.belowY);
        assertArrayEquals(s.biome, b.biome);
    }

    @Test
    void realBlocksComeOutOfASaveWithTheirShape() throws Exception {
        VoxelChunk v = VoxelChunk.fromNbt(Nbt.read(nbt(chunk(4, 2))));
        assertEquals(4, v.chunkX);
        assertEquals(2, v.chunkZ);
        // Lowest ground: the stone at 63 on most columns, sand at 60 under the water: 6 below that, not below the bottom of what's saved (48)
        assertEquals(54, v.minY);
        assertEquals(69 - 54 + 1, v.height);
        int grass = v.cells[VoxelChunk.index(1, 69 - v.minY, 2)];
        assertEquals("minecraft:grass_block[snowy=false]", v.palette[grass]);
        int water = v.cells[VoxelChunk.index(3, 62 - v.minY, 4)];
        assertEquals("minecraft:water[level=0]", v.palette[water]);
        assertEquals(0, v.cells[VoxelChunk.index(1, 69 - v.minY, 3)]);
        assertEquals("minecraft:air", v.palette[0]);
        assertTrue(v.biomes.length >= 1);
    }

    @Test
    void blockDataSurvivesTheDisk() throws Exception {
        VoxelChunk v = VoxelChunk.fromNbt(Nbt.read(nbt(chunk(-4, 9))));
        v.stamp = 1234;
        VoxelChunk b = VoxelChunk.decode(v.encode());
        assertEquals(-4, b.chunkX);
        assertEquals(9, b.chunkZ);
        assertEquals(v.minY, b.minY);
        assertEquals(v.height, b.height);
        assertEquals(1234, b.stamp);
        assertArrayEquals(v.palette, b.palette);
        assertArrayEquals(v.cells, b.cells);
        assertArrayEquals(v.biomes, b.biomes);
        assertArrayEquals(v.biome16, b.biome16);
        assertTrue(v.encode().length < 2000);
    }

    @Test
    void theBlockFileKeepsChunksApartAndReusesTheirPlace() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("charmap-test");
        java.nio.file.Path f = dir.resolve("sub").resolve("v.0.0.cvx");
        assertNull(VoxelFile.read(f, 3));
        byte[] a = {1, 2, 3, 4}, b = {9, 9};
        assertTrue(VoxelFile.write(f, 3, a));
        assertTrue(VoxelFile.write(f, 7, b));
        assertArrayEquals(a, VoxelFile.read(f, 3));
        assertArrayEquals(b, VoxelFile.read(f, 7));
        assertNull(VoxelFile.read(f, 5));
        assertFalse(VoxelFile.write(f, 3, a), "same data, nothing to write");
        long size = java.nio.file.Files.size(f);
        byte[] smaller = {5, 6};
        assertTrue(VoxelFile.write(f, 3, smaller));
        assertEquals(size, java.nio.file.Files.size(f));
        assertArrayEquals(smaller, VoxelFile.read(f, 3));
        assertArrayEquals(b, VoxelFile.read(f, 7));
        byte[] bigger = {7, 7, 7, 7, 7, 7};
        assertTrue(VoxelFile.write(f, 7, bigger));
        assertArrayEquals(bigger, VoxelFile.read(f, 7));
        assertArrayEquals(smaller, VoxelFile.read(f, 3));
    }

    @Test
    void packedValuesKeepToTheSaveFormat() {
        assertEquals(4, ChunkSummary.bitsFor(2, 4));
        assertEquals(5, ChunkSummary.bitsFor(17, 4));
        assertEquals(1, ChunkSummary.bitsFor(2, 1));
        int[] values = new int[256];
        for (int i = 0; i < 256; i++) values[i] = i;
        assertArrayEquals(values, ChunkSummary.unpack(pack(values, 9), 9, 256));
    }
}
