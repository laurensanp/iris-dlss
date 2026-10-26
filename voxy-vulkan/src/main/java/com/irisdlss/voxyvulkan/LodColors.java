package com.irisdlss.voxyvulkan;

import com.irisdlss.voxyvulkan.mixin.SpriteContentsAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Face colours per Voxy block id: the average colour of the block model's texture on its top, bottom and
 * sides, times the biome tint (grass, leaves, water) where the model asks for one. 0 = draw nothing
 * (air-like blocks, mostly see-through textures such as glass). Any thread.
 */
final class LodColors {

    static final int TOP = 0;
    static final int BOTTOM = 1;
    static final int SIDE = 2;
    private static final int UNKNOWN = -1;
    /** Tint index for water: the biome's water colour (the fluid renderer tints it, not BlockColors). */
    private static final int WATER_TINT = 1000;
    private static final Direction[] GROUP_DIRECTIONS = {Direction.UP, Direction.DOWN, Direction.NORTH};

    private final Mapper mapper;
    /** [blockId * 3 + group]: 0xRRGGBB, 0 = invisible, UNKNOWN = not computed. */
    private volatile int[] base = new int[0];
    /** [blockId * 3 + group]: the model's tint index, -1 = untinted. */
    private volatile int[] tintIndex = new int[0];
    private final Long2IntOpenHashMap tints = new Long2IntOpenHashMap();
    private volatile Biome[] biomes = new Biome[0];

    LodColors(Mapper mapper) {
        this.mapper = mapper;
    }

    static int group(int face) {
        return face == 2 ? TOP : face == 3 ? BOTTOM : SIDE;
    }

    /** Water (a fluid tinted with the biome's water colour). */
    boolean water(int blockId) {
        this.base(blockId, TOP);
        return this.tintIndex[blockId * 3 + TOP] == WATER_TINT;
    }

    /** False for blocks that draw nothing at all. */
    boolean visible(int blockId) {
        return this.base(blockId, TOP) != 0 || this.base(blockId, SIDE) != 0 || this.base(blockId, BOTTOM) != 0;
    }

    /** 0xRRGGBB of a face (group TOP / BOTTOM / SIDE) in the given Voxy biome, 0 = invisible. */
    int color(int blockId, int biomeId, int group) {
        int color = this.base(blockId, group);
        int tint = this.tintIndex[blockId * 3 + group];
        if (color == 0 || tint < 0) {
            return color;
        }
        int t = this.tint(blockId, tint, biomeId);
        int r = ((color >> 16) & 0xFF) * ((t >> 16) & 0xFF) / 255;
        int g = ((color >> 8) & 0xFF) * ((t >> 8) & 0xFF) / 255;
        int b = (color & 0xFF) * (t & 0xFF) / 255;
        return Math.max(1, r << 16 | g << 8 | b);
    }

    private int base(int blockId, int group) {
        int[] table = this.base;
        int index = blockId * 3 + group;
        if (index < table.length && table[index] != UNKNOWN) {
            return table[index];
        }
        this.compute(blockId);
        return this.base[index];
    }

    private synchronized void compute(int blockId) {
        int[] colors = this.base;
        int[] tints = this.tintIndex;
        if (blockId * 3 + 2 < colors.length && colors[blockId * 3] != UNKNOWN) {
            return;
        }
        if (blockId * 3 + 2 >= colors.length) {
            int size = Math.max(blockId * 3 + 3, colors.length * 2);
            int[] grown = Arrays.copyOf(colors, size);
            Arrays.fill(grown, colors.length, size, UNKNOWN);
            colors = grown;
            tints = Arrays.copyOf(tints, size);
        }
        BlockState state = this.mapper.getBlockStateFromBlockId(blockId);
        for (int group = 0; group < 3; group++) {
            int color = 0;
            int tint = -1;
            if (state != null && !state.isAir()) {
                try {
                    int[] result = faceColor(state, GROUP_DIRECTIONS[group]);
                    color = result[0];
                    tint = result[1];
                } catch (RuntimeException e) {
                    color = mapColor(state);
                }
            }
            colors[blockId * 3 + group] = color;
            tints[blockId * 3 + group] = tint;
        }
        this.tintIndex = tints;
        this.base = colors;
    }

