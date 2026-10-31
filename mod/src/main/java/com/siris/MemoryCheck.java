package com.siris;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * Java heap check. Shader packs plus map mods need more than the 3 GB some launchers give the game
 * (Lunar's default): the heap then sits at its limit and the garbage collector stalls the game for
 * 50-90 ms again and again (Lunar logs "Pause - Unknown"), which looks like an FPS drop that comes and
 * goes. The mod can't change the heap size, so it tells the player once per start after joining a
 * world, and again when the heap stays nearly full for half a minute.
 */
public final class MemoryCheck {

    /** Below this maximum heap the game stutters with a shader pack. */
    private static final long LOW_MAX_HEAP = 4L << 30;
    private static final long CHECK_INTERVAL_NS = 10_000_000_000L;
    /** Heap at least this full on this many checks in a row: warn. */
    private static final double FULL_FRACTION = 0.9;
    private static final int FULL_CHECKS = 3;
    private static final long FULL_WARNING_INTERVAL_NS = 600_000_000_000L;

    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(15000L);

    private static long lastCheck;
    private static boolean lowHeapWarned;
    private static int fullChecks;
    private static long lastFullWarning = Long.MIN_VALUE / 2;

    private MemoryCheck() {
    }

    /** Render thread, every frame. */
    public static void frame() {
        long now = System.nanoTime();
        if (now - lastCheck < CHECK_INTERVAL_NS) {
            return;
        }
        lastCheck = now;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            fullChecks = 0;
            return;
        }
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        if (max == Long.MAX_VALUE) {
            return;
        }
        if (!lowHeapWarned && max < LOW_MAX_HEAP) {
            lowHeapWarned = true;
            Siris.LOGGER.warn("SIRIS: Java has only {} of memory; with shader packs the garbage collector keeps "
                + "stalling the game. Allocate 6-8 GB in the launcher.", gigabytes(max));
            toast(minecraft, "Only " + gigabytes(max) + " of memory for Java",
                "Shader packs will stutter. Allocate 6-8 GB in your launcher.");
        }
        long used = runtime.totalMemory() - runtime.freeMemory();
        fullChecks = used >= max * FULL_FRACTION ? fullChecks + 1 : 0;
        if (fullChecks >= FULL_CHECKS && now - lastFullWarning > FULL_WARNING_INTERVAL_NS) {
            lastFullWarning = now;
            fullChecks = 0;
            Siris.LOGGER.warn("SIRIS: Java memory has been nearly full for 30 s ({} of {}); expect garbage "
                + "collection stutter. Allocate more in the launcher.", gigabytes(used), gigabytes(max));
            toast(minecraft, "Java memory nearly full (" + gigabytes(used) + " / " + gigabytes(max) + ")",
                "Expect stutter. Allocate more memory in your launcher.");
        }
    }

    private static void toast(Minecraft minecraft, String title, String message) {
        SystemToast.addOrUpdate(minecraft.gui.toastManager(), TOAST, Component.literal(title), Component.literal(message));
    }

    private static String gigabytes(long bytes) {
        return String.format("%.1f GB", bytes / (double) (1L << 30));
    }
}
