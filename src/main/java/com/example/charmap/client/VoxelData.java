package com.example.charmap.client;

import com.example.charmap.model.VoxelChunk;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.PalettedContainer;

// A chunk's blocks ready to build a model from, with real block states instead of names.
final class VoxelData {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState(), UNDER = Blocks.STONE.defaultBlockState();

    final int chunkX, chunkZ, minY, height, hash;
    final BlockState[] palette;
    final short[] cells;
    final Biome[] biomes;
    final byte[] biome16;

    VoxelData(int chunkX, int chunkZ, int minY, int height, BlockState[] palette, short[] cells, Biome[] biomes, byte[] biome16) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minY = minY;
        this.height = height;
        this.palette = palette;
        this.cells = cells;
        this.biomes = biomes;
        this.biome16 = biome16;
        this.hash = Arrays.hashCode(cells) * 31 + palette.length;
    }

    long bytes() {
        return 200L + cells.length * 2L + palette.length * 8L;
    }

    // Below what's kept is solid ground: nothing there needs a face. Above it is sky.
    BlockState get(int x, int y, int z) {
        int up = y - minY;
        if (up < 0) return UNDER;
        if (up >= height) return AIR;
        return palette[cells[up * 256 + z * 16 + x]];
    }

    Biome biome(int x, int z) {
        return biomes[Math.min(biome16[(z >> 2) * 4 + (x >> 2)] & 255, biomes.length - 1)];
    }

    static VoxelData of(VoxelChunk c, Registry<Biome> registry) {
        BlockState[] states = new BlockState[c.palette.length];
        for (int i = 0; i < states.length; i++) states[i] = i == 0 ? AIR : BlockColours.parse(c.palette[i]);
        Biome fallback = registry.get(Biomes.PLAINS);
        if (fallback == null) fallback = registry.iterator().next();
        Biome[] biomes = new Biome[Math.max(1, c.biomes.length)];
        Arrays.fill(biomes, fallback);
        for (int i = 0; i < c.biomes.length; i++) {
            ResourceLocation id = ResourceLocation.tryParse(c.biomes[i]);
            Biome b = id == null ? null : registry.get(id);
            if (b != null) biomes[i] = b;
        }
        return new VoxelData(c.chunkX, c.chunkZ, c.minY, c.height, states, c.cells, biomes, c.biome16);
    }

    // From a copy of a chunk the game has loaded right now.
    static VoxelData from(ChunkSnapshot s) {
        int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int start = s.startY((z << 4) | x);
                boolean first = true;
                for (int y = start; y >= s.bottomY(); y--) {
                    PalettedContainer<BlockState> section = s.section(y >> 4);
                    if (section == null) {
                        y &= ~15;
                        continue;
                    }
                    BlockState state = section.get(x, y & 15, z);
                    if (state.isAir()) continue;
                    if (first) {
                        highest = Math.max(highest, y);
                        first = false;
                    }
                    if (!state.getFluidState().is(FluidTags.WATER) || start - y > 64) {
                        lowest = Math.min(lowest, y);
                        break;
                    }
                }
            }
        }
        if (highest == Integer.MIN_VALUE) {
            return new VoxelData(s.chunkX, s.chunkZ, 0, 0, new BlockState[] {AIR}, new short[0], new Biome[] {null}, new byte[16]);
        }
        if (lowest == Integer.MAX_VALUE) lowest = highest;
        int minY = Math.max(s.bottomY(), lowest - VoxelChunk.BELOW), height = highest - minY + 1;
        short[] cells = new short[height * 256];
        Map<BlockState, Integer> index = new IdentityHashMap<>();
        List<BlockState> list = new ArrayList<>();
        list.add(AIR);
        index.put(AIR, 0);
        for (int up = 0; up < height; up++) {
            int y = minY + up;
            PalettedContainer<BlockState> section = s.section(y >> 4);
            if (section == null) continue;
            for (int z = 0, o = up * 256; z < 16; z++) {
                for (int x = 0; x < 16; x++, o++) {
                    BlockState state = section.get(x, y & 15, z);
                    if (state.isAir()) continue;
                    Integer at = index.get(state);
                    if (at == null) {
                        at = list.size();
                        list.add(state);
                        index.put(state, at);
                    }
                    cells[o] = (short) (int) at;
                }
            }
        }
        // The biome under each 4x4 patch of columns.
        Map<Biome, Integer> biomeIndex = new IdentityHashMap<>();
        List<Biome> biomes = new ArrayList<>();
        byte[] biome16 = new byte[16];
        for (int q = 0; q < 16; q++) {
            int x = (q & 3) * 4 + 2, z = (q >> 2) * 4 + 2;
            Holder<Biome> holder = s.biome(s.chunkX * 16 + x, Math.max(minY, s.startY((z << 4) | x)), s.chunkZ * 16 + z);
            Biome b = holder.value();
            Integer at = biomeIndex.get(b);
            if (at == null) {
                at = biomes.size();
                biomes.add(b);
                biomeIndex.put(b, at);
            }
            biome16[q] = (byte) (int) at;
        }
        return new VoxelData(s.chunkX, s.chunkZ, minY, height, list.toArray(new BlockState[0]), cells, biomes.toArray(new Biome[0]), biome16);
    }

    // Back to names, for keeping on disk.
    VoxelChunk toChunk(Registry<Biome> registry) {
        VoxelChunk c = new VoxelChunk();
        c.chunkX = chunkX;
        c.chunkZ = chunkZ;
        c.minY = minY;
        c.height = height;
        c.cells = cells;
        c.stamp = (int) (System.currentTimeMillis() / 1000);
        c.palette = new String[palette.length];
        for (int i = 0; i < palette.length; i++) c.palette[i] = nameOf(palette[i]);
        c.biomes = new String[biomes.length];
        for (int i = 0; i < biomes.length; i++) {
            ResourceLocation id = biomes[i] == null ? null : registry.getKey(biomes[i]);
            c.biomes[i] = id == null ? "minecraft:plains" : id.toString();
        }
        System.arraycopy(biome16, 0, c.biome16, 0, 16);
        return c;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static String nameOf(BlockState state) {
        String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (state.getValues().isEmpty()) return name;
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Property<?>, Comparable<?>> e : state.getValues().entrySet()) {
            Property property = e.getKey();
            parts.add(property.getName() + "=" + property.getName((Comparable) e.getValue()));
        }
        Collections.sort(parts);
        return name + "[" + String.join(",", parts) + "]";
    }
}
