package com.irisdlss.mixin;

import com.irisdlss.SamplerLodBias;
import com.mojang.renderpearl.backend.opengl.GlSampler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlSampler.class)
public class GlSamplerMixin {

    @Shadow
    @Final
    private int id;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void irisdlss$track(CallbackInfo ci) {
        SamplerLodBias.register(this.id);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void irisdlss$untrack(CallbackInfo ci) {
        SamplerLodBias.unregister(this.id);
    }
}
