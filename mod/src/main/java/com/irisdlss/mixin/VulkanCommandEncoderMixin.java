package com.irisdlss.mixin;

import com.irisdlss.DlssNative;
import com.irisdlss.IrisDlss;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanCommandEncoder.class)
public class VulkanCommandEncoderMixin {

    /** Frame boundary: the bridge releases DLSS features no frame in flight still uses. */
    @Inject(method = "submit", at = @At("TAIL"))
    private void irisdlss$submitted(CallbackInfo ci) {
        if (IrisDlss.isNativeLoaded()) {
            DlssNative.frameSubmitted();
        }
    }
}
