package com.example.charmap.client;

import com.example.charmap.CharMap;
import com.example.charmap.model.Shade;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GrassBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.TallGrassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.client.model.data.ModelData;

// One colour per block: the average of the texture on its top face, worked out once and kept. Runs off the main thread.
public final class BlockColours {
    private BlockColours() {}

    public enum Tint { NONE, GRASS, FOLIAGE, WATER }

    public static final class Info {
        public final int colour;
        public final Tint tint;
        public final boolean invisible, water, overlay, translucent;

        Info(int colour, Tint tint, boolean invisible, boolean water, boolean overlay, boolean translucent) {
            this.colour = colour;
            this.tint = tint;
            this.invisible = invisible;
            this.water = water;
            this.overlay = overlay;
            this.translucent = translucent;
        }
    }

    private static final Info AIR = new Info(0, Tint.NONE, true, false, false, false);

    private static final Map<BlockState, Info> BY_STATE = new ConcurrentHashMap<>();
    private static final Map<String, Info> BY_NAME = new ConcurrentHashMap<>();
    private static final Map<SpriteContents, Integer> BY_SPRITE = new ConcurrentHashMap<>();

    // After a resource pack change all of it is out of date.
    public static void clear() {
        BY_STATE.clear();
        BY_NAME.clear();
        BY_SPRITE.clear();
    }

    public static Info of(BlockState state) {
        Info i = BY_STATE.get(state);
        if (i != null) return i;
        i = work(state);
        BY_STATE.put(state, i);
        return i;
    }

    // A block as a save file writes it, "minecraft:oak_log[axis=y]".
    public static Info ofName(String name) {
        Info i = BY_NAME.get(name);
        if (i != null) return i;
        i = of(parse(name));
        BY_NAME.put(name, i);
        return i;
    }

    public static BlockState parse(String name) {
        try {
            int open = name.indexOf('[');
            String base = open < 0 ? name : name.substring(0, open);
            ResourceLocation id = ResourceLocation.tryParse(base);
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return Blocks.AIR.defaultBlockState();
            Block block = BuiltInRegistries.BLOCK.get(id);
            BlockState state = block.defaultBlockState();
            if (open < 0) return state;
            StateDefinition<Block, BlockState> def = block.getStateDefinition();
            for (String part : name.substring(open + 1, name.length() - 1).split(",")) {
                int eq = part.indexOf('=');
                if (eq > 0) state = withValue(state, def.getProperty(part.substring(0, eq)), part.substring(eq + 1));
            }
            return state;
        } catch (Throwable t) {
            CharMap.once("block-name", "Couldn't read a block name from a save: " + name, t);
            return Blocks.AIR.defaultBlockState();
        }
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
        if (property == null) return state;
        return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
    }

