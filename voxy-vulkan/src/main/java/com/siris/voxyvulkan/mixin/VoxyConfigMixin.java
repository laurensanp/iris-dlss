package com.siris.voxyvulkan.mixin;

import com.siris.voxyvulkan.VoxyVulkan;
import me.cortex.voxy.client.config.VoxyConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** On Vulkan Voxy never creates its OpenGL renderer; {@link com.siris.voxyvulkan.LodRenderer} draws instead. */
@Mixin(value = VoxyConfig.class, remap = false)
public class VoxyConfigMixin {

    @Inject(method = "isRenderingEnabled", at = @At("HEAD"), cancellable = true)
    private void voxyvulkan$noOpenGlRenderer(CallbackInfoReturnable<Boolean> cir) {
        if (VoxyVulkan.isVulkan()) {
            cir.setReturnValue(false);
        }
    }
}
