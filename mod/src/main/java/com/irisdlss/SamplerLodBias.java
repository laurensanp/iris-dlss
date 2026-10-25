package com.irisdlss;

/**
 * Texture LOD bias for RenderPearl's samplers. When the world renders below output resolution,
 * mip selection otherwise picks levels for the low resolution and DLSS gets blurry textures to
 * reconstruct from. Vulkan samplers are immutable and the game creates them at startup, so the bias
 * comes from the configured quality mode and a changed mode applies after a restart.
 */
public final class SamplerLodBias {

    private SamplerLodBias() {
    }

    /** VulkanGpuSampler creation: log2(render / output), the DLSS guideline, at most one level. */
    public static float vulkanBias() {
        if (!DlssConfig.enabled || !DlssConfig.textureLodBias) {
            return 0.0F;
        }
        double scale = switch (DlssConfig.quality) {
            case DLAA -> 1.0;
            case QUALITY -> 2.0 / 3.0;
            case BALANCED -> 0.58;
            case PERFORMANCE -> 0.5;
            case ULTRA_PERFORMANCE -> 1.0 / 3.0;
        };
        return Math.max(-1.0F, (float) (Math.log(scale) / Math.log(2.0)));
    }
}
