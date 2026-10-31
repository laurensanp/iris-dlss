package com.siris.voxyvulkan.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Empty: applied after Voxy's Minecraft mixin (higher priority) so that
 * {@link com.siris.voxyvulkan.VoxyVulkanMixinPlugin#postApply} sees Voxy's merged device check.
 */
@Mixin(value = Minecraft.class, priority = 2000)
public class MinecraftMixin {
}
