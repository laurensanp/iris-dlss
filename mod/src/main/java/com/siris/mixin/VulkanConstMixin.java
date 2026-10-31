package com.siris.mixin;

import com.siris.VulkanAccess;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets VulkanAccess.createStorageTexture add VK_IMAGE_USAGE_STORAGE_BIT (DLSS writes its output so). */
@Mixin(VulkanConst.class)
public class VulkanConstMixin {

    @Inject(method = "textureUsageToVk", at = @At("RETURN"), cancellable = true)
    private static void siris$extraUsage(int usage, GpuFormat format, CallbackInfoReturnable<Integer> cir) {
        if (VulkanAccess.extraImageUsage != 0) {
            cir.setReturnValue(cir.getReturnValueI() | VulkanAccess.extraImageUsage);
        }
    }
}