    private static Info work(BlockState state) {
        try {
            if (state.isAir() || state.is(Blocks.BARRIER) || state.is(Blocks.LIGHT) || state.is(Blocks.STRUCTURE_VOID)) return AIR;
            FluidState fluid = state.getFluidState();
            boolean water = fluid.is(FluidTags.WATER);
            Minecraft mc = Minecraft.getInstance();
            BlockModelShaper shaper = mc.getBlockRenderer().getBlockModelShaper();
            BakedModel model = shaper.getBlockModel(state);

            // Seagrass, kelp and waterlogged blocks count as water too: from above you look through it, the same as a save file is read.
            if (water || state.getBlock() instanceof LiquidBlock) {
                return new Info(fluidColour(fluid.isEmpty() ? net.minecraft.world.level.material.Fluids.LAVA : fluid.getType(), water), water ? Tint.WATER : Tint.NONE, false, water, false, false);
            }
            if (state.getRenderShape() == RenderShape.INVISIBLE && fluid.isEmpty()) {
                // Block entities that draw themselves (chests, signs): the particle texture is the best guess there is.
                TextureAtlasSprite sprite = shaper.getParticleIcon(state);
                int c = sprite == null ? 0 : average(sprite);
                if ((c >>> 24) < 8) return AIR;
                return new Info(0xFF000000 | c, Tint.NONE, false, false, false, false);
            }

            RandomSource random = RandomSource.create(42);
            List<BakedQuad> quads = model.getQuads(state, Direction.UP, random, ModelData.EMPTY, null);
            BakedQuad quad = quads.isEmpty() ? null : quads.get(quads.size() - 1);
            if (quad == null) {
                quads = model.getQuads(state, null, random, ModelData.EMPTY, null);
                if (!quads.isEmpty()) quad = bestOf(quads);
            }
            TextureAtlasSprite sprite = quad != null ? quad.getSprite() : model.getParticleIcon(ModelData.EMPTY);
            int average = sprite == null ? 0 : average(sprite);
            if ((average >>> 24) < 8) average = fromMapColour(state);

            Tint tint = Tint.NONE;
            int fixed = -1;
            if (quad != null && quad.isTinted()) {
                int plain = tintWithoutWorld(mc.getBlockColors(), state, quad.getTintIndex());
                Block block = state.getBlock();
                if (plain == GrassColor.getDefaultColor() || plain == GrassColor.get(0.5, 1.0) || block instanceof GrassBlock || block instanceof TallGrassBlock || state.is(Blocks.SUGAR_CANE)) tint = Tint.GRASS;
                else if (plain == FoliageColor.getDefaultColor()) tint = Tint.FOLIAGE;
                else if (plain != -1) fixed = plain;
                else if (block instanceof LeavesBlock) tint = Tint.FOLIAGE;
            }
            int colour = 0xFF000000 | (average & 0xFFFFFF);
            if (fixed != -1) colour = Shade.multiply(colour, fixed);

            boolean noCollision;
            try {
                noCollision = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
            } catch (Throwable t) {
                noCollision = false;
            }
            int alpha = average >>> 24;
            boolean glass = !noCollision && alpha < 200 && !(state.getBlock() instanceof LeavesBlock) && mc.getBlockRenderer() != null && isSeeThrough(state);
            return new Info(colour, tint, false, false, noCollision && !state.is(Blocks.SNOW), glass);
        } catch (Throwable t) {
            CharMap.once("block-colour", "Couldn't work out a colour for " + state, t);
            return new Info(0xFF000000 | fromMapColour(state), Tint.NONE, false, false, false, false);
        }
    }

    private static boolean isSeeThrough(BlockState state) {
        return !state.canOcclude();
    }

    // Among a block's general faces, the flattest one facing up if there is one (a lily pad), otherwise the last one.
    private static BakedQuad bestOf(List<BakedQuad> quads) {
        for (BakedQuad q : quads) if (q.getDirection() == Direction.UP) return q;
        return quads.get(quads.size() - 1);
    }

    private static int tintWithoutWorld(BlockColors colours, BlockState state, int index) {
        try {
            return colours.getColor(state, null, null, index);
        } catch (Throwable t) {
            return -1; // some mods' colour code insists on a world
        }
    }

    private static int fromMapColour(BlockState state) {
        try {
            return 0xFF000000 | state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
        } catch (Throwable t) {
            return 0xFF808080;
        }
    }

    private static int fluidColour(Fluid fluid, boolean water) {
        try {
            IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(fluid);
            ResourceLocation still = ext.getStillTexture();
            if (still != null) {
                TextureAtlasSprite sprite = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS).getSprite(still);
                int c = 0xFF000000 | average(sprite);
                if (!water) {
                    int tint = ext.getTintColor();
                    if (tint != -1 && tint != 0) c = Shade.multiply(c, tint);
                }
                return c;
            }
        } catch (Throwable t) {
            CharMap.once("fluid-colour", "Couldn't read a fluid's texture", t);
        }
        return water ? 0xFFB0B0B0 : 0xFFD06010;
    }

    // Average colour of a sprite's first animation frame. Alpha says how much of it is solid.
    static int average(TextureAtlasSprite sprite) {
        SpriteContents contents = sprite.contents();
        Integer known = BY_SPRITE.get(contents);
        if (known != null) return known;
        int w = contents.width(), h = contents.height();
        int[] pixels = new int[w * h];
        NativeImage image = contents.getOriginalImage();
        int n = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = image.getPixelRGBA(x, y); // despite the name this is ABGR
                pixels[n++] = (abgr & 0xFF00FF00) | ((abgr & 0xFF) << 16) | ((abgr >> 16) & 0xFF);
            }
        }
        int average = Shade.averagePixels(pixels, n);
        BY_SPRITE.put(contents, average);
        return average;
    }
}
