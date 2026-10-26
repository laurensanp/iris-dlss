package com.irisdlss.voxyvulkan;

import java.util.Arrays;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One colour per Voxy block id, from the block's map colour (what a map shows for it). 0 = draw nothing
 * (blocks without a map colour: glass, barriers, light blocks).
 */
final class LodColors {

    private static final int UNKNOWN = -1;

    private final Mapper mapper;
    private volatile int[] colors = new int[0];

    LodColors(Mapper mapper) {
        this.mapper = mapper;
    }

    /** 0xRRGGBB, or 0 for an invisible block. Any thread. */
    int get(int blockId) {
        int[] table = this.colors;
        if (blockId < table.length && table[blockId] != UNKNOWN) {
            return table[blockId];
        }
        return this.compute(blockId);
    }

    private synchronized int compute(int blockId) {
        int[] table = this.colors;
        if (blockId >= table.length) {
            int[] grown = Arrays.copyOf(table, Math.max(blockId + 1, table.length * 2));
            Arrays.fill(grown, table.length, grown.length, UNKNOWN);
            table = grown;
        }
        int color = 0;
        BlockState state = this.mapper.getBlockStateFromBlockId(blockId);
        ClientLevel level = Minecraft.getInstance().level;
        if (state != null && !state.isAir() && level != null) {
            color = state.getMapColor(level, BlockPos.ZERO).col & 0xFFFFFF;
        }
        table[blockId] = color;
        this.colors = table;
        return color;
    }
}
