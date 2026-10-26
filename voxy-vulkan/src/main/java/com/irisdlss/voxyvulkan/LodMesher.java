package com.irisdlss.voxyvulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import me.cortex.voxy.common.world.other.Mapper;
import org.lwjgl.system.MemoryUtil;

/**
 * Builds the quads of one Voxy section (32x32x32 voxels; at level L one voxel is 2^L blocks) on the CPU:
 * every voxel face that borders air or a see-through block of another kind. Vertices are
 * POSITION_COLOR: position in blocks relative to the section's corner, colour with the face shade in
 * rgb and Voxy's light byte in alpha.
 */
final class LodMesher {

    static final int VERTEX_SIZE = 16;
    private static final int SIZE = 32;
    // +x, -x, +y, -y, +z, -z
    private static final int[][] DIRECTIONS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final float[] SHADE = {0.6F, 0.6F, 1.0F, 0.5F, 0.8F, 0.8F};
    // Corners of each face (unit cube), counter-clockwise seen from outside.
    private static final int[][][] CORNERS = {
        {{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}},
        {{0, 0, 1}, {0, 1, 1}, {0, 1, 0}, {0, 0, 0}},
        {{0, 1, 1}, {1, 1, 1}, {1, 1, 0}, {0, 1, 0}},
        {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},
        {{1, 0, 1}, {1, 1, 1}, {0, 1, 1}, {0, 0, 1}},
        {{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}},
    };

    record Mesh(ByteBuffer vertices, int quads) {
    }

    private LodMesher() {
    }

    static int index(int x, int y, int z) {
        return y << 10 | z << 5 | x;
    }

    /** The vertices in a native buffer (free with MemoryUtil.memFree), or quads = 0 and no buffer. */
    static Mesh mesh(long[] voxels, int level, Mapper mapper, LodColors colors) {
        int scale = 1 << level;
        int quads = 0;
        // Count first, then fill: one allocation of the exact size.
        for (int pass = 0; pass < 2; pass++) {
            ByteBuffer out = null;
            if (pass == 1) {
                if (quads == 0) {
                    return new Mesh(null, 0);
                }
                out = MemoryUtil.memAlloc(quads * 4 * VERTEX_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            }
            int written = 0;
            for (int y = 0; y < SIZE; y++) {
                for (int z = 0; z < SIZE; z++) {
                    for (int x = 0; x < SIZE; x++) {
                        long voxel = voxels[index(x, y, z)];
                        if (Mapper.isAir(voxel)) {
                            continue;
                        }
                        int block = Mapper.getBlockId(voxel);
                        int color = colors.get(block);
                        if (color == 0) {
                            continue;
                        }
                        for (int face = 0; face < 6; face++) {
                            int[] d = DIRECTIONS[face];
                            int nx = x + d[0];
                            int ny = y + d[1];
                            int nz = z + d[2];
                            boolean inside = nx >= 0 && ny >= 0 && nz >= 0 && nx < SIZE && ny < SIZE && nz < SIZE;
                            long neighbour = inside ? voxels[index(nx, ny, nz)] : Mapper.AIR;
                            if (!faceVisible(neighbour, block, mapper, colors)) {
                                continue;
                            }
                            if (pass == 0) {
                                quads++;
                                continue;
                            }
                            // The light reaching this face is stored in the neighbouring voxel (unknown at
                            // the section border).
                            int light = Math.max(Mapper.getLightId(voxel), inside ? Mapper.getLightId(neighbour) : 0);
                            putFace(out, x, y, z, face, scale, color, light);
                            written++;
                        }
                    }
                }
            }
            if (pass == 1) {
                out.flip();
                return new Mesh(out, written);
            }
        }
        throw new IllegalStateException();
    }

    private static boolean faceVisible(long neighbour, int block, Mapper mapper, LodColors colors) {
        if (Mapper.isAir(neighbour)) {
            return true;
        }
        int other = Mapper.getBlockId(neighbour);
        if (other == block) {
            return false;
        }
        return colors.get(other) == 0 || mapper.getBlockStateOpacity(neighbour) < 15;
    }

    private static void putFace(ByteBuffer out, int x, int y, int z, int face, int scale, int color, int light) {
        float shade = SHADE[face];
        int r = Math.round(((color >> 16) & 0xFF) * shade);
        int g = Math.round(((color >> 8) & 0xFF) * shade);
        int b = Math.round((color & 0xFF) * shade);
        for (int[] corner : CORNERS[face]) {
            out.putFloat((x + corner[0]) * scale);
            out.putFloat((y + corner[1]) * scale);
            out.putFloat((z + corner[2]) * scale);
            out.put((byte) r).put((byte) g).put((byte) b).put((byte) light);
        }
    }
}
