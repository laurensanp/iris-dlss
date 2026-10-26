package com.irisdlss;

import java.util.Arrays;

/**
 * 1% low of the frames the game renders: the mean of the slowest 1% of frame times over the last
 * five seconds, as frames per second. The presenter reports the same for shown frames.
 */
public final class FrameStats {

    private static final long WINDOW_NANOS = 5_000_000_000L;
    private static final int CAPACITY = 4096;

    private static final long[] times = new long[CAPACITY];
    private static final long[] durations = new long[CAPACITY];
    private static int head;
    private static int size;
    private static long lastFrame;
    private static long lastComputed;
    private static float low;

    private FrameStats() {
    }

    /** Minecraft.runTick, once per rendered frame. */
    public static void frame() {
        long now = System.nanoTime();
        if (lastFrame != 0L) {
            int index = (head + size) % CAPACITY;
            if (size == CAPACITY) {
                head = (head + 1) % CAPACITY;
            } else {
                size++;
            }
            times[index] = now;
            durations[index] = now - lastFrame;
        }
        lastFrame = now;
        while (size > 0 && now - times[head] > WINDOW_NANOS) {
            head = (head + 1) % CAPACITY;
            size--;
        }
    }

    /** 1% low of rendered frames (fps), refreshed at most twice a second; 0 before the first frames. */
    public static float renderedLow() {
        long now = System.nanoTime();
        if (now - lastComputed > 500_000_000L) {
            lastComputed = now;
            low = compute();
        }
        return low;
    }

    private static float compute() {
        if (size == 0) {
            return 0.0F;
        }
        long[] sorted = new long[size];
        for (int i = 0; i < size; i++) {
            sorted[i] = durations[(head + i) % CAPACITY];
        }
        Arrays.sort(sorted);
        int count = Math.max(1, size / 100);
        long sum = 0L;
        for (int i = 0; i < count; i++) {
            sum += sorted[size - 1 - i];
        }
        return sum > 0L ? count * 1e9F / sum : 0.0F;
    }
}
