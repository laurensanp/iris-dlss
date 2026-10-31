package com.siris.voxyvulkan.mixin;

import com.siris.voxyvulkan.LodRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While LODs are drawn the world continues past the vanilla render distance: its distance fog moves out
 * to the end of the LODs (water, lava and blindness fog stay as they are).
 */
@Mixin(FogRenderer.class)
public class FogRendererMixin {

    @Inject(method = "setupFog", at = @At("RETURN"))
    private void voxyvulkan$fogAtLodDistance(Camera camera, int renderDistance, DeltaTracker deltaTracker, float darken,
        ClientLevel level, CallbackInfoReturnable<FogData> cir) {
        float end = LodRenderer.fogEnd();
        FogData fog = cir.getReturnValue();
        if (end > fog.renderDistanceEnd) {
            fog.renderDistanceStart = end * 0.6F;
            fog.renderDistanceEnd = end;
        }
    }
}
