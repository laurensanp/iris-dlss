package com.siris;

import net.minecraft.client.Minecraft;

/**
 * Dev option -Dsiris.benchmark=true: once in a world, cycles through DLSS modes (settings changed
 * the same way the settings screens change them) and logs rendered and shown fps of each.
 */
public final class DlssBenchmark {

    private static final boolean ENABLED = Boolean.getBoolean("siris.benchmark");
    /** Dev option -Dsiris.devNoDlss=true: DLSS off once in a world, the game stays on Vulkan (pack comparisons with OpenGL). */
    private static final boolean NO_DLSS = Boolean.getBoolean("siris.devNoDlss");
    private static final long WARMUP_NANOS = 20_000_000_000L;
    private static final long SETTLE_NANOS = 3_000_000_000L;
    private static final long MEASURE_NANOS = 10_000_000_000L;

    private record Mode(String name, boolean enabled, DlssConfig.Quality quality, boolean frameGeneration,
        DlssConfig.Preset preset) {
    }

    private static final Mode[] MODES = {
        new Mode("SR Quality + FG", true, DlssConfig.Quality.QUALITY, true, DlssConfig.Preset.K),
        new Mode("SR Quality", true, DlssConfig.Quality.QUALITY, false, DlssConfig.Preset.K),
        new Mode("SR Performance", true, DlssConfig.Quality.PERFORMANCE, false, DlssConfig.Preset.K),
        new Mode("SR Custom 80 %", true, DlssConfig.Quality.CUSTOM, false, DlssConfig.Preset.K),
        new Mode("SR Quality, preset L", true, DlssConfig.Quality.QUALITY, false, DlssConfig.Preset.L),
        new Mode("SR Performance, preset M", true, DlssConfig.Quality.PERFORMANCE, false, DlssConfig.Preset.M),
        new Mode("SR Quality, preset E", true, DlssConfig.Quality.QUALITY, false, DlssConfig.Preset.E),
        new Mode("DLAA", true, DlssConfig.Quality.DLAA, false, DlssConfig.Preset.K),
        new Mode("DLSS off", false, DlssConfig.Quality.QUALITY, false, DlssConfig.Preset.K),
        new Mode("SR Quality + FG (again)", true, DlssConfig.Quality.QUALITY, true, DlssConfig.Preset.K),
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
        if (NO_DLSS && DlssConfig.enabled && Minecraft.getInstance().level != null) {
            DlssConfig.enabled = false;
            Siris.LOGGER.info("SIRIS: off for this session (-Dsiris.devNoDlss)");
        }
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
            Siris.LOGGER.info("SIRIS benchmark: {} -> {} fps rendered (avg {} ms, worst {} ms), {} fps shown | {}",
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
            Siris.LOGGER.info("SIRIS benchmark: done");
            return;
        }
        Mode m = MODES[index];
        DlssConfig.enabled = m.enabled;
        DlssConfig.quality = m.quality;
        DlssConfig.frameGeneration = m.frameGeneration;
        DlssConfig.preset = m.preset;
        DlssConfig.renderScale = 80;
        if (m.frameGeneration) {
            DlssConfig.vulkanPresent = true;
        }
        VulkanPresenter.retry();
        Siris.LOGGER.info("SIRIS benchmark: measuring {}", m.name);
    }
}
