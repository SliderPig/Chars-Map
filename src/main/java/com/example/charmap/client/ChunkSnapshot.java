package com.example.charmap.client;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.levelgen.Heightmap;

// A copy of the part of a chunk the map looks at, made quickly on the main thread (a copy of a few small arrays) so all the slow work can happen elsewhere.
public final class ChunkSnapshot {
    public final int chunkX, chunkZ, layer;
    public boolean startInsideSolid;  // Nether roof or cave slice: ignore solid blocks until the first gap
    public boolean sliceRock;         // cave slice: no gap at all counts as solid rock, not as unknown
    private final int lowestSection;
    private final PalettedContainer<BlockState>[] sections;
    private final PalettedContainerRO<Holder<Biome>>[] biomes;
    private final int minSection;
    private final short[] heights;    // first free y per column, null = start from startFixed
    private int startFixed;
    private final int bottom;

    @SuppressWarnings("unchecked")
    private ChunkSnapshot(LevelChunk chunk, int layer, int fromSection, int toSection, short[] heights) {
        this.chunkX = chunk.getPos().x;
        this.chunkZ = chunk.getPos().z;
        this.layer = layer;
        this.heights = heights;
        LevelChunkSection[] all = chunk.getSections();
        this.minSection = chunk.getMinSection();
        this.lowestSection = fromSection;
        int count = Math.max(0, toSection - fromSection + 1);
        this.sections = new PalettedContainer[count];
        this.biomes = new PalettedContainerRO[all.length];
        for (int i = 0; i < all.length; i++) biomes[i] = all[i].getBiomes();
        for (int i = 0; i < count; i++) {
            int index = fromSection + i - minSection;
            if (index >= 0 && index < all.length && !all[index].hasOnlyAir()) sections[i] = all[index].getStates().copy();
        }
        this.bottom = fromSection * 16;
    }

    // The view from the sky. The chunk's height map says where each column's top is, so only the few sections that matter get copied.
    public static ChunkSnapshot surface(LevelChunk chunk) {
        Heightmap map = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE);
        short[] heights = new short[256];
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int h = map.getFirstAvailable(x, z);
                heights[(z << 4) | x] = (short) h;
                if (h < low) low = h;
                if (h > high) high = h;
            }
        }
        int minSection = chunk.getMinSection();
        // Far enough down to find the bottom of a deep ocean under the lowest column, and a plant's ground.
        int from = Math.max(minSection, ((low - 64) >> 4));
        int to = Math.min(chunk.getMaxSection() - 1, (high - 1) >> 4);
        return new ChunkSnapshot(chunk, 0, from, Math.max(from, to), heights);
    }

    // Under a roof or inside a slice: start here and ignore solid blocks until the first gap.
    public static ChunkSnapshot slice(LevelChunk chunk, int layer, int startY, int depth, boolean rock) {
        int minSection = chunk.getMinSection();
        int to = Math.min(chunk.getMaxSection() - 1, startY >> 4);
        int from = Math.max(minSection, (startY - depth) >> 4);
        ChunkSnapshot s = new ChunkSnapshot(chunk, layer, from, Math.max(from, to), null);
        s.startFixed = Math.min(startY, (to << 4) + 15);
        s.startInsideSolid = true;
        s.sliceRock = rock;
        return s;
    }

    public int startY(int column) {
        if (heights == null) return startFixed;
        int h = heights[column] - 1;
        return Math.min(h, (lowestSection + sections.length) * 16 - 1);
    }

    public int bottomY() {
        return bottom;
    }

    public PalettedContainer<BlockState> section(int sectionY) {
        int i = sectionY - lowestSection;
        return i >= 0 && i < sections.length ? sections[i] : null;
    }

    public Holder<Biome> biome(int blockX, int y, int blockZ) {
        int i = (y >> 4) - minSection;
        i = Math.max(0, Math.min(biomes.length - 1, i));
        return biomes[i].get((blockX & 15) >> 2, (y & 15) >> 2, (blockZ & 15) >> 2);
    }

    // True when there's no block data worth looking at.
    public boolean empty() {
        for (PalettedContainer<BlockState> s : sections) if (s != null) return false;
        return true;
    }

    public static boolean isAir(BlockState s) {
        return s.isAir() || s.is(Blocks.BARRIER);
    }
}