    /** {0xRRGGBB or 0, tint index or -1}. */
    private static int[] faceColor(BlockState state, Direction direction) {
        Minecraft minecraft = Minecraft.getInstance();
        BlockStateModelSet models = minecraft.getModelManager().getBlockStateModelSet();
        BlockStateModel model = models.get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(42L), parts);
        BakedQuad quad = firstQuad(parts, direction);
        if (quad == null) {
            quad = firstQuad(parts, null);
        }
        if (quad != null) {
            int color = averageColor(quad.materialInfo().sprite());
            return new int[] {color, color == 0 ? -1 : quad.materialInfo().tintIndex()};
        }
        if (!state.getFluidState().isEmpty()) {
            // Fluids are not block models: their particle texture; water takes the biome's water colour.
            int color = averageColor(models.getParticleMaterial(state).sprite());
            boolean water = state.getFluidState().is(FluidTags.WATER);
            return new int[] {color == 0 ? mapColor(state) : color, water ? WATER_TINT : -1};
        }
        return new int[] {0, -1};
    }

    private static BakedQuad firstQuad(List<BlockStateModelPart> parts, Direction direction) {
        for (BlockStateModelPart part : parts) {
            List<BakedQuad> quads = part.getQuads(direction);
            if (!quads.isEmpty()) {
                return quads.get(0);
            }
        }
        return null;
    }

    /** Alpha-weighted average of the sprite's first frame; 0 if it is mostly see-through. */
    private static int averageColor(TextureAtlasSprite sprite) {
        SpriteContents contents = sprite.contents();
        NativeImage image = ((SpriteContentsAccessor) contents).voxyvulkan$getOriginalImage();
        if (image == null || image.isClosed()) {
            return 0;
        }
        int width = Math.min(contents.width(), image.getWidth());
        int height = Math.min(contents.height(), image.getHeight());
        long r = 0;
        long g = 0;
        long b = 0;
        long alpha = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = image.getPixel(x, y);
                int a = (argb >>> 24) & 0xFF;
                r += (long) ((argb >> 16) & 0xFF) * a;
                g += (long) ((argb >> 8) & 0xFF) * a;
                b += (long) (argb & 0xFF) * a;
                alpha += a;
            }
        }
        if (alpha < (long) width * height * 255 / 4) {
            return 0;
        }
        int color = (int) (r / alpha) << 16 | (int) (g / alpha) << 8 | (int) (b / alpha);
        return Math.max(1, color);
    }

    private static int mapColor(BlockState state) {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? 0 : state.getMapColor(level, BlockPos.ZERO).col & 0xFFFFFF;
    }

    private int tint(int blockId, int tintIndex, int biomeId) {
        long key = (long) blockId << 24 | (long) tintIndex << 12 | biomeId;
        synchronized (this.tints) {
            if (this.tints.containsKey(key)) {
                return this.tints.get(key);
            }
        }
        int color = 0xFFFFFF;
        BlockState state = this.mapper.getBlockStateFromBlockId(blockId);
        BlockTintSource source = state == null || tintIndex == WATER_TINT ? null
            : Minecraft.getInstance().getBlockColors().getTintSource(state, tintIndex);
        if (tintIndex == WATER_TINT) {
            Biome biome = this.biome(biomeId);
            color = biome == null ? 0x3F76E4 : biome.getWaterColor() & 0xFFFFFF;
            // Vanilla water is translucent over a darker bottom; opaque LOD water a little darker.
            color = ((color >> 16 & 0xFF) * 4 / 5) << 16 | ((color >> 8 & 0xFF) * 4 / 5) << 8 | (color & 0xFF) * 4 / 5;
        } else if (source != null) {
            Biome biome = this.biome(biomeId);
            color = (biome == null ? source.color(state) : source.colorInWorld(state, tintGetter(biome), BlockPos.ZERO)) & 0xFFFFFF;
        }
        synchronized (this.tints) {
            this.tints.put(key, color);
        }
        return color;
    }

    private Biome biome(int biomeId) {
        Biome[] table = this.biomes;
        if (biomeId < table.length && table[biomeId] != null) {
            return table[biomeId];
        }
        synchronized (this) {
            ClientLevel level = Minecraft.getInstance().level;
            Mapper.BiomeEntry[] entries = this.mapper.getBiomeEntries();
            if (level == null || biomeId >= entries.length || entries[biomeId] == null) {
                return null;
            }
            Biome biome = level.registryAccess().lookupOrThrow(Registries.BIOME)
                .getValue(Identifier.parse(entries[biomeId].biome));
            table = Arrays.copyOf(this.biomes, Math.max(this.biomes.length, biomeId + 1));
            table[biomeId] = biome;
            this.biomes = table;
            return biome;
        }
    }

    /** A world of nothing but one biome: what a tint source needs to pick grass, foliage or water colour. */
    private static BlockAndTintGetter tintGetter(Biome biome) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("getBlockTint") && args != null && args.length == 2) {
                return ((ColorResolver) args[1]).getColor(biome, 0.0, 0.0);
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            return method.invoke(BlockAndTintGetter.EMPTY, args);
        };
        return (BlockAndTintGetter) Proxy.newProxyInstance(LodColors.class.getClassLoader(),
            new Class<?>[] {BlockAndTintGetter.class}, handler);
    }
}
