package com.siris.voxyvulkan.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Empty: applied after Voxy's LevelRenderer mixins (higher priority) so that
 * {@link com.siris.voxyvulkan.VoxyVulkanMixinPlugin#postApply} sees Voxy's merged Iris hook.
 */
@Mixin(value = LevelRenderer.class, priority = 2000)
public class LevelRendererMixin {
}
