package com.irisdlss.mixin;

import com.irisdlss.DlssUpscaler;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NGX goes before the device: after the game waited for its queue, before vkDestroyDevice. */
@Mixin(VulkanDevice.class)
public class VulkanDeviceMixin {

    @Inject(method = "close", at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/vma/Vma;vmaDestroyAllocator(J)V"))
    private void irisdlss$shutdown(CallbackInfo ci) {
        DlssUpscaler.shutdown();
    }
}
