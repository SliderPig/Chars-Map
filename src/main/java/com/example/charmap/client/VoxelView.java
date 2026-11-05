package com.example.charmap.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

// What the game's own block drawing code looks at while the model is built: a few chunks of kept blocks, always in full daylight. Used on the 3D view's own thread.
final class VoxelView implements BlockAndTintGetter {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    static final int ACROSS = 6;
    final VoxelData[] grid = new VoxelData[ACROSS * ACROSS];
    int baseX, baseZ;
    int minBuild, worldHeight;
    int cutY = Integer.MAX_VALUE;
    int top = Integer.MIN_VALUE;

    private VoxelData chunk(int blockX, int blockZ) {
        int cx = (blockX >> 4) - baseX, cz = (blockZ >> 4) - baseZ;
        if (cx < 0 || cz < 0 || cx >= ACROSS || cz >= ACROSS) return null;
        return grid[cz * ACROSS + cx];
    }

    BlockState at(int x, int y, int z) {
        if (y > cutY) return AIR;
        VoxelData d = chunk(x, z);
        return d == null ? AIR : d.get(x & 15, y, z & 15);
    }

    // Would this block stop the sun? Plants and water don't, much.
    boolean blocksSun(int x, int y, int z) {
        BlockState s = at(x, y, z);
        return !s.isAir() && s.getFluidState().isEmpty() && s.blocksMotion();
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return at(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        if (!shade) return 1f;
        switch (direction) {
            case DOWN: return 0.5f;
            case NORTH: case SOUTH: return 0.8f;
            case WEST: case EAST: return 0.6f;
            default: return 1f;
        }
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return null; // never asked for: the brightness methods below answer first
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        return layer == LightLayer.SKY ? 15 : 0;
    }

    @Override
    public int getRawBrightness(BlockPos pos, int darken) {
        return 15 - darken;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        VoxelData d = chunk(pos.getX(), pos.getZ());
        Biome b = d == null || d.height == 0 ? fallbackBiome() : d.biome(pos.getX() & 15, pos.getZ() & 15);
        if (b == null) b = fallbackBiome();
        return resolver.getColor(b, pos.getX(), pos.getZ());
    }

    private Biome fallbackBiome() {
        for (VoxelData d : grid) {
            if (d != null && d.height != 0 && d.biomes[0] != null) return d.biomes[0];
        }
        return net.minecraft.client.Minecraft.getInstance().level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME).get(net.minecraft.world.level.biome.Biomes.PLAINS);
    }

    @Override
    public int getHeight() {
        return worldHeight;
    }

    @Override
    public int getMinBuildHeight() {
        return minBuild;
    }
}
