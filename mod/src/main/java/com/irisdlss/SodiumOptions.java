package com.irisdlss;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * "DLSS" entry in Sodium's video settings (Sodium replaces the vanilla screen, so the button on the
 * vanilla screen is not visible there). Entry point "sodium:config_api_user". Two pages: the everyday
 * settings, and the ones only needed for tuning or troubleshooting.
 */
public class SodiumOptions implements ConfigEntryPoint {

    private static final Identifier ENABLED = id("enabled");
    private static final Identifier QUALITY = id("quality");
    private static final Identifier PRESENTER = id("vulkan_output");
    private static final Identifier FRAME_GENERATION = id("frame_generation");

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("iris-dlss", path);
    }

    private static Component text(String text) {
        return Component.literal(text);
    }

    private static boolean dlssOn(ConfigState state) {
        return state.readBooleanOption(ENABLED);
    }

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        builder.registerOwnModOptions()
            .setName("DLSS")
            .setColorTheme(builder.createColorTheme().setBaseThemeRGB(0xFF76B900))
            .addPage(builder.createOptionPage()
                .setName(text("Settings"))
                .addOptionGroup(builder.createOptionGroup()
                    .setName(text("Upscaling"))
                    .addOption(builder.createBooleanOption(ENABLED)
                        .setName(text("DLSS"))
                        .setTooltip(text("Render the world at a lower resolution and upscale it with NVIDIA DLSS. "
                            + "The game runs on Vulkan while this is on (takes effect at the next start if the game "
                            + "started on OpenGL)."))
                        .setImpact(OptionImpact.HIGH)
                        .setDefaultValue(true)
                        .setBinding(value -> {
                            DlssConfig.enabled = value;
                            VulkanPresenter.retry();
                        }, () -> DlssConfig.enabled)
                        .setStorageHandler(DlssConfig::save))
                    .addOption(builder.createEnumOption(QUALITY, DlssConfig.Quality.class)
                        .setName(text("Mode"))
                        .setTooltip(SodiumOptions::qualityTooltip)
                        .setImpact(OptionImpact.HIGH)
                        .setDefaultValue(DlssConfig.Quality.QUALITY)
                        .setEnabledProvider(SodiumOptions::dlssOn, ENABLED)
                        .setElementNameProvider(q -> text(q.label))
                        .setBinding(value -> DlssConfig.quality = value, () -> DlssConfig.quality)
                        .setStorageHandler(DlssConfig::save))
                    .addOption(builder.createIntegerOption(id("render_scale"))
                        .setName(text("Render resolution"))
                        .setTooltip(text("Only for mode \"Custom\": the world's render resolution per axis. Lower is "
                            + "faster and softer. 67 % = Quality, 50 % = Performance, 100 % = DLAA."))
                        .setImpact(OptionImpact.HIGH)
                        .setDefaultValue(67)
                        .setRange(33, 100, 1)
                        .setEnabledProvider(state -> dlssOn(state)
                            && state.readEnumOption(QUALITY, DlssConfig.Quality.class) == DlssConfig.Quality.CUSTOM,
                            ENABLED, QUALITY)
                        .setValueFormatter(v -> text(v + " %"))
                        .setBinding(value -> DlssConfig.renderScale = value, () -> DlssConfig.renderScale)
                        .setStorageHandler(DlssConfig::save))
                    .addOption(builder.createEnumOption(id("preset"), DlssConfig.Preset.class)
                        .setName(text("Model"))
                        .setTooltip(SodiumOptions::presetTooltip)
                        .setImpact(OptionImpact.MEDIUM)
                        .setDefaultValue(DlssConfig.Preset.K)
                        .setEnabledProvider(SodiumOptions::dlssOn, ENABLED)
                        .setElementNameProvider(p -> text(p.label))
                        .setBinding(value -> DlssConfig.preset = value, () -> DlssConfig.preset)
                        .setStorageHandler(DlssConfig::save))
                    .addOption(builder.createIntegerOption(id("sharpness"))
                        .setName(text("Sharpening"))
                        .setTooltip(text("Contrast-adaptive sharpening after DLSS. Counters the softness of lower "
                            + "render resolutions; too much makes edges grainy."))
                        .setImpact(OptionImpact.LOW)
                        .setDefaultValue(35)
                        .setRange(0, 100, 5)
                        .setEnabledProvider(SodiumOptions::dlssOn, ENABLED)
                        .setValueFormatter(v -> text(v == 0 ? "Off" : v + " %"))
                        .setBinding(value -> DlssConfig.sharpness = value / 100.0F,
                            () -> Math.round(DlssConfig.sharpness * 100))
                        .setStorageHandler(DlssConfig::save)))
                .addOptionGroup(builder.createOptionGroup()
                    .setName(text("Frame Generation"))
                    .addOption(builder.createBooleanOption(FRAME_GENERATION)
                        .setName(text("Frame Generation"))
                        .setTooltip(text("DLSS Frame Generation: one generated frame between two rendered ones, "
                            + "about twice the fps shown, slightly more latency. RTX 40/50 only. Needs the DLSS "
                            + "presenter. Lunar Client: turn off its \"Multi-Threaded Rendering\"."))
                        .setImpact(OptionImpact.HIGH)
                        .setDefaultValue(true)
                        .setEnabledProvider(state -> dlssOn(state) && state.readBooleanOption(PRESENTER),
                            ENABLED, PRESENTER)
                        .setBinding(value -> {
                            DlssConfig.frameGeneration = value;
                            VulkanPresenter.retry();
                        }, () -> DlssConfig.frameGeneration)
                        .setStorageHandler(DlssConfig::save))
                    .addOption(builder.createEnumOption(id("frame_gen_multiplier"), DlssConfig.FrameGenMode.class)
                        .setName(text("Generated frames"))
                        .setTooltip(SodiumOptions::frameGenTooltip)
                        .setImpact(OptionImpact.HIGH)
                        .setDefaultValue(DlssConfig.FrameGenMode.X2)
                        .setAllowedValuesProvider(state -> DlssConfig.FrameGenMode.supported(), ENABLED)
                        .setEnabledProvider(state -> dlssOn(state) && state.readBooleanOption(PRESENTER)
                            && state.readBooleanOption(FRAME_GENERATION)
                            && DlssConfig.FrameGenMode.supported().size() > 1, ENABLED, PRESENTER, FRAME_GENERATION)
                        .setElementNameProvider(m -> text(m.label))
                        .setBinding(value -> DlssConfig.frameGenMode = value, () -> DlssConfig.frameGenMode)
                        .setStorageHandler(DlssConfig::save))
                    .addOption(builder.createBooleanOption(PRESENTER)
                        .setName(text("DLSS presenter"))
                        .setTooltip(text("Show frames through the DLSS presenter's own swapchain instead of the "
                            + "game's. Needed for Frame Generation; also paces frames for lower latency."))
                        .setImpact(OptionImpact.LOW)
                        .setDefaultValue(true)
                        .setEnabledProvider(SodiumOptions::dlssOn, ENABLED)
                        .setBinding(value -> {
                            DlssConfig.vulkanPresent = value;
                            VulkanPresenter.retry();
                        }, () -> DlssConfig.vulkanPresent)
                        .setStorageHandler(DlssConfig::save)))
                .addOptionGroup(builder.createOptionGroup()
                    .setName(text("Display"))
                    .addOption(builder.createEnumOption(id("overlay"), DlssConfig.Overlay.class)
                        .setName(text("Performance overlay"))
                        .setTooltip(o -> text(switch (o) {
                            case OFF -> "No overlay.";
                            case FPS -> "Frames per second in the top left corner (shown and rendered with Frame "
                                + "Generation).";
                            case DETAILED -> "fps plus DLSS mode, render size, frame pacing and latency.";
                        }))
                        .setDefaultValue(DlssConfig.Overlay.OFF)
                        .setElementNameProvider(o -> text(o.label))
                        .setBinding(value -> DlssConfig.overlay = value, () -> DlssConfig.overlay)
                        .setStorageHandler(DlssConfig::save))))
            .addPage(builder.createOptionPage()
                .setName(text("Advanced"))
                .addOptionGroup(builder.createOptionGroup()
                    .setName(text("Image"))
                    .addOption(builder.createBooleanOption(id("lod_bias"))
                        .setName(text("Texture LOD bias"))
                        .setTooltip(text("Sample textures for the output resolution, not the lower render resolution "
                            + "(sharper distant blocks). Applies after a restart."))
                        .setDefaultValue(true)
                        .setBinding(value -> DlssConfig.textureLodBias = value, () -> DlssConfig.textureLodBias)
                        .setStorageHandler(DlssConfig::save))
                    .addOption(builder.createBooleanOption(id("pack_taa"))
                        .setName(text("Turn off shader pack TAA"))
                        .setTooltip(text("A shader pack's own temporal anti-aliasing on top of DLSS blurs and smears. "
                            + "When on, the pack's TAA option is switched off at the next start."))
                        .setDefaultValue(true)
                        .setBinding(value -> DlssConfig.disablePackTaa = value, () -> DlssConfig.disablePackTaa)
                        .setStorageHandler(DlssConfig::save)))
                .addOptionGroup(builder.createOptionGroup()
                    .setName(text("Troubleshooting"))
                    .addOption(builder.createIntegerOption(id("hand_distance"))
                        .setName(text("Hand distance"))
                        .setTooltip(text("Pixels closer than this are treated as the first-person hand (no motion). "
                            + "Raise it if the held item smears while turning."))
                        .setDefaultValue(8)
                        .setRange(0, 30, 1)
                        .setValueFormatter(v -> text(String.format("%.1f blocks", v / 10.0)))
                        .setBinding(value -> DlssConfig.handDistance = value / 10.0F,
                            () -> Math.round(DlssConfig.handDistance * 10))
                        .setStorageHandler(DlssConfig::save))
                    .addOption(builder.createEnumOption(id("jitter"), DlssConfig.JitterDirection.class)
                        .setName(text("Jitter direction"))
                        .setTooltip(text("Only if the image stays soft or wobbles: try the other directions and keep "
                            + "the sharpest."))
                        .setDefaultValue(DlssConfig.JitterDirection.NORMAL)
                        .setElementNameProvider(j -> text(j.label))
                        .setBinding(DlssConfig.JitterDirection::apply, DlssConfig.JitterDirection::current)
                        .setStorageHandler(DlssConfig::save))));
    }

    static Component qualityTooltip(DlssConfig.Quality quality) {
        return text(switch (quality) {
            case DLAA -> "DLAA: full resolution, DLSS only anti-aliases. Sharpest, slowest.";
            case QUALITY -> "Quality: 67 % render resolution. Best balance.";
            case BALANCED -> "Balanced: 58 % render resolution.";
            case PERFORMANCE -> "Performance: 50 % render resolution.";
            case ULTRA_PERFORMANCE -> "Ultra Performance: 33 % render resolution, for very high output resolutions.";
            case CUSTOM -> "Custom: set the render resolution yourself (slider below).";
        });
    }

    static Component frameGenTooltip(DlssConfig.FrameGenMode mode) {
        String text = switch (mode) {
            case X2 -> "2x: one generated frame per rendered one (RTX 40/50).";
            case X3 -> "3x: two generated frames per rendered one (DLSS 4 multi frame generation, RTX 50).";
            case X4 -> "4x: three generated frames per rendered one (DLSS 4 multi frame generation, RTX 50).";
        };
        int max = DlssUpscaler.frameGenerationMax();
        return text(text + (max < 0 ? " Which modes this GPU supports shows once DLSS ran in a world."
            : max < 3 ? " This GPU supports 2x only." : ""));
    }

    static Component presetTooltip(DlssConfig.Preset preset) {
        return text(switch (preset) {
            case K -> "K: transformer model, best detail. Default.";
            case L -> "L: second-generation transformer, NVIDIA's default for Ultra Performance. Most expensive.";
            case M -> "M: second-generation transformer, NVIDIA's default for Performance.";
            case J -> "J: transformer like K, slightly less ghosting, a bit more flicker.";
            case E -> "E: older CNN model. Cheapest (more fps), a bit softer.";
            case DEFAULT -> "Let the driver / NVIDIA app choose the model.";
        });
    }
}
