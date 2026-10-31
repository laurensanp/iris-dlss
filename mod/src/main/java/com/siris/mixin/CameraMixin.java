package com.siris.mixin;

import com.siris.DlssUpscaler;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public class CameraMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void siris$jitter(CameraRenderState cameraState, DeltaTracker deltaTracker, CallbackInfo ci) {
        DlssUpscaler.applyJitter(cameraState);
    }
}
