package com.example.charmap.client;

import com.example.charmap.client.BlockColours.Info;
import com.example.charmap.client.BlockColours.Tint;
import com.example.charmap.model.ChunkSummary;
import com.example.charmap.model.Shade;
import com.example.charmap.model.TileCodec;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

// Turns a column into one map pixel. Used for chunks you can see and for chunks read from a save, so both look the same.
public final class Colourer {
    private Colourer() {}

    public static final int ROCK = 0xFF2B2430; // solid ground in a cave map: dark, so the tunnels stand out

    public record Column(int colour, int height) {}

    public static int tinted(Info info, Biome biome, int blockX, int blockZ) {
        switch (info.tint) {
            case GRASS: return Shade.multiply(info.colour, biome.getGrassColor(blockX, blockZ));
            case FOLIAGE: return Shade.multiply(info.colour, biome.getFoliageColor());
            case WATER: return Shade.multiply(info.colour, biome.getWaterColor());
            default: return info.colour;
        }
    }

    // top = first thing seen from above. below = the next thing under it, null if nothing.
    public static int colour(Info top, Info below, int topY, int belowY, Biome biome, int blockX, int blockZ) {
        int c = tinted(top, biome, blockX, blockZ);
        if (below == null || below.invisible) return TileCodec.known(c);
        if (top.water) {
            int bottom = tinted(below, biome, blockX, blockZ);
            return TileCodec.known(Shade.water(c, bottom, topY - belowY));
        }
        if (top.overlay) {
            // Flowers, tall grass and torches only colour the ground a bit: a meadow shouldn't look like static.
            return TileCodec.known(Shade.mix(tinted(below, biome, blockX, blockZ), c, 0.35f));
        }
        if (top.translucent) return TileCodec.known(Shade.mix(tinted(below, biome, blockX, blockZ), c, 0.6f));
        return TileCodec.known(c);
    }

    // Height to use for hills: plants don't count as ground, or grass would make the whole map speckled.
    public static int groundHeight(Info top, Info below, int topY, int belowY) {
        if (top.overlay && below != null && !below.invisible && !below.water) return belowY;
        return topY;
    }

    // A chunk as a save file described it. Fills the 256 colours and heights, and gives a fingerprint of them.
    public static int fromSummary(ChunkSummary s, Registry<Biome> biomes, int[] colours, short[] heights) {
        Info[] infos = new Info[s.blocks.length];
        for (int i = 0; i < infos.length; i++) infos[i] = BlockColours.ofName(s.blocks[i]);
        Biome[] biomeList = new Biome[s.biomes.length];
        Biome fallback = fallback(biomes);
        for (int i = 0; i < biomeList.length; i++) {
            ResourceLocation id = ResourceLocation.tryParse(s.biomes[i]);
            Biome b = id == null ? null : biomes.get(id);
            biomeList[i] = b == null ? fallback : b;
        }
        int hash = 1;
        for (int c = 0; c < 256; c++) {
            int x = c & 15, z = c >> 4;
            int topId = s.top[c];
            if (topId < 0 || topId >= infos.length || biomeList.length == 0) {
                colours[c] = 0;
                heights[c] = Shade.NO_HEIGHT;
                continue;
            }
            Info top = infos[topId];
            int belowId = s.below[c];
            Info below = belowId >= 0 && belowId < infos.length ? infos[belowId] : null;
            Biome biome = biomeList[Math.min(s.biome[c] & 255, biomeList.length - 1)];
            colours[c] = colour(top, below, s.topY[c], s.belowY[c], biome, s.chunkX * 16 + x, s.chunkZ * 16 + z);
            heights[c] = (short) groundHeight(top, below, s.topY[c], s.belowY[c]);
            hash = hash * 31 + colours[c] + heights[c] * 7;
        }
        return hash == 0 ? 1 : hash;
    }

    private static Biome fallback(Registry<Biome> biomes) {
        Biome b = biomes.get(Biomes.PLAINS);
        return b != null ? b : biomes.iterator().next();
    }

    // A live chunk. Scans the copied block data from the top down. layerTop = Integer.MIN_VALUE for the normal view of the sky.
    public static int fromSnapshot(ChunkSnapshot s, int[] colours, short[] heights) {
        int hash = 1;
        BlockState lastState = null;
        Info lastInfo = null;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int c = (z << 4) | x;
                int blockX = s.chunkX * 16 + x, blockZ = s.chunkZ * 16 + z;
                int start = s.startY(c);
                boolean inRoof = s.startInsideSolid;
                Info top = null, below = null;
                int topY = 0, belowY = 0;
                int floorY = s.bottomY();
                for (int y = start; y >= floorY; y--) {
                    PalettedContainer<BlockState> section = s.section(y >> 4);
                    if (section == null) {
                        inRoof = false;
                        y &= ~15;
                        continue;
                    }
                    BlockState state = section.get(x, y & 15, z);
                    Info info;
                    if (state == lastState) info = lastInfo;
                    else {
                        info = BlockColours.of(state);
                        lastState = state;
                        lastInfo = info;
                    }
                    if (info.invisible) {
                        inRoof = false;
                        continue;
                    }
                    if (inRoof) continue;
                    if (top == null) {
                        top = info;
                        topY = y;
                        continue;
                    }
                    if (top.water && info.water) continue;
                    below = info;
                    belowY = y;
                    break;
                }
                if (top == null) {
                    if (s.sliceRock) {
                        colours[c] = ROCK;
                        heights[c] = (short) start;
                        hash = hash * 31 + ROCK;
                    } else {
                        colours[c] = 0;
                        heights[c] = Shade.NO_HEIGHT;
                    }
                    continue;
                }
                Holder<Biome> holder = s.biome(blockX, topY, blockZ);
                colours[c] = colour(top, below, topY, belowY, holder.value(), blockX, blockZ);
                heights[c] = (short) groundHeight(top, below, topY, belowY);
                hash = hash * 31 + colours[c] + heights[c] * 7;
            }
        }
        return hash == 0 ? 1 : hash;
    }
}
