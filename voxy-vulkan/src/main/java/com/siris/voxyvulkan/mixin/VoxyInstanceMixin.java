package com.siris.voxyvulkan.mixin;

import com.siris.voxyvulkan.LodRenderer;
import me.cortex.voxy.commonImpl.VoxyInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The LOD renderer holds a reference on the world it draws; it lets go before Voxy closes its worlds. */
@Mixin(value = VoxyInstance.class, remap = false)
public class VoxyInstanceMixin {

    @Inject(method = "shutdown", at = @At("HEAD"))
    private void voxyvulkan$releaseWorld(CallbackInfo ci) {
        LodRenderer.shutdown();
    }
}
