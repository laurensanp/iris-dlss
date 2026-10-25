package com.irisdlss;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** config/iris-dlss.properties, editable in game through the settings screen. */
public final class DlssConfig {

    /** NVSDK_NGX_PerfQuality_Value, in the order shown in the settings screen. */
    public enum Quality {
        DLAA(5, "DLAA", "dlaa"),
        QUALITY(2, "Quality", "quality"),
        BALANCED(1, "Balanced", "balanced"),
        PERFORMANCE(0, "Performance", "performance"),
        ULTRA_PERFORMANCE(3, "Ultra Performance", "ultra_performance");

        public final int ngx;
        public final String label;
        public final String key;

        Quality(int ngx, String label, String key) {
            this.ngx = ngx;
            this.label = label;
            this.key = key;
        }
    }

    /** NVSDK_NGX_DLSS_Hint_Render_Preset. */
    public enum Preset {
        K(11, "K (Transformer, best)", "k"),
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
    /** Contrast-adaptive sharpening after DLSS, 0..1. */
    public static float sharpness = 0.35F;

    private static Path file;

    private DlssConfig() {
    }

    public static void load(Path path) {
        file = path;
        Properties p = new Properties();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                p.load(reader);
            } catch (IOException e) {
                IrisDlss.LOGGER.warn("Could not read {}", path, e);
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
        sharpness = Math.max(0.0F, Math.min(1.0F, parseFloat(p.getProperty("sharpness"), 0.35F)));
        save();
    }

    public static void save() {
        if (file == null) {
            return;
        }
        try (Writer writer = Files.newBufferedWriter(file)) {
            writer.write("# Iris DLSS Bridge - also editable in game (Mod Menu > Iris DLSS Bridge)\n");
            writer.write("enabled=" + enabled + "\n");
            writer.write("# dlaa | quality | balanced | performance | ultra_performance\n");
            writer.write("quality=" + quality.key + "\n");
            writer.write("# k | j | default\n");
            writer.write("preset=" + preset.key + "\n");
            writer.write("textureLodBias=" + textureLodBias + "\n");
            writer.write("handDistance=" + handDistance + "\n");
            writer.write("jitterSignX=" + jitterSignX + "\n");
            writer.write("jitterSignY=" + jitterSignY + "\n");
            writer.write("vulkanPresent=" + vulkanPresent + "\n");
            writer.write("frameGeneration=" + frameGeneration + "\n");
            writer.write("sharpness=" + sharpness + "\n");
        } catch (IOException e) {
            IrisDlss.LOGGER.warn("Could not write {}", file, e);
        }
    }


    private static <E extends Enum<E>> E parse(E[] values, String value, E fallback) {
        if (value == null) {
            return fallback;
        }
        for (E e : values) {
            String key = e instanceof Quality q ? q.key : e instanceof Preset p ? p.key : e.name();
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
