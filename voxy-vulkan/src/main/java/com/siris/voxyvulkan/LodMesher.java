package com.siris.voxyvulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import me.cortex.voxy.common.world.other.Mapper;
import org.lwjgl.system.MemoryUtil;

/**
 * Builds the quads of one Voxy section (32x32x32 voxels; at level L one voxel is 2^L blocks) on the CPU:
 * every voxel face that borders air or a see-through block of another kind. The neighbouring sections'
 * facing layers decide the faces on the border. Vertices are POSITION_COLOR: position in blocks relative
 * to the section's corner, colour with the face shade in rgb and Voxy's light byte in alpha.
 */
final class LodMesher {

    static final int VERTEX_SIZE = 16;
    static final int SIZE = 32;
    // +x, -x, +y, -y, +z, -z
    static final int[][] DIRECTIONS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
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
    /** Light (block nibble high, sky nibble low) assumed beyond a border without a neighbouring section. */
    private static final int OPEN_SKY = 0x0F;

    record Mesh(ByteBuffer vertices, int quads) {
    }

    private LodMesher() {
    }

    static int index(int x, int y, int z) {
        return y << 10 | z << 5 | x;
    }

    /**
     * The layer of the neighbouring section that touches face {@code face} of this one, as
     * {@code layer[a * 32 + b]} with (a, b) the two coordinates along the face (see {@link #slabIndex}).
     */
    static long[] slab(long[] neighbour, int face) {
        long[] layer = new long[SIZE * SIZE];
        for (int a = 0; a < SIZE; a++) {
            for (int b = 0; b < SIZE; b++) {
                int x;
                int y;
                int z;
                switch (face) {
                    case 0 -> { x = 0; y = a; z = b; }
                    case 1 -> { x = SIZE - 1; y = a; z = b; }
                    case 2 -> { x = a; y = 0; z = b; }
                    case 3 -> { x = a; y = SIZE - 1; z = b; }
                    case 4 -> { x = a; y = b; z = 0; }
                    default -> { x = a; y = b; z = SIZE - 1; }
                }
                layer[a * SIZE + b] = neighbour[index(x, y, z)];
            }
        }
        return layer;
    }

    private static int slabIndex(int face, int x, int y, int z) {
        return switch (face) {
            case 0, 1 -> y * SIZE + z;
            case 2, 3 -> x * SIZE + z;
            default -> x * SIZE + y;
        };
    }

    /**
     * The vertices in a native buffer (free with MemoryUtil.memFree), or quads = 0 and no buffer.
     * {@code slabs[face]} is the touching layer of the neighbour in that direction, null if there is none.
     */
    static Mesh mesh(long[] voxels, long[][] slabs, int level, Mapper mapper, LodColors colors) {
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
                        if (!colors.visible(block)) {
                            continue;
                        }
                        for (int face = 0; face < 6; face++) {
                            int[] d = DIRECTIONS[face];
                            int nx = x + d[0];
                            int ny = y + d[1];
                            int nz = z + d[2];
                            boolean inside = nx >= 0 && ny >= 0 && nz >= 0 && nx < SIZE && ny < SIZE && nz < SIZE;
                            long neighbour;
                            boolean known = true;
                            if (inside) {
                                neighbour = voxels[index(nx, ny, nz)];
                            } else if (slabs[face] != null) {
                                neighbour = slabs[face][slabIndex(face, x, y, z)];
                            } else {
                                neighbour = Mapper.AIR;
                                known = false;
                            }
                            // Side faces of surface voxels on the section border are always drawn: the
                            // neighbour may be drawn at another level whose surface is a voxel higher or lower.
                            boolean skirt = !inside && d[1] == 0 && surface(voxels, x, y, z);
                            if (!skirt && !faceVisible(neighbour, block, mapper, colors)) {
                                continue;
                            }
                            int color = colors.color(block, Mapper.getBiomeId(voxel), LodColors.group(face));
                            if (color == 0) {
                                continue;
                            }
                            if (pass == 0) {
                                quads++;
                                continue;
                            }
                            // The light reaching the face is stored in the voxel it looks into; a skirt
                            // or a face into another block takes the light above the voxel.
                            boolean water = colors.water(block);
                            int light;
                            if (water && y + 1 < SIZE && Mapper.isAir(voxels[index(x, y + 1, z)])) {
                                light = Mapper.getLightId(voxels[index(x, y + 1, z)]);
                            } else if (known && Mapper.isAir(neighbour)) {
                                light = Mapper.getLightId(neighbour);
                            } else if (y + 1 < SIZE && Mapper.isAir(voxels[index(x, y + 1, z)])) {
                                light = Mapper.getLightId(voxels[index(x, y + 1, z)]);
                            } else {
                                light = known ? Mapper.getLightId(neighbour) : OPEN_SKY;
                            }
                            // Water is shaded like its surface on all sides, so steps between levels blend in.
                            putFace(out, x, y, z, face, scale, color, light, water ? 1.0F : SHADE[face]);
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

    /** The voxel has air above it (or is at the section's top). */
    private static boolean surface(long[] voxels, int x, int y, int z) {
        return y + 1 >= SIZE || Mapper.isAir(voxels[index(x, y + 1, z)]);
    }

    private static boolean faceVisible(long neighbour, int block, Mapper mapper, LodColors colors) {
        if (Mapper.isAir(neighbour)) {
            return true;
        }
        int other = Mapper.getBlockId(neighbour);
        if (other == block) {
            return false;
        }
        return !colors.visible(other) || mapper.getBlockStateOpacity(neighbour) < 15;
    }

    private static void putFace(ByteBuffer out, int x, int y, int z, int face, int scale, int color, int light,
        float shade) {
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
