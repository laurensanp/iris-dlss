package com.siris;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/** config/siris.properties, editable in game through the settings screen. */
public final class DlssConfig {

    /**
     * NVSDK_NGX_PerfQuality_Value, in the order shown in the settings screen. {@code scale} is the render
     * resolution per axis; CUSTOM takes {@link #renderScale}.
     */
    public enum Quality {
        DLAA(5, "DLAA", "dlaa", 1.0),
        QUALITY(2, "Quality", "quality", 2.0 / 3.0),
        BALANCED(1, "Balanced", "balanced", 0.58),
        PERFORMANCE(0, "Performance", "performance", 0.5),
        ULTRA_PERFORMANCE(3, "Ultra Performance", "ultra_performance", 1.0 / 3.0),
        CUSTOM(-1, "Custom", "custom", 0.0);

        public final int ngx;
        public final String label;
        public final String key;
        private final double scale;

        Quality(int ngx, String label, String key, double scale) {
            this.ngx = ngx;
            this.label = label;
            this.key = key;
            this.scale = scale;
        }

        /** Render resolution per axis, 0.33..1. */
        public double scale() {
            return this == CUSTOM ? renderScale / 100.0 : scale;
        }

        /** The NGX mode DLSS is created with: CUSTOM uses the mode closest to its scale. */
        public int ngxMode() {
            if (this != CUSTOM) {
                return ngx;
            }
            double s = scale();
            return s >= 0.99 ? DLAA.ngx : s >= 0.62 ? QUALITY.ngx : s >= 0.54 ? BALANCED.ngx
                : s >= 0.42 ? PERFORMANCE.ngx : ULTRA_PERFORMANCE.ngx;
        }
    }

    /** NVSDK_NGX_DLSS_Hint_Render_Preset. */
    public enum Preset {
        K(11, "K (Transformer, best)", "k"),
        L(12, "L (Transformer 2)", "l"),
        M(13, "M (Transformer 2, performance)", "m"),
        J(10, "J (Transformer, less ghosting)", "j"),
        E(5, "E (CNN, fastest)", "e"),
        DEFAULT(0, "Driver default", "default");

        public final int ngx;
        public final String label;
        public final String key;

        Preset(int ngx, String label, String key) {
            this.ngx = ngx;
            this.label = label;
            this.key = key;
        }
    }

    /** Frame Generation multiplier: frames shown per rendered frame. 3x and 4x are DLSS 4 multi frame generation. */
    public enum FrameGenMode {
        X2(2, "2x", "2"),
        X3(3, "3x (RTX 50)", "3"),
        X4(4, "4x (RTX 50)", "4");

        public final int factor;
        public final String label;
        public final String key;

        FrameGenMode(int factor, String label, String key) {
            this.factor = factor;
            this.label = label;
            this.key = key;
        }

        /**
         * The modes the GPU supports. Before DLSS started in a world the GPU's maximum is unknown: then 2x
         * and the configured mode.
         */
        public static java.util.Set<FrameGenMode> supported() {
            int max = DlssUpscaler.frameGenerationMax();
            java.util.Set<FrameGenMode> modes = java.util.EnumSet.of(X2);
            for (FrameGenMode mode : values()) {
                if (max < 0 ? mode == frameGenMode : mode.factor <= max) {
                    modes.add(mode);
                }
            }
            return modes;
        }
    }

    /** Signs applied to the jitter reported to DLSS (diagnostic). */
    public enum JitterDirection {
        NORMAL("Normal", 1.0F, 1.0F),
        FLIP_X("Flip X", -1.0F, 1.0F),
        FLIP_Y("Flip Y", 1.0F, -1.0F),
        FLIP_BOTH("Flip both", -1.0F, -1.0F);

        public final String label;
        final float x;
        final float y;

        JitterDirection(String label, float x, float y) {
            this.label = label;
            this.x = x;
            this.y = y;
        }

        public static void apply(JitterDirection direction) {
            jitterSignX = direction.x;
            jitterSignY = direction.y;
            DlssUpscaler.requestReset();
        }

        public static JitterDirection current() {
            for (JitterDirection d : values()) {
                if (d.x == Math.signum(jitterSignX) && d.y == Math.signum(jitterSignY)) {
                    return d;
                }
            }
            return NORMAL;
        }
    }

    public static boolean enabled = true;
    public static Quality quality = Quality.QUALITY;
    public static Preset preset = Preset.K;
    /** Negative texture LOD bias so textures keep output-resolution detail (DLSS guideline). */
    public static boolean textureLodBias = true;
    /** Pixels closer than this (blocks) are treated as the view-locked hand: zero motion. */
    public static float handDistance = 0.8F;
    public static float jitterSignX = 1.0F;
    public static float jitterSignY = 1.0F;
    /** Show frames through the bridge's presenter instead of the game's swapchain (needed for frame generation). */
    public static boolean vulkanPresent = true;
    /** DLSS Frame Generation: one generated frame between two rendered ones. */
    public static boolean frameGeneration = true;
    /** Frames shown per rendered frame with Frame Generation; the presenter clamps it to what the GPU supports. */
    public static FrameGenMode frameGenMode = FrameGenMode.X2;
    /** Contrast-adaptive sharpening after DLSS, 0..1. */
    public static float sharpness = 0.35F;
    /** Render resolution in percent for {@link Quality#CUSTOM}. */
    public static int renderScale = 67;
    /** Switch the shader pack's own TAA off at startup (DLSS does the anti-aliasing). */
    public static boolean disablePackTaa = true;
    /** Small fps / DLSS lines in the corner of the HUD. */
    public static Overlay overlay = Overlay.OFF;
    /** Java Flight Recording of the last minutes, written to siris/recordings when the game closes (config file only). */
    public static boolean diagnosticRecording = false;
    /** Lunar Client fixes at start (config file only, read early by {@link LunarCompat}). */
    public static boolean lunarCompat = true;

