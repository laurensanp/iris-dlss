package com.irisdlss;

import net.minecraft.client.Minecraft;

/**
 * Dev option -Diris-dlss.benchmark=true: once in a world, cycles through DLSS modes (settings changed
 * the same way the settings screens change them) and logs rendered and shown fps of each.
 */
public final class DlssBenchmark {

    private static final boolean ENABLED = Boolean.getBoolean("iris-dlss.benchmark");
    private static final long WARMUP_NANOS = 20_000_000_000L;
    private static final long SETTLE_NANOS = 3_000_000_000L;
    private static final long MEASURE_NANOS = 10_000_000_000L;

    private record Mode(String name, boolean enabled, DlssConfig.Quality quality, boolean frameGeneration) {
    }

    private static final Mode[] MODES = {
        new Mode("SR Quality + FG", true, DlssConfig.Quality.QUALITY, true),
        new Mode("SR Quality", true, DlssConfig.Quality.QUALITY, false),
        new Mode("SR Performance", true, DlssConfig.Quality.PERFORMANCE, false),
        new Mode("DLAA", true, DlssConfig.Quality.DLAA, false),
        new Mode("DLSS off", false, DlssConfig.Quality.QUALITY, false),
        new Mode("SR Quality + FG (again)", true, DlssConfig.Quality.QUALITY, true),
    };

    private static long phaseStart;
    private static int mode = -1;
    private static int frames;
    private static long frameNanos;
    private static long lastFrame;
    private static long worstFrame;
    private static float shownSum;
    private static int shownSamples;

    private DlssBenchmark() {
    }

    /** Minecraft.runTick, every frame. */
    public static void frame() {
        if (!ENABLED || mode >= MODES.length) {
            return;
        }
        long now = System.nanoTime();
        if (Minecraft.getInstance().level == null || Minecraft.getInstance().player == null) {
            phaseStart = now;
            return;
        }
        if (mode < 0) {
            if (now - phaseStart >= WARMUP_NANOS) {
                start(0, now);
            }
            return;
        }
        long elapsed = now - phaseStart;
        if (elapsed >= SETTLE_NANOS) {
            if (lastFrame != 0L) {
                long frame = now - lastFrame;
                frames++;
                frameNanos += frame;
                worstFrame = Math.max(worstFrame, frame);
            }
            lastFrame = now;
            if (VulkanPresenter.isRunning() && frames % 30 == 0) {
                shownSum += VulkanPresenter.shownFps();
                shownSamples++;
            }
        }
        if (elapsed >= SETTLE_NANOS + MEASURE_NANOS) {
            Mode m = MODES[mode];
            double avgMs = frames > 0 ? frameNanos / 1e6 / frames : 0.0;
            IrisDlss.LOGGER.info("Iris DLSS benchmark: {} -> {} fps rendered (avg {} ms, worst {} ms), {} fps shown | {}",
                m.name, String.format("%.1f", avgMs > 0 ? 1000.0 / avgMs : 0.0), String.format("%.2f", avgMs),
                String.format("%.1f", worstFrame / 1e6),
                shownSamples > 0 ? String.format("%.0f", shownSum / shownSamples) : "-", DlssUpscaler.statusLine());
            start(mode + 1, now);
        }
    }

    private static void start(int index, long now) {
        mode = index;
        phaseStart = now;
        frames = 0;
        frameNanos = 0L;
        lastFrame = 0L;
        worstFrame = 0L;
        shownSum = 0.0F;
        shownSamples = 0;
        if (index >= MODES.length) {
            IrisDlss.LOGGER.info("Iris DLSS benchmark: done");
            return;
        }
        Mode m = MODES[index];
        DlssConfig.enabled = m.enabled;
        DlssConfig.quality = m.quality;
        DlssConfig.frameGeneration = m.frameGeneration;
        if (m.frameGeneration) {
            DlssConfig.vulkanPresent = true;
        }
        VulkanPresenter.retry();
        IrisDlss.LOGGER.info("Iris DLSS benchmark: measuring {}", m.name);
    }
}
