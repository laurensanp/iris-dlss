package com.siris.mixin;

import com.siris.DlssNative;
import com.siris.Siris;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanCommandEncoder.class)
public class VulkanCommandEncoderMixin {

    /** Frame boundary: the bridge releases DLSS features no frame in flight still uses. */
    @Inject(method = "submit", at = @At("TAIL"))
    private void siris$submitted(CallbackInfo ci) {
        if (Siris.isNativeLoaded()) {
            DlssNative.frameSubmitted();
        }
    }
}
