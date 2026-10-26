package com.irisdlss.voxyvulkan;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import org.lwjgl.system.MemoryUtil;

/**
 * Off the render thread: picks the Voxy sections to draw for the current camera (coarser levels further
 * away) and meshes them. Reading sections can hit Voxy's database on disk, so none of this runs on the
 * render thread; it only takes the finished meshes ({@link #results}).
 */
final class LodScheduler {

    /** A section is drawn at level L once it is this many section sizes away; closer, its children are. */
    private static final double DETAIL = 2.0;

    record Result(long key, LodMesher.Mesh mesh) {
    }

    final ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();

    private final WorldEngine engine;
    private final LodColors colors;
    private final ExecutorService meshers;
    private final Thread selector;
    private final Set<Long> requested = ConcurrentHashMap.newKeySet();
    private volatile boolean running = true;
    private volatile long[] selection = new long[0];

    private volatile double cameraX;
    private volatile double cameraY;
    private volatile double cameraZ;
    private volatile double vanillaRadius;
    private volatile double lodRadius;
    private volatile boolean hasCamera;

    LodScheduler(WorldEngine engine) {
        this.engine = engine;
        this.colors = new LodColors(engine.getMapper());
        this.meshers = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "Voxy Vulkan mesher");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        // A changed section is meshed again the next time it is selected.
        engine.setDirtyCallback((section, flags, neighbours) -> this.requested.remove(section.key));
        this.selector = new Thread(this::selectLoop, "Voxy Vulkan selector");
        this.selector.setDaemon(true);
        this.selector.start();
    }

    /** Render thread, every frame. Radii in blocks. */
    void update(double x, double y, double z, double vanillaRadius, double lodRadius) {
        this.cameraX = x;
        this.cameraY = y;
        this.cameraZ = z;
        this.vanillaRadius = vanillaRadius;
        this.lodRadius = lodRadius;
        this.hasCamera = true;
    }

    long[] selection() {
        return this.selection;
    }

    void close() {
        this.running = false;
        this.selector.interrupt();
        this.meshers.shutdownNow();
        this.engine.setDirtyCallback(null);
        Result result;
        while ((result = this.results.poll()) != null) {
            if (result.mesh().vertices() != null) {
                MemoryUtil.memFree(result.mesh().vertices());
            }
        }
    }

    private void selectLoop() {
        while (this.running) {
            try {
                if (this.hasCamera) {
                    this.select();
                }
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                VoxyVulkan.LOGGER.error("Voxy Vulkan: section selection failed", e);
            }
        }
    }

    private void select() {
        double x = this.cameraX;
        double y = this.cameraY;
        double z = this.cameraZ;
        double radius = this.lodRadius;
        int top = WorldEngine.MAX_LOD_LAYER;
        int topSize = 32 << top;
        LongArrayList out = new LongArrayList();
        for (int sx = Math.floorDiv((int) (x - radius), topSize); sx <= Math.floorDiv((int) (x + radius), topSize); sx++) {
            for (int sz = Math.floorDiv((int) (z - radius), topSize); sz <= Math.floorDiv((int) (z + radius), topSize); sz++) {
                if (horizontalDistance(x, z, sx * topSize, sz * topSize, topSize) > radius) {
                    continue;
                }
                for (int sy = -2; sy <= 1; sy++) {
                    this.visit(top, sx, sy, sz, x, y, z, out);
                }
            }
        }
        long[] selected = out.toLongArray();
        this.selection = selected;
        LongOpenHashSet keep = new LongOpenHashSet(selected);
        this.requested.removeIf(key -> !keep.contains(key));
        for (long key : selected) {
            if (this.running && this.requested.add(key)) {
                this.meshers.execute(() -> this.mesh(key));
            }
        }
    }

    private void visit(int level, int sx, int sy, int sz, double x, double y, double z, LongArrayList out) {
        WorldSection section = this.engine.acquireIfExists(level, sx, sy, sz);
        if (section == null) {
            return;
        }
        byte children;
        try {
            children = section.getNonEmptyChildren();
        } finally {
            section.release();
        }
        int size = 32 << level;
        double minX = (double) sx * size;
        double minZ = (double) sz * size;
        // Fully inside the vanilla render distance: Sodium draws it.
        double farX = Math.max(Math.abs(minX - x), Math.abs(minX + size - x));
        double farZ = Math.max(Math.abs(minZ - z), Math.abs(minZ + size - z));
        if (farX * farX + farZ * farZ < this.vanillaRadius * this.vanillaRadius) {
            return;
        }
        double distance = distance(x, y, z, minX, (double) sy * size, minZ, size);
        if (level == 0 || children == 0 || distance >= size * DETAIL) {
            out.add(section.key);
            return;
        }
        for (int i = 0; i < 8; i++) {
            int cx = sx * 2 + (i & 1);
            int cy = sy * 2 + ((i >> 1) & 1);
            int cz = sz * 2 + ((i >> 2) & 1);
            if ((children >> WorldSection.getChildIndex(cx, cy, cz) & 1) != 0) {
                this.visit(level - 1, cx, cy, cz, x, y, z, out);
            }
        }
    }

    private void mesh(long key) {
        if (!this.running) {
            return;
        }
        try {
            WorldSection section = this.engine.acquireIfExists(key);
            if (section == null) {
                this.results.add(new Result(key, new LodMesher.Mesh(null, 0)));
                return;
            }
            long[] voxels;
            try {
                voxels = section.copyData();
            } finally {
                section.release();
            }
            this.results.add(new Result(key, LodMesher.mesh(voxels, WorldEngine.getLevel(key), this.engine.getMapper(),
                this.colors)));
        } catch (RuntimeException e) {
            this.requested.remove(key);
            VoxyVulkan.LOGGER.error("Voxy Vulkan: meshing {} failed", WorldEngine.pprintPos(key), e);
        }
    }

    private static double horizontalDistance(double x, double z, double minX, double minZ, double size) {
        double dx = Math.max(0.0, Math.max(minX - x, x - (minX + size)));
        double dz = Math.max(0.0, Math.max(minZ - z, z - (minZ + size)));
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double distance(double x, double y, double z, double minX, double minY, double minZ, double size) {
        double dx = Math.max(0.0, Math.max(minX - x, x - (minX + size)));
        double dy = Math.max(0.0, Math.max(minY - y, y - (minY + size)));
        double dz = Math.max(0.0, Math.max(minZ - z, z - (minZ + size)));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