    public enum Overlay {
        OFF("Off", "off"),
        FPS("FPS", "fps"),
        DETAILED("Detailed", "detailed");

        public final String label;
        public final String key;

        Overlay(String label, String key) {
            this.label = label;
            this.key = key;
        }
    }

    private static Path file;

    private DlssConfig() {
    }

    /**
     * config/siris.properties. Up to 1.1.1 the file was config/iris-dlss.properties: it is moved here once,
     * so settings carry over.
     */
    public static Path configFile() {
        Path dir = FabricLoader.getInstance().getConfigDir();
        Path current = dir.resolve("siris.properties");
        Path old = dir.resolve("iris-dlss.properties");
        if (!Files.exists(current) && Files.isRegularFile(old)) {
            try {
                Files.move(old, current);
            } catch (IOException ignored) {
                // read the old file's settings once more next start
            }
        }
        return current;
    }

    public static void load(Path path) {
        file = path;
        Properties p = new Properties();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                p.load(reader);
            } catch (IOException e) {
                Siris.LOGGER.warn("Could not read {}", path, e);
            }
        }
        enabled = Boolean.parseBoolean(p.getProperty("enabled", "true"));
        quality = parse(Quality.values(), p.getProperty("quality"), Quality.QUALITY);
        preset = parse(Preset.values(), p.getProperty("preset"), Preset.K);
        textureLodBias = Boolean.parseBoolean(p.getProperty("textureLodBias", "true"));
        handDistance = parseFloat(p.getProperty("handDistance"), 0.8F);
        jitterSignX = parseFloat(p.getProperty("jitterSignX"), 1.0F);
        jitterSignY = parseFloat(p.getProperty("jitterSignY"), 1.0F);
        vulkanPresent = Boolean.parseBoolean(p.getProperty("vulkanPresent", "true"));
        frameGeneration = Boolean.parseBoolean(p.getProperty("frameGeneration", "true"));
        frameGenMode = parse(FrameGenMode.values(), p.getProperty("frameGenMultiplier"), FrameGenMode.X2);
        sharpness = Math.max(0.0F, Math.min(1.0F, parseFloat(p.getProperty("sharpness"), 0.35F)));
        renderScale = Math.max(33, Math.min(100, Math.round(parseFloat(p.getProperty("renderScale"), 67.0F))));
        disablePackTaa = Boolean.parseBoolean(p.getProperty("disablePackTaa", "true"));
        overlay = parse(Overlay.values(), p.getProperty("overlay"), Overlay.OFF);
        diagnosticRecording = Boolean.parseBoolean(p.getProperty("diagnosticRecording", "false"));
        lunarCompat = !"false".equalsIgnoreCase(p.getProperty("lunarCompat", "true").trim());
        save();
    }

    public static void save() {
        if (file == null) {
            return;
        }
        try (Writer writer = Files.newBufferedWriter(file)) {
            writer.write("# SIRIS - also editable in game (Mod Menu > SIRIS)\n");
            writer.write("enabled=" + enabled + "\n");
            writer.write("# dlaa | quality | balanced | performance | ultra_performance | custom (renderScale %)\n");
            writer.write("quality=" + quality.key + "\n");
            writer.write("# k | l | m | j | e | default\n");
            writer.write("preset=" + preset.key + "\n");
            writer.write("textureLodBias=" + textureLodBias + "\n");
            writer.write("handDistance=" + handDistance + "\n");
            writer.write("jitterSignX=" + jitterSignX + "\n");
            writer.write("jitterSignY=" + jitterSignY + "\n");
            writer.write("vulkanPresent=" + vulkanPresent + "\n");
            writer.write("frameGeneration=" + frameGeneration + "\n");
            writer.write("# 2 | 3 | 4 (3 and 4 need an RTX 50 card)\n");
            writer.write("frameGenMultiplier=" + frameGenMode.key + "\n");
            writer.write("sharpness=" + sharpness + "\n");
            writer.write("renderScale=" + renderScale + "\n");
            writer.write("disablePackTaa=" + disablePackTaa + "\n");
            writer.write("# off | fps | detailed\n");
            writer.write("overlay=" + overlay.key + "\n");
            writer.write("# true: records the last 10 minutes (Java Flight Recorder) into siris/recordings when the game\n");
            writer.write("# closes, to find what makes the frame rate drop. Costs ~1-2 % while on.\n");
            writer.write("diagnosticRecording=" + diagnosticRecording + "\n");
            writer.write("# Lunar Client: true turns off its Multi-Threaded Rendering (needed for Frame Generation) and drops\n");
            writer.write("# Vitrail's barrier redirect that clashes with Lunar's. Read when the game starts.\n");
            writer.write("lunarCompat=" + lunarCompat + "\n");
        } catch (IOException e) {
            Siris.LOGGER.warn("Could not write {}", file, e);
        }
    }


    private static <E extends Enum<E>> E parse(E[] values, String value, E fallback) {
        if (value == null) {
            return fallback;
        }
        for (E e : values) {
            String key = e instanceof Quality q ? q.key : e instanceof Preset p ? p.key
                : e instanceof Overlay o ? o.key : e instanceof FrameGenMode f ? f.key : e.name();
            if (key.equalsIgnoreCase(value.trim())) {
                return e;
            }
        }
        return fallback;
    }

    private static float parseFloat(String value, float fallback) {
        try {
            return value == null ? fallback : Float.parseFloat(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
