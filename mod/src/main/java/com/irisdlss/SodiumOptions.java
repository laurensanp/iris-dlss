package com.irisdlss;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * "DLSS" entry in Sodium's video settings (Sodium replaces the vanilla screen, so the button on the
 * vanilla screen is not visible there). Entry point "sodium:config_api_user".
 */
public class SodiumOptions implements ConfigEntryPoint {

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("iris-dlss", path);
    }

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        builder.registerOwnModOptions()
            .setName("DLSS")
            .setColorTheme(builder.createColorTheme().setBaseThemeRGB(0xFF76B900))
            .addPage(builder.createOptionPage()
                .setName(Component.literal("DLSS"))
                .addOptionGroup(builder.createOptionGroup()
                    .setName(Component.literal("Super Resolution"))
                    .addOption(builder.createBooleanOption(id("enabled"))
                        .setName(Component.literal("DLSS"))
                        .setTooltip(Component.literal("Render the world at lower resolution and upscale it with "
                            + "NVIDIA DLSS. The game runs on Vulkan while this is on."))
                        .setImpact(OptionImpact.HIGH)
                        .setDefaultValue(true)
                        .setBinding(value -> {
                            DlssConfig.enabled = value;
                            VulkanPresenter.retry();
                        }, () -> DlssConfig.enabled)
                        .setStorageHandler(DlssConfig::save))
                    .addOption(builder.createEnumOption(id("quality"), DlssConfig.Quality.class)
                        .setName(Component.literal("Quality"))
                        .setTooltip(Component.literal("DLAA renders at full resolution: sharpest, like native. "
                            + "Quality 67 %, Balanced 58 %, Performance 50 %, Ultra Performance 33 %."))
                        .setImpact(OptionImpact.HIGH)
                        .setDefaultValue(DlssConfig.Quality.QUALITY)
                        .setElementNameProvider(q -> Component.literal(q.label))
                        .setBinding(value -> DlssConfig.quality = value, () -> DlssConfig.quality)
                        .setStorageHandler(DlssConfig::save))
                    .addOption(builder.createEnumOption(id("preset"), DlssConfig.Preset.class)
                        .setName(Component.literal("Model"))
                        .setTooltip(Component.literal("K: transformer model, best detail. J: less ghosting. E: older CNN model, "
                            + "noticeably cheaper on the GPU (more fps), a bit softer."))
                        .setDefaultValue(DlssConfig.Preset.K)
                        .setElementNameProvider(p -> Component.literal(p.label))
                        .setBinding(value -> DlssConfig.preset = value, () -> DlssConfig.preset)
                        .setStorageHandler(DlssConfig::save))
                    .addOption(builder.createIntegerOption(id("sharpness"))
                        .setName(Component.literal("Sharpening"))
                        .setTooltip(Component.literal("Contrast-adaptive sharpening after DLSS."))
                        .setDefaultValue(35)
                        .setRange(0, 100, 5)
                        .setValueFormatter(v -> Component.literal(v == 0 ? "Off" : v + "%"))
                        .setBinding(value -> DlssConfig.sharpness = value / 100.0F,
                            () -> Math.round(DlssConfig.sharpness * 100))
                        .setStorageHandler(DlssConfig::save))
                    .addOption(builder.createBooleanOption(id("lod_bias"))
                        .setName(Component.literal("Texture LOD bias"))
                        .setTooltip(Component.literal("Sample textures for the output resolution, not the lower "
                            + "render resolution (sharper distant blocks)."))
                        .setDefaultValue(true)
                        .setBinding(value -> DlssConfig.textureLodBias = value, () -> DlssConfig.textureLodBias)
                        .setStorageHandler(DlssConfig::save)))
                .addOptionGroup(builder.createOptionGroup()
                    .setName(Component.literal("Output"))
                    .addOption(builder.createBooleanOption(id("frame_generation"))
                        .setName(Component.literal("Frame Generation"))
                        .setTooltip(Component.literal("DLSS Frame Generation: one generated frame between two "
                            + "rendered ones (RTX 40/50). Needs the DLSS presenter."))
                        .setImpact(OptionImpact.HIGH)
                        .setDefaultValue(true)
                        .setBinding(value -> {
                            DlssConfig.frameGeneration = value;
                            if (value) {
                                DlssConfig.vulkanPresent = true;
                                VulkanPresenter.retry();
                            }
                        }, () -> DlssConfig.frameGeneration)
                        .setStorageHandler(DlssConfig::save))
                    .addOption(builder.createBooleanOption(id("vulkan_output"))
                        .setName(Component.literal("DLSS presenter"))
                        .setTooltip(Component.literal("Show frames through the DLSS presenter's own Vulkan swapchain "
                            + "instead of the game's (needed for Frame Generation)."))
                        .setDefaultValue(true)
                        .setBinding(value -> {
                            DlssConfig.vulkanPresent = value;
                            if (!value) {
                                DlssConfig.frameGeneration = false;
                            }
                            VulkanPresenter.retry();
                        }, () -> DlssConfig.vulkanPresent)
                        .setStorageHandler(DlssConfig::save)))
                .addOptionGroup(builder.createOptionGroup()
                    .setName(Component.literal("Troubleshooting"))
                    .addOption(builder.createEnumOption(id("jitter"), DlssConfig.JitterDirection.class)
                        .setName(Component.literal("Jitter direction"))
                        .setTooltip(Component.literal("Only if the image stays soft or wobbles: try the other "
                            + "directions and keep the sharpest."))
                        .setDefaultValue(DlssConfig.JitterDirection.NORMAL)
                        .setElementNameProvider(j -> Component.literal(j.label))
                        .setBinding(DlssConfig.JitterDirection::apply, DlssConfig.JitterDirection::current)
                        .setStorageHandler(DlssConfig::save))));
    }
}
