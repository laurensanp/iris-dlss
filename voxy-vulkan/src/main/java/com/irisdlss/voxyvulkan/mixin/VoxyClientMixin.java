package com.irisdlss.voxyvulkan.mixin;

import com.irisdlss.voxyvulkan.VoxyVulkan;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.commonImpl.VoxyCommon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Voxy's client start queries OpenGL capabilities and creates a GL index buffer, which abort the game on
 * Vulkan. On Vulkan only the part Voxy's data needs runs: registering its client instance (storage, ingest).
 */
@Mixin(value = VoxyClient.class, remap = false)
public class VoxyClientMixin {

    @Inject(method = "initVoxyClient", at = @At("HEAD"), cancellable = true)
    private static void voxyvulkan$initWithoutOpenGl(CallbackInfo ci) {
        if (VoxyVulkan.isVulkan()) {
            VoxyCommon.setInstanceFactory(VoxyClientInstance::new);
            VoxyVulkan.LOGGER.info("Voxy Vulkan: Voxy started without its OpenGL renderer");
            ci.cancel();
        }
    }
}
