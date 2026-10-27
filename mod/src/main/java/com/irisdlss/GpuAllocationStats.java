package com.irisdlss;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * diagnosticRecording=true also counts the textures and buffers created on the game's Vulkan device, by label,
 * and logs the biggest every 10 seconds ("Iris DLSS GPU allocations:"). Finds what fills video memory.
 */
public final class GpuAllocationStats {

    private static final long INTERVAL_NANOS = 10_000_000_000L;
    private static final Map<String, long[]> BY_LABEL = new ConcurrentHashMap<>();
    private static final AtomicLong WINDOW_START = new AtomicLong(System.nanoTime());

    private GpuAllocationStats() {
    }

    public static boolean enabled() {
        return DlssConfig.diagnosticRecording;
    }

    public static void texture(String label, int width, int height, int depth, int mips, int bytesPerTexel) {
        // A full mip chain adds a third.
        long bytes = (long) width * height * Math.max(1, depth) * bytesPerTexel * (mips > 1 ? 4 : 3) / 3;
        add("texture " + label, bytes);
    }

    public static void buffer(Supplier<String> label, long bytes) {
        String name;
        try {
            name = label == null ? "?" : label.get();
        } catch (RuntimeException e) {
            name = "?";
        }
        add("buffer " + name, bytes);
    }

    private static void add(String label, long bytes) {
        long[] entry = BY_LABEL.computeIfAbsent(label, k -> new long[2]);
        synchronized (entry) {
            entry[0]++;
            entry[1] += bytes;
        }
        long start = WINDOW_START.get();
        long now = System.nanoTime();
        if (now - start >= INTERVAL_NANOS && WINDOW_START.compareAndSet(start, now)) {
            report((now - start) / 1e9);
        }
    }

    private static void report(double seconds) {
        List<Map.Entry<String, long[]>> entries = new ArrayList<>(BY_LABEL.entrySet());
        BY_LABEL.clear();
        long total = 0;
        long count = 0;
        for (Map.Entry<String, long[]> e : entries) {
            total += e.getValue()[1];
            count += e.getValue()[0];
        }
        entries.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
        StringBuilder line = new StringBuilder(String.format("Iris DLSS GPU allocations: %.0f MB in %d allocations over %.0f s; top:",
            total / 1048576.0, count, seconds));
        for (int i = 0; i < Math.min(8, entries.size()); i++) {
            Map.Entry<String, long[]> e = entries.get(i);
            line.append(String.format(" [%s: %dx, %.1f MB]", e.getKey(), e.getValue()[0], e.getValue()[1] / 1048576.0));
        }
        IrisDlss.LOGGER.info(line.toString());
    }
}
